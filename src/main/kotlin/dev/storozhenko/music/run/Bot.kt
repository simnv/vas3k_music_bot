package dev.storozhenko.music.run

import dev.storozhenko.music.OdesilResponse
import dev.storozhenko.music.Quality
import dev.storozhenko.music.RequestOptions
import dev.storozhenko.music.eagerlyDelete
import dev.storozhenko.music.getLogger
import dev.storozhenko.music.parseRequestOptions
import dev.storozhenko.music.removeFirstLine
import dev.storozhenko.music.services.DownloadService
import dev.storozhenko.music.services.ErrorNotificationService
import dev.storozhenko.music.services.MediaProbeService
import dev.storozhenko.music.services.MediaProcessingService
import dev.storozhenko.music.services.MusicSearchService
import dev.storozhenko.music.services.OdesilService
import dev.storozhenko.music.services.VideoMeta
import dev.storozhenko.music.services.TelegramSender
import dev.storozhenko.music.services.UrlValidator
import dev.storozhenko.music.split2ByDash
import dev.storozhenko.music.validateVideoFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions
import org.telegram.telegrambots.meta.api.objects.MessageEntity
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow
import org.telegram.telegrambots.meta.generics.TelegramClient
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import java.io.File
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.jsoup.Jsoup

class Bot(
    private val botName: String,
    private val ytdlLocation: String,
    private val telegramClient: TelegramClient,
    private val telegramAllowList: String,
    private val errorNotificationTelegramId: String?,
    private val ipv6UrlContains: String?,
    private val chunkSizeMB: Int,
    private val ytdlProxy: String?,
    private val ytdlProxyUrlContains: String?
) : LongPollingUpdateConsumer {
    private val logger = getLogger()
    private val errorNotificationService = errorNotificationTelegramId?.let { ErrorNotificationService(telegramClient, it) }
    private val musicSearchService = MusicSearchService()
    private val odesilService = OdesilService(musicSearchService)
    private val urlValidator = UrlValidator()
    private val downloader: DownloadService
    private val probe: MediaProbeService
    private val processor: MediaProcessingService
    private val sender: TelegramSender
    val handler = CoroutineExceptionHandler { _, exception ->
        logger.error("Caught exception: $exception")
        errorNotificationService?.sendErrorNotification(exception)
    }
    private val virtualDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
    private val coroutine = CoroutineScope(virtualDispatcher + SupervisorJob() + handler)
    private val helpMessage = getResource("help_message.txt")
    private val chatsAndPlaylistNames = telegramAllowList
        .split(",")
        .map { line -> line.split(":") }
        .associate { (id, prefix) -> id.toLong() to prefix }
    private val fileDeleteScope = CoroutineScope(Dispatchers.Default + SupervisorJob() + handler)
    private val processorScope = CoroutineScope(virtualDispatcher + SupervisorJob() + handler)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val emptyKeyboard: InlineKeyboardMarkup = InlineKeyboardMarkup.builder().build()

    init {
        downloader = DownloadService(
            ytdlLocation, virtualDispatcher, fileDeleteScope,
            ipv6UrlContains, ytdlProxy, ytdlProxyUrlContains,
            errorNotificationService
        )
        probe = MediaProbeService(virtualDispatcher)
        processor = MediaProcessingService(virtualDispatcher, fileDeleteScope, chunkSizeMB, errorNotificationService)
        sender = TelegramSender(telegramClient, coroutine)
    }


    override fun consume(updates: MutableList<Update>) {
        logger.info("Got ${updates.size} updates")
        updates.forEach {
            processorScope.launch {
                runCatching {
                    if (it.hasCallbackQuery()) handleCallback(it.callbackQuery)
                    else consume(it)
                }.onFailure { e ->
                    if (e is CancellationException) {
                        logger.info("Update processing cancelled: ${e.message}")
                    } else {
                        logger.error("Can not process update $it", e)
                        errorNotificationService?.sendUpdateProcessingErrorNotification(e)
                    }
                }
            }
        }
    }

    private suspend fun handleCallback(query: CallbackQuery) {
        val data = query.data ?: return
        if (!data.startsWith("cancel:")) return
        val token = data.substringAfter("cancel:")
        val job = activeJobs.remove(token)
        val text = if (job != null) {
            job.cancel(CancellationException("user cancelled via button"))
            "Отменено"
        } else {
            query.message?.let { msg ->
                runCatching { sender.removeKeyboard(msg.chatId, msg.messageId) }
            }
            "Уже завершено"
        }
        runCatching {
            telegramClient.executeAsync(
                AnswerCallbackQuery.builder().callbackQueryId(query.id).text(text).build()
            ).await()
        }
    }

    private suspend fun consume(update: Update) {
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
                    errorNotificationService?.sendCommandErrorNotification(it, command)
                }
            }
            return
        }

        var initialMessage = update.message.text
        val urlEntities = entities.filter { entity -> entity.type == "url" }
        if (urlEntities.isEmpty()) {
            logger.info("No URL entities, returning")
            return
        }

        val odesilDetections = urlEntities.mapNotNull { odesilService.detect(it) }
        val links = odesilDetections.map { mapOdesilResponse(it.odesilResponse) }
        val validLinks = urlEntities.filter { entity -> urlValidator.isValidDownloadUrl(entity.text) }

        if (links.isEmpty() && validLinks.isEmpty()) {
            logger.info("No links from Odesil or valid video services, returning")
            return
        }

        val pulser = sender.startPulser(chatId, "typing")
        val cancelToken = UUID.randomUUID().toString()
        activeJobs[cancelToken] = requireNotNull(currentCoroutineContext()[Job]) { "no Job in coroutine context" }
        var tmId: Int? = null
        try {
        lateinit var linksMessage: String
        if (!links.isEmpty()) {
            linksMessage = if (links.size == 1) {
                links.first()
            } else {
                links.mapIndexed { index, l -> "${index + 1}. $l" }.joinToString(separator = "\n\n")
            }
        } else if (!validLinks.isEmpty()) {
            val validLink = validLinks.first()
            val meta = downloader.getVideoMeta(validLink.text)
            val displayTitle = formatTitleWithDuration(meta)
            val isMusicChat = chatsAndPlaylistNames[chatId]?.contains("music", ignoreCase = true) == true
            val odesilFromTitle = if (isMusicChat && isVkOrRutube(validLink.text) && meta.title.isNotBlank()) {
                val searchQuery = stripAnnotations(meta.title)
                downloader.ytSearchFirst(searchQuery, meta.durationSec)?.let { ytUrl ->
                    logger.info("VK/RuTube in music chat: probing Odesli via YT search '$ytUrl'")
                    odesilService.detect(ytUrl)
                }
            } else null
            linksMessage = if (odesilFromTitle != null) {
                mapOdesilResponse(odesilFromTitle) + "\n<a href=\"${validLink.text}\">${validLink.text}</a>"
            } else {
                "$displayTitle\n<a href=\"${validLink.text}\">${validLink.text}</a>"
            }
        }

        val youtubeFromMessage = extractFirstUrlByText(linksMessage, "Youtube")
        val ytSearchUrl: String? = if (youtubeFromMessage == null && odesilDetections.isNotEmpty()) {
            val firstDetection = odesilDetections.first().odesilResponse
            val data = firstDetection.entitiesByUniqueId[firstDetection.entityUniqueId]
            val query = listOfNotNull(data?.artistName, data?.title)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            if (query.isNotBlank()) downloader.ytSearchFirst(query) else null
        } else null
        if (ytSearchUrl != null) {
            val ytLink = "<a href=\"$ytSearchUrl\">YouTube search</a>"
            val parts = linksMessage.split("\n", limit = 2)
            linksMessage = if (parts.size == 2) "${parts[0]}\n$ytLink | ${parts[1]}" else "$linksMessage\n$ytLink"
        }

        val message = "$linksMessage"

        val (quality, forceAudio) = parseRequestOptions(update.message.text)
        val requestMode = if (forceAudio) "audio (forced)" else quality.label

        logger.info("Sending message: $message")
        val authorUsername = update.message.from?.userName
        val authorName = update.message.from?.let { listOfNotNull(it.firstName, it.lastName).joinToString(" ").ifBlank { null } }
        val chatTitle = update.message.chat.title ?: "Private Chat"
        errorNotificationService?.sendMessageWithSourceInfo(message, authorName, authorUsername, chatId, update.message.messageId, chatTitle, requestMode)

        val replyToMessageId = update.message.getMessageId()
        val cancelKb = cancelKeyboard(cancelToken)
        val sendMessage = SendMessage.builder()
            .chatId(chatId.toString())
            .text(message)
            .parseMode("HTML")
            .replyToMessageId(replyToMessageId)
            .linkPreviewOptions(LinkPreviewOptions.builder().isDisabled(true).build())
            .disableNotification(true)
            .replyMarkup(cancelKb)
            .build()
        val textMessage = telegramClient.executeAsync(sendMessage).await()
        val mid = textMessage.messageId
        tmId = mid

        val yturl = youtubeFromMessage ?: ytSearchUrl
        val success = if (yturl != null) {
            downloadAndSendVideo(yturl, message, mid, replyToMessageId, chatId, quality, forceAudio, pulser, cancelKb)
        } else if (!validLinks.isEmpty()) {
            val validurl = validLinks.first().text
            downloadAndSendVideo(validurl, message, mid, replyToMessageId, chatId, quality, forceAudio, pulser, cancelKb)
        } else {
            false
        }

        } catch (e: CancellationException) {
            tmId?.let { id ->
                withContext(NonCancellable) {
                    runCatching { sender.deleteMessage(chatId, id) }
                }
            }
            errorNotificationService?.sendErrorNotification(e, "Cancelled by user")
            throw e
        } finally {
            activeJobs.remove(cancelToken)
            pulser.close()
        }
    }

    private suspend fun downloadAndSendAudioOnly(url: String, message: String, intermediateMessageId: Int, chatId: Long, pulser: TelegramSender.ChatActionPulser, cancelKb: InlineKeyboardMarkup? = null): Boolean {
        suspend fun status(suffix: String) = sender.editMessageText(chatId, intermediateMessageId, "$message\n$suffix", cancelKb)
        suspend fun fail(reason: String) = sender.editMessageText(chatId, intermediateMessageId, "$message\n❌ $reason", emptyKeyboard)
        var downloadedFile: File? = null
        var thumbnailFile: File? = null
        try {
            pulser.set("typing")
            status("Downloading audio...")
            val downloadParams = downloader.commonYtDlpFlags(url) + listOf(
                "-f", "bestaudio[ext=m4a]/bestaudio[ext=mp3]/bestaudio",
                "--print", "before_dl:[QUALITY] source: id=%(format_id)s codec=%(acodec)s abr=%(abr)skbps asr=%(asr)sHz ext=%(ext)s"
            )
            downloadedFile = downloader.download("${UUID.randomUUID()}.%(ext)s", url, *downloadParams.toTypedArray())
                ?: run { fail("Failed to download audio: empty result"); return false }

            thumbnailFile = downloader.resolveSiblingThumbnail(downloadedFile)
                ?.let { runCatching { processor.convertToSquareThumbnail(it) }.getOrNull() ?: it }
            val duration = probe.getMediaDuration(downloadedFile)

            val (artist, title) = message.lineSequence().first().split2ByDash(true)
            status("Sending Audio...")
            pulser.set("upload_voice")
            sender.sendAudioInPlace(
                audioFile = downloadedFile,
                chatId = chatId,
                intermediateMessageId = intermediateMessageId,
                caption = message.removeFirstLine(),
                artist = artist,
                title = title,
                duration = duration,
                thumbnailFile = thumbnailFile
            )
            sender.removeKeyboard(chatId, intermediateMessageId)
            logger.info("Audio sent (audio-only fast path)")
            return true
        } catch (e: Exception) {
            logger.error("Error in downloadAndSendAudioOnly: ${e.message}", e)
            fail("Failed to send audio: ${e.message}")
            return false
        } finally {
            eagerlyDelete(logger, downloadedFile, thumbnailFile)
        }
    }

    private suspend fun downloadAndSendVideo(url: String, message:String, intermediateMessageId: Int, rmid: Int, chatId: Long, quality: Quality, forceAudio: Boolean, pulser: TelegramSender.ChatActionPulser, cancelKb: InlineKeyboardMarkup? = null): Boolean {
        if (forceAudio) return downloadAndSendAudioOnly(url, message, intermediateMessageId, chatId, pulser, cancelKb)
        suspend fun status(suffix: String) = sender.editMessageText(chatId, intermediateMessageId, "$message\n$suffix", cancelKb)
        suspend fun fail(reason: String) = sender.editMessageText(chatId, intermediateMessageId, "$message\n❌ $reason", emptyKeyboard)
        var downloadedFile: File? = null
        var thumbnailFile: File? = null
        var chunkFiles: List<File> = emptyList()
        var telegramAudioFile: File? = null

        try {
            pulser.set("typing")
            val downloadingMsg = if (quality == Quality.HIGH) "Downloading..." else "Downloading <code>${quality.label}</code>..."
            status("$downloadingMsg Use <code>low</code>/<code>medium</code>/<code>high</code> or <code>audio</code> after link to change quality.")
            val downloadParams = downloader.commonYtDlpFlags(url) + listOf(
                "-f", quality.formatSelector,
                "--merge-output-format", "mp4"
            )
            downloadedFile = downloader.download(
                "${UUID.randomUUID()}.%(ext)s",
                url,
                *downloadParams.toTypedArray()
            ) ?: run { fail("Failed to download file: empty result"); return false }

            val (isVideoValid, videoValidationMessage) = validateVideoFile(downloadedFile)
            if (!isVideoValid) {
                logger.error("Video validation failed: $videoValidationMessage")
                fail("Video validation failed: $videoValidationMessage")
                return false
            }

            val fileSizeInBytes = downloadedFile.length()
            val fileSizeInMB = fileSizeInBytes / (1024.0 * 1024.0)
            logger.info("Downloaded file size: ${String.format("%.2f", fileSizeInMB)} MB (${fileSizeInBytes} bytes)")

            thumbnailFile = downloader.resolveSiblingThumbnail(downloadedFile)

            status("Getting dimensions...")
            val videoDims = probe.getVideoDimensions(downloadedFile) ?: run {
                fail("Failed to get video dimensions"); return false
            }
            val videoDuration = videoDims.duration

            val (artist, title) = message.lineSequence().first().split2ByDash(true)
            var sendVideo = true
            val idHasMusic = chatsAndPlaylistNames[chatId]?.contains("music", ignoreCase = true) == true

            if (fileSizeInMB > chunkSizeMB) {
                status("Splitting video into chunks...")
                val outputPrefix = "${downloadedFile.parentFile.absolutePath}/${downloadedFile.nameWithoutExtension}_chunk.mp4"
                chunkFiles = processor.splitVideoIntoChunks(downloadedFile, outputPrefix)

                if (chunkFiles.isEmpty()) {
                    logger.error("No chunk files were created")
                    fail("Failed to split video into chunks")
                    return false
                }

                logger.info("Video split into ${chunkFiles.size} chunks")
            } else {
                chunkFiles = listOf(downloadedFile)
            }

            if (idHasMusic) {
                pulser.set("typing")
                status("Analyzing Video...")
                sendVideo = probe.decideSendAsVideo(downloadedFile, videoDuration)
            }

            if (!sendVideo) {
                logger.info("Sending Audio...")
                status("Sending Audio...")
                pulser.set("upload_voice")

                try {
                    val sourceAudioFile = chunkFiles.first()

                    status("Extracting audio...")
                    try {
                        telegramAudioFile = processor.convertToTelegramAudio(sourceAudioFile)
                    } catch (e: Exception) {
                        logger.error("Failed to extract audio: ${e.message}", e)
                        fail("Failed to extract audio: ${e.message}")
                        return false
                    }

                    val audioThumbnail = thumbnailFile
                        ?.let { runCatching { processor.convertToSquareThumbnail(it) }.getOrNull() ?: it }
                    sender.sendAudioInPlace(
                        audioFile = telegramAudioFile,
                        chatId = chatId,
                        intermediateMessageId = intermediateMessageId,
                        caption = message.removeFirstLine(),
                        artist = artist,
                        title = title,
                        duration = videoDuration,
                        thumbnailFile = audioThumbnail
                    )
                    sender.removeKeyboard(chatId, intermediateMessageId)
                    logger.info("Audio sent (status morphed in place)")
                } catch (e: TelegramApiException) {
                    logger.error("Failed to send audio: ${e.message}", e)
                    fail("Failed to send audio: ${e.message}")
                    return false
                }
            }

            if (sendVideo) {
                if (thumbnailFile != null && videoDims.height > videoDims.width) {
                    thumbnailFile = runCatching { processor.convertToLandscapeThumbnail(thumbnailFile!!) }.getOrNull() ?: thumbnailFile
                }
                val probedChunks = chunkFiles.map { f ->
                    val dims = probe.getVideoDimensions(f) ?: run {
                        fail("Failed to get video dimensions for a chunk")
                        return false
                    }
                    f to dims
                }
                if (!sender.sendVideoChunks(chatId, intermediateMessageId, rmid, probedChunks, message, thumbnailFile, pulser, cancelKb)) return false
                sender.removeKeyboard(chatId, intermediateMessageId)
            }

            return true
        } catch (e: Exception) {
            logger.error("Error in downloadAndSendVideo: ${e.message}", e)
            fail("Failed to process video: ${e.message}")
            return false
        } finally {
            val files = (chunkFiles + listOfNotNull(downloadedFile, thumbnailFile, telegramAudioFile)).toTypedArray()
            eagerlyDelete(logger, *files)
        }
    }

    private suspend fun processCommands(update: Update, command: String) {
        when (command) {
            "/help" -> sendHelp(update)
        }
    }

    private suspend fun sendHelp(update: Update) {
        logger.info("sending help")
        telegramClient.executeAsync(SendMessage(update.message.chatId.toString(), helpMessage)).await()
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

    private fun extractFirstUrlByText(html: String, linkText: String): String? {
        val doc = Jsoup.parse(html)
        val element = doc.select("a:containsOwn($linkText)").first()
        return element?.attr("href")
    }

    private fun formatTitleWithDuration(meta: VideoMeta): String =
        meta.durationSec?.let { "${meta.title} [%02d:%02d]".format(it / 60, it % 60) } ?: meta.title

    private fun stripAnnotations(s: String): String =
        s.replace(Regex("\\s*\\[[^\\]]*\\]"), "")
            .replace(Regex("\\s*\\([^)]*\\)"), "")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun cancelKeyboard(token: String): InlineKeyboardMarkup {
        val button = InlineKeyboardButton.builder()
            .text("❌ Отмена")
            .callbackData("cancel:$token")
            .build()
        val row = InlineKeyboardRow().apply { add(button) }
        return InlineKeyboardMarkup.builder().keyboardRow(row).build()
    }

    private fun isVkOrRutube(url: String): Boolean = runCatching {
        val host = URL(url).host.lowercase()
        host == "rutube.ru" || host.endsWith(".rutube.ru") ||
            host == "vk.com" || host.endsWith(".vk.com") ||
            host == "vk.ru" || host.endsWith(".vk.ru") ||
            host == "vkvideo.ru" || host.endsWith(".vkvideo.ru")
    }.getOrDefault(false)

}
