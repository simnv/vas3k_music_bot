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
import org.telegram.telegrambots.meta.api.methods.send.SendAudio
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
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
        val validLinks = entities
            .filter { entity -> entity.type == "url" && isValidDownloadUrl(entity.text) }

        if (links.isEmpty() && validLinks.isEmpty()) {
            logger.info("No links from Odesil or valid video services, returning")
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
        } else if (!validLinks.isEmpty()) {
            val validLink = validLinks.first()
            val videoTitle = getVideoTitle(validLink.text)
            linksMessage = "$videoTitle\n<a href=\"${validLink.text}\">${validLink.text}</a>"
        }

        val message = "$linksMessage"

        logger.info("Sending message: $message")
        val replyToMessageId = update.message.getMessageId()
        val textMessage = telegramClient.execute(SendMessage(chatId.toString(), message).apply { enableHtml(true); setReplyToMessageId(replyToMessageId); disableWebPagePreview(); disableNotification() })
        val tmId = textMessage.messageId

        val yturl = extractFirstUrlByText(message, "Youtube")
        if (yturl != null) {
            downloadAndSendVideo(yturl, message, tmId, replyToMessageId, chatId)
        } else if (!validLinks.isEmpty()) {
            val validurl = validLinks.first().text
            downloadAndSendVideo(validurl, message, tmId, replyToMessageId, chatId)
        }

        logger.info("Deleting intermediate text message $tmId")
        telegramClient.execute(DeleteMessage(chatId.toString(), tmId))
    }

    private fun downloadAndSendVideo(url: String, message:String, intermediateMessageId: Int, rmid: Int, chatId: Long) {
        telegramClient.execute(SendChatAction(chatId.toString(), "typing"))
        editMessageText(chatId, intermediateMessageId, "$message\nDownloading...")
        val useIPv6orIPv4 = if (url.contains("youtu")) "-6" else "-4"
        val downloadedFile = download(
            "${UUID.randomUUID()}.%(ext)s",
            url, 
            useIPv6orIPv4,
            "--cookies", "/cookies.txt",
            "-t", "mp4",
            "-I", "0",
            "--playlist-items", "1",
            "--write-thumbnail",
            "--embed-thumbnail",
            "--convert-thumbnails", "jpg"
        )
        val thumbnailFile = downloadedFile.changeExtension("jpg")
        if(thumbnailFile.exists()) {
            delayedDelete(thumbnailFile)
        }
        editMessageText(chatId, intermediateMessageId, "$message\nGetting dimensions...")
        val (videoWidth, videoHeight, videoDuration) = getVideoDimensions(downloadedFile).split(",").map { it.toDouble().toInt() }
        val (artist, title) = message.lineSequence().first().split2ByDash(true)
        var sendVideo = true;
        val idHasMusic = chatsAndPlaylistNames[chatId]?.contains("music", ignoreCase = true) == true;
        if (idHasMusic) {
            logger.info("Sending Audio...")
            editMessageText(chatId, intermediateMessageId, "$message\nSending Audio...")
            telegramClient.execute(SendChatAction(chatId.toString(), "upload_voice"))
            val audio = SendAudio
                .builder()
                .audio(InputFile(downloadedFile))
                .thumbnail(InputFile(thumbnailFile))
                .duration(videoDuration)
                .caption(message.removeFirstLine())
                .parseMode("HTML")
                .disableNotification(true)
                .performer(artist)
                .title(title)
                .chatId(chatId)
                .replyToMessageId(rmid)
                .build()
            val audioMessage = telegramClient.execute(audio)
            editMessageText(chatId, intermediateMessageId, "$message\nAnalyzing Video...")
            val videoCheckFreezeDuration = 30.0 // videoDuration * 0.15
            val totalFreezeDuration = getTotalFreezeDuration(downloadedFile, videoDuration * 0.2, videoCheckFreezeDuration)
            logger.info("${downloadedFile.absolutePath} totalFreezeDuration / videoCheckFreezeDuration = ${totalFreezeDuration} / ${videoCheckFreezeDuration} = ${totalFreezeDuration / videoCheckFreezeDuration}")
            sendVideo = (totalFreezeDuration / videoCheckFreezeDuration) < 0.5
        }

        if (sendVideo) {
            editMessageText(chatId, intermediateMessageId, "$message\nSending Video...")
            logger.info("Sending Video...")
            telegramClient.execute(SendChatAction(chatId.toString(), "upload_video"))
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
                .disableNotification(true)
                .chatId(chatId)
                .replyToMessageId(rmid)
                .build()
            telegramClient.execute(video)
        }

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
        // "youtubeMusic" to "YouTube Music",
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

    private fun delayedDelete(fileToDelete: File) {
        fileToDelete.deleteOnExit()
        fileDeleteScope.launch {
            runCatching {
                delay(55.minutes)
                fileToDelete.delete()
                logger.info("${fileToDelete.absolutePath} is deleted")
            }.onFailure {
                logger.error("Could not delete ${fileToDelete.absolutePath}")
            }
        }
    }

    private fun download(filename: String, url: String, vararg params: String): File {
        logger.info("Running yt-dlp for $url...")
        val folderName = UUID.randomUUID().toString()
        val folder = File("/tmp", folderName).apply { mkdir() }
        logger.info(params.joinToString(" "))
        val process =
            ProcessBuilder(ytdlLocation, url, "-o", "${folder.absolutePath}/$filename", *params).start()
        process.inputStream.reader(Charsets.UTF_8).use { logger.info(it.readText()) }
        process.waitFor()
        logger.info("Finished running yt-dlp for $url")
        var downloadedFile = folder.listFiles().first()
        downloadedFile = downloadedFile.changeExtension("mp4")
        delayedDelete(downloadedFile)
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

    private fun getTotalFreezeDuration(videoFile: File, startTime: Double, duration: Double): Double {
        logger.info("Running ffmpeg for $videoFile to find freezes...")
        val command = listOf(
            "ffmpeg",
            "-v", "error",
            "-i", videoFile.absolutePath,
            "-ss", startTime.toInt().toString(),
            "-t", duration.toInt().toString(),
            "-vf", "freezedetect=n=-60dB:d=1,metadata=mode=print:file=-",
            "-map", "0:v:0",
            "-f", "null",
            "-"
        )
        logger.info(command.joinToString(" "))

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        var totalFreezeDuration = 0.0
        val freezeDurationPattern = Regex("lavfi\\.freezedetect\\.freeze_duration=(\\d+\\.?\\d*)")

        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            reader.lineSequence().forEach { line ->
                logger.info(line)
                
                // Check for freeze duration and add to total
                freezeDurationPattern.find(line)?.let { match ->
                    totalFreezeDuration += match.groupValues[1].toDouble()
                }
            }
        }

        process.waitFor()
        logger.info("Total Freeze Duration: $totalFreezeDuration")
        return totalFreezeDuration
    }

    private fun getVideoTitle(url: String): String {
        logger.info("Running yt-dlp to get title for $url...")
        val useIPv6orIPv4 = if (url.contains("youtu")) "-6" else "-4"
        val command = listOf("yt-dlp", "--cookies", "/cookies.txt", "--print", "%(title)s", useIPv6orIPv4, url)
        logger.info(command.joinToString(" "))

        val process = ProcessBuilder(command)
            .redirectErrorStream(true) // Merge error stream with output
            .start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = reader.readLine() // Read first line of output
        logger.info("Got $output from yt-dlp")
        process.waitFor()
        if (output.contains(": No video formats found!")) {
            return ""
        }
        return output
    }

    data class DomainConfig(
        val hosts: Set<String>, // exact host names
        val allowedPaths: Set<String>, // path must start with one of these
        val blockedPathPrefixes: Set<String> = emptySet(),
        val extraPathTokens: Set<String> = emptySet()
    )

    private val VALID_URL_CONFIG = mapOf(
        "youtube" to DomainConfig(
            hosts = setOf("youtube.com", "youtu.be"),
            allowedPaths = setOf("/watch", "/shorts/", "/embed/", "/v/"),
            blockedPathPrefixes = setOf("/post/")
        ),

        "tiktok" to DomainConfig(
            hosts = setOf("tiktok.com", "vt.tiktok.com"),
            allowedPaths = setOf("/"),
            blockedPathPrefixes = emptySet()
        ),

        "vk" to DomainConfig(
            hosts = setOf("vk.com", "vk.ru", "vkvideo.ru"),
            allowedPaths = setOf("/video", "/clip"),
            blockedPathPrefixes = emptySet(),
            extraPathTokens = setOf("/video-", "/clip-")
        ),

        "rutube" to DomainConfig(
            hosts = setOf("rutube.ru"),
            allowedPaths = setOf("/video"),
            blockedPathPrefixes = emptySet()
        )
    )

    private fun isValidDownloadUrl(urlString: String): Boolean =
        runCatching {
            val url = URL(urlString)
            val host = url.host.lowercase()
            val path = url.path.lowercase()

            // Find the first config whose host matches (exact or sub-domain)
            val cfg = VALID_URL_CONFIG.values.firstOrNull { cfg ->
                cfg.hosts.any { exact ->
                    host == exact || host.endsWith(".$exact")
                }
            } ?: return false

            // 1. Reject blocked prefixes
            if (cfg.blockedPathPrefixes.any { path.startsWith(it) }) return false

            // 2. Accept explicit allowed prefixes
            if (cfg.allowedPaths.any { path.startsWith(it) }) return true

            // 3. Accept extra in-path tokens (VK-style "/video-123", etc.)
            cfg.extraPathTokens.any { path.contains(it) }
        }.getOrDefault(false)

    private fun splitAudioAndVideo(videoFile: File, videoFileName: String, audioFileName: String): String {
        logger.info("Running ffmpeg for splitting $videoFile...")
        val command = listOf("ffmpeg", "-i", videoFile.absolutePath,
            "-v", "error",
            "-an", "-c:v", "copy", videoFileName,
            "-vn", "-c:a", "copy", audioFileName)

        val process = ProcessBuilder(command)
            .redirectErrorStream(true) // Merge error stream with output
            .start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = reader.readLine() // Read first line of output
        logger.info("Got $output from ffprobe")
        process.waitFor()
        return output
    }

    private fun editMessageText(chatId: Long, messageId: Int, newText: String) {
        val editMessage = EditMessageText.builder()
            .chatId(chatId.toString())
            .messageId(messageId)
            .text(newText)
            .parseMode("HTML")
            .disableWebPagePreview(true)
            .build()

        try {
            telegramClient.execute(editMessage)
        } catch (e: TelegramApiException) {
            // Handle error (message might not exist or bot doesn't have permission)
            println("Failed to edit message: ${e.message}")
        }
    }
}

fun String.split2ByDash(reverseIfSingle: Boolean = false): Pair<String, String> {
    val parts = this.split(" - ", limit = 2)
    return when {
        parts.size == 2 -> parts[0] to parts[1]
        reverseIfSingle -> "" to parts[0]
        else -> parts[0] to ""
    }
}

fun String.removeFirstLine(): String {
    return this.lineSequence().drop(1).joinToString("\n")
}
