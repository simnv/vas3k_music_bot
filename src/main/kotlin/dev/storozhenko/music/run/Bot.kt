package dev.storozhenko.music.run

import dev.storozhenko.music.OdesilResponse
import dev.storozhenko.music.getLogger
import dev.storozhenko.music.services.OdesilService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.minutes
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.methods.ActionType
import org.telegram.telegrambots.meta.api.objects.MessageEntity
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.generics.TelegramClient
import org.telegram.telegrambots.meta.api.methods.send.SendVideo
import org.telegram.telegrambots.meta.api.objects.InputFile
import java.io.File
import java.util.UUID
import org.jsoup.Jsoup
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL

class Bot(
    private val botName: String,
    private val ytdlLocation: String,
    private val telegramClient: TelegramClient,
    private val telegramAllowList: String
) : LongPollingUpdateConsumer {
    private val logger = getLogger()
    private val odesilService = OdesilService()
    val handler = CoroutineExceptionHandler { _, exception ->
        logger.error("Caught exception: $exception")
    }
    private val coroutine = CoroutineScope(Dispatchers.IO + SupervisorJob() + handler)
    private val helpMessage = getResource("help_message.txt")
    private val chatsAndPlaylistNames = telegramAllowList
        .split(",")
        .map { line -> line.split(":") }
        .associate { (id, prefix) -> id.toLong() to prefix }
    private val fileDeleteScope = CoroutineScope(Dispatchers.Default)
    private val processorScope = CoroutineScope(Dispatchers.Default)

    override fun consume(updates: MutableList<Update>) {
        logger.info("Got ${updates.size} updates")
        updates.forEach {
            processorScope.launch {
                runCatching { consume(it) }
                    .onFailure { e -> logger.error("Can not process update $it", e) }
            }
        }
    }

    private fun consume(update: Update) {
        if (!update.hasMessage() || !update.message.hasText()) {
            logger.info("Got an update without text")
            return
        }

        val chatId = update.message.chatId
        if (chatId !in chatsAndPlaylistNames) {
            logger.info("Got an update from unauthorized chat $chatId")
            return
        }

        if (!update.message.hasEntities()) {
            logger.info("Got an update with no entities")
            return
        }

        val entities = update.message.entities
        val command = getCommand(entities)

        if (command != null) {
            logger.info("Processing command $command")
            coroutine.launch {
                runCatching {
                    processCommands(update, command)
                }.onFailure {
                    logger.error("Can't process command for $update", it)
                }
            }
            return
        }

        telegramClient.execute(SendChatAction(chatId.toString(), "typing"))
        var initialMessage = update.message.text
        val links = entities
            .filter { entity -> entity.type == "url" }
            .mapNotNull(odesilService::detect)
            .mapIndexed { index, odesilEntity ->
                mapOdesilResponse(odesilEntity.odesilResponse)
            }
        val vkLinks = entities
            .filter { entity -> entity.type == "url" && isVkUrl(entity.text) }

        if (links.isEmpty() && vkLinks.isEmpty()) {
            logger.info("No links from Odesil or vk, returning")
            return
        }

        val from = update.message.from.userName
        val fromId = update.message.from.id
        lateinit var linksMessage: String
        if (!links.isEmpty()) {
            linksMessage = if (links.size == 1) {
                links.first()
            } else {
                links.mapIndexed { index, l -> "${index + 1}. $l" }.joinToString(separator = "\n\n")
            }
        } else if (!vkLinks.isEmpty()) {
            val vkLink = vkLinks.first()
            val videoTitle = getVideoTitle(vkLink.text)
            linksMessage = "$videoTitle\n<a href=\"${vkLink.text}\">VK</a>"
        }

        val message = "$linksMessage"

        logger.info("Sending message: $message")
        val replyToMessageId = update.message.getMessageId()
        val textMessage = telegramClient.execute(SendMessage(chatId.toString(), message).apply { enableHtml(true); setReplyToMessageId(replyToMessageId) })
        val tmId = textMessage.messageId

        val yturl = extractFirstUrlByText(message, "Youtube")
        if (yturl != null) {
            downloadAndSendVideo(yturl, message, replyToMessageId, chatId)
        } else if (!vkLinks.isEmpty()) {
            val vkurl = vkLinks.first().text
            downloadAndSendVideo(vkurl, message, replyToMessageId, chatId)
        }

        logger.info("Deleting intermediate text message $tmId")
        telegramClient.execute(DeleteMessage(chatId.toString(), tmId))
    }

    private fun downloadAndSendVideo(url: String, message:String, rmid: Int, chatId: Long) {
        telegramClient.execute(SendChatAction(chatId.toString(), "upload_video"))
        val downloadedFile = downloadVideo(url, "--cookies", "/cookies.txt", "-t", "mp4", "--write-thumbnail", "--embed-thumbnail", "--convert-thumbnails", "jpg")
        val thumbnailFile = downloadedFile.changeExtension("jpg")
        val (videoWidth, videoHeight, videoDuration) = getVideoDimensions(downloadedFile).split(",").map { it.toDouble().toInt() }
        val video = SendVideo
            .builder()
            .video(InputFile(downloadedFile))
            .thumbnail(InputFile(thumbnailFile))
            .cover(InputFile(thumbnailFile))
            .width(videoWidth)
            .height(videoHeight)
            .duration(videoDuration)
            .caption(message)
            .parseMode("HTML")
            .showCaptionAboveMedia(true)
            .supportsStreaming(true)
            .chatId(chatId)
            .replyToMessageId(rmid)
            .build()
        telegramClient.execute(video)
        downloadedFile.delete()
        thumbnailFile.delete()
    }

    private suspend fun processCommands(update: Update, command: String) {
        when (command) {
            "/help" -> sendHelp(update)
        }
    }

    private fun sendHelp(update: Update) {
        logger.info("sending help")
        telegramClient.execute(SendMessage(update.message.chatId.toString(), helpMessage))
    }

    private val platformOrder = listOf(
        "yandex" to "Yandex.Music",
        "youtube" to "YouTube",
        "youtubeMusic" to "YouTube Music",
        "appleMusic" to "Apple Music",
        "itunes" to "iTunes",
        "spotify" to "Spotify",
        "google" to "Google",
        "googleStore" to "Google Store",
        "soundcloud" to "SoundCloud"
    )

    private fun mapOdesilResponse(odesilResponse: OdesilResponse): String {
        val odesilEntityData = odesilResponse.entitiesByUniqueId[odesilResponse.entityUniqueId]
        val title = odesilEntityData?.title ?: ""
        val artistName = odesilEntityData?.artistName ?: ""
        val platforms = platformOrder.mapNotNull { (platformId, platformName) ->
            odesilResponse.linksByPlatform[platformId]?.let { platformData -> platformName to platformData }
        }
        val songName = "$artistName - $title\n"
        return songName + platforms.joinToString(separator = " | ")
        { (platformName, platformData) -> "<a href=\"${platformData.url}\">${platformName}</a>" }
    }

    private fun getCommand(entities: List<MessageEntity>): String? {
        val entityText = entities.firstOrNull { entity -> entity.type == "bot_command" }?.text
        return if (entityText != null && (!entityText.contains("@") || entityText.contains(botName)))
            entityText.split("@").first()
        else {
            null
        }
    }

    private fun getResource(name: String): String {
        return this::class.java.classLoader.getResource(name)?.readText()
            ?: throw IllegalStateException("Resource $name is not found")
    }

    private fun downloadVideo(url: String, vararg params: String): File {
        return download("${UUID.randomUUID()}.%(ext)s", url, *params)
    }

    private fun downloadAudio(url: String, vararg params: String): File {
        return download("%(title)s.%(ext)s", url, "-x", "--audio-format", "mp3", "--no-playlist", *params)
    }

    private fun download(filename: String, url: String, vararg params: String): File {
        logger.info("Running yt-dlp for $url...")
        val folderName = UUID.randomUUID().toString()
        val folder = File("/tmp", folderName).apply { mkdir() }
        val process =
            ProcessBuilder(ytdlLocation, url, "-o", "${folder.absolutePath}/$filename", *params).start()
        process.inputStream.reader(Charsets.UTF_8).use { logger.info(it.readText()) }
        process.waitFor()
        logger.info("Finished running yt-dlp for $url")
        var downloadedFile = folder.listFiles().first()
        downloadedFile = downloadedFile.changeExtension("mp4")
        downloadedFile.deleteOnExit()
        fileDeleteScope.launch {
            runCatching {
                delay(5.minutes)
                downloadedFile.delete()
                logger.info("${downloadedFile.absolutePath} is deleted")
            }.onFailure {
                logger.error("Could not delete ${downloadedFile.absolutePath}")
            }
        }
        return downloadedFile
    }

    private fun extractFirstUrlByText(html: String, linkText: String): String? {
        val doc = Jsoup.parse(html)
        val element = doc.select("a:containsOwn($linkText)").first()
        return element?.attr("href")
    }

    private fun File.changeExtension(newExtension: String): File {
        val newName = "${this.nameWithoutExtension}.$newExtension"
        return this.resolveSibling(newName)
    }

    private fun getVideoDimensions(videoFile: File): String {
        logger.info("Running ffprobe for $videoFile...")
        val command = listOf("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height,duration", "-of", "csv=p=0", videoFile.absolutePath)

        val process = ProcessBuilder(command)
            .redirectErrorStream(true) // Merge error stream with output
            .start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = reader.readLine() // Read first line of output
        logger.info("Got $output from ffprobe")
        process.waitFor()
        return output
    }

    private fun getVideoTitle(url: String): String {
        logger.info("Running yt-dlp to get title for $url...")
        val command = listOf("yt-dlp", "--print", "%(title)s", url)

        val process = ProcessBuilder(command)
            .redirectErrorStream(true) // Merge error stream with output
            .start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = reader.readLine() // Read first line of output
        logger.info("Got $output from yt-dlp")
        process.waitFor()
        return output
    }

    fun isVkUrl(urlString: String): Boolean {
        return try {
            val url = URL(urlString)
            val host = url.host
            (host == "vk.com" || host == "vk.ru" || host.endsWith(".vk.com") || host.endsWith(".vk.ru")) && (url.path.startsWith("/video") || url.path.startsWith("/clip"))
        } catch (e: Exception) {
            false
        }
    }
}
