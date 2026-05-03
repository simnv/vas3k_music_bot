package dev.storozhenko.music.run

import dev.storozhenko.music.OdesilResponse
import dev.storozhenko.music.Quality
import dev.storozhenko.music.RequestOptions
import dev.storozhenko.music.getLogger
import dev.storozhenko.music.parseRequestOptions
import dev.storozhenko.music.removeFirstLine
import dev.storozhenko.music.services.DownloadService
import dev.storozhenko.music.services.ErrorNotificationService
import dev.storozhenko.music.services.MediaProbeService
import dev.storozhenko.music.services.MediaProcessingService
import dev.storozhenko.music.services.OdesilService
import dev.storozhenko.music.services.TelegramSender
import dev.storozhenko.music.services.UrlValidator
import dev.storozhenko.music.split2ByDash
import dev.storozhenko.music.validateVideoFile
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions
import org.telegram.telegrambots.meta.api.objects.MessageEntity
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.generics.TelegramClient
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import java.io.File
import java.util.UUID
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
    private val odesilService = OdesilService()
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
                runCatching { consume(it) }
                    .onFailure { e ->
                        logger.error("Can not process update $it", e)
                        errorNotificationService?.sendUpdateProcessingErrorNotification(e)
                    }
            }
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

        val pulser = sender.startPulser(chatId, "typing")
        try {
        val odesilDetections = urlEntities.mapNotNull(odesilService::detect)
        val links = odesilDetections.map { mapOdesilResponse(it.odesilResponse) }
        val validLinks = urlEntities.filter { entity -> urlValidator.isValidDownloadUrl(entity.text) }

        if (links.isEmpty() && validLinks.isEmpty()) {
            logger.info("No links from Odesil or valid video services, returning")
            return
        }

        lateinit var linksMessage: String
        if (!links.isEmpty()) {
            linksMessage = if (links.size == 1) {
                links.first()
            } else {
                links.mapIndexed { index, l -> "${index + 1}. $l" }.joinToString(separator = "\n\n")
            }
        } else if (!validLinks.isEmpty()) {
            val validLink = validLinks.first()
            val videoTitle = downloader.getVideoTitle(validLink.text)
            linksMessage = "$videoTitle\n<a href=\"${validLink.text}\">${validLink.text}</a>"
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
        // Send message with source info to error notification service
        val authorUsername = update.message.from?.userName
        val authorName = update.message.from?.let { listOfNotNull(it.firstName, it.lastName).joinToString(" ").ifBlank { null } }
        val chatTitle = update.message.chat.title ?: "Private Chat"
        errorNotificationService?.sendMessageWithSourceInfo(message, authorName, authorUsername, chatId, update.message.messageId, chatTitle, requestMode)

        val replyToMessageId = update.message.getMessageId()
        val sendMessage = SendMessage.builder()
            .chatId(chatId.toString())
            .text(message)
            .parseMode("HTML")
            .replyToMessageId(replyToMessageId)
            .linkPreviewOptions(LinkPreviewOptions.builder().isDisabled(true).build())
            .disableNotification(true)
            .build()
        val textMessage = telegramClient.executeAsync(sendMessage).await()
        val tmId = textMessage.messageId

        val yturl = youtubeFromMessage ?: ytSearchUrl
        val success = if (yturl != null) {
            downloadAndSendVideo(yturl, message, tmId, replyToMessageId, chatId, quality, forceAudio, pulser)
        } else if (!validLinks.isEmpty()) {
            val validurl = validLinks.first().text
            downloadAndSendVideo(validurl, message, tmId, replyToMessageId, chatId, quality, forceAudio, pulser)
        } else {
            // No video to download, but we have Odesli information
            // Keep the text message with Odesli information
            false
        }

        // Status message is now consumed in-place via EditMessageMedia on success,
        // and shows the error text on failure. No delete needed in either case.
        } finally {
            pulser.close()
        }
    }

    private suspend fun downloadAndSendAudioOnly(url: String, message: String, intermediateMessageId: Int, chatId: Long, pulser: TelegramSender.ChatActionPulser): Boolean {
        var downloadedFile: File? = null
        var thumbnailFile: File? = null
        try {
            pulser.set("typing")
            sender.editMessageText(chatId, intermediateMessageId, "$message\nDownloading audio...")
            val downloadParams = downloader.commonYtDlpFlags(url) + listOf("-f", "bestaudio[ext=m4a]/bestaudio")
            downloadedFile = downloader.download("${UUID.randomUUID()}.%(ext)s", url, *downloadParams.toTypedArray())
                ?: run {
                    sender.editMessageText(chatId, intermediateMessageId, "$message\n❌ Failed to download audio: empty result")
                    return false
                }

            thumbnailFile = downloader.resolveSiblingThumbnail(downloadedFile)
            val duration = probe.getMediaDuration(downloadedFile)

            val (artist, title) = message.lineSequence().first().split2ByDash(true)
            sender.editMessageText(chatId, intermediateMessageId, "$message\nSending Audio...")
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
            logger.info("Audio sent (audio-only fast path)")
            return true
        } catch (e: Exception) {
            logger.error("Error in downloadAndSendAudioOnly: ${e.message}", e)
            sender.editMessageText(chatId, intermediateMessageId, "$message\n❌ Failed to send audio: ${e.message}")
            return false
        } finally {
            try {
                downloadedFile?.let { if (it.exists()) { it.delete(); logger.info("Deleted audio file: ${it.absolutePath}") } }
                thumbnailFile?.let { if (it.exists()) { it.delete(); logger.info("Deleted thumbnail: ${it.absolutePath}") } }
            } catch (e: Exception) {
                logger.error("Error cleaning up files: ${e.message}", e)
            }
        }
    }

    private suspend fun downloadAndSendVideo(url: String, message:String, intermediateMessageId: Int, rmid: Int, chatId: Long, quality: Quality, forceAudio: Boolean, pulser: TelegramSender.ChatActionPulser): Boolean {
        if (forceAudio) return downloadAndSendAudioOnly(url, message, intermediateMessageId, chatId, pulser)
        var downloadedFile: File? = null
        var thumbnailFile: File? = null
        var chunkFiles: List<File> = emptyList()
        var telegramAudioFile: File? = null

        try {
            pulser.set("typing")
            sender.editMessageText(chatId, intermediateMessageId, "$message\nDownloading ${quality.label}... Use <code>low</code>/<code>medium</code>/<code>high</code> or <code>audio</code> after link to change.")
            val downloadParams = downloader.commonYtDlpFlags(url) + listOf(
                "-f", quality.formatSelector,
                "--merge-output-format", "mp4"
            )
            downloadedFile = downloader.download(
                "${UUID.randomUUID()}.%(ext)s",
                url,
                *downloadParams.toTypedArray()
            ) ?: run {
                sender.editMessageText(chatId, intermediateMessageId, "$message\n❌ Failed to download file: empty result")
                return false
            }
            
            // Validate downloaded file
            val (isVideoValid, videoValidationMessage) = validateVideoFile(downloadedFile)
            if (!isVideoValid) {
                logger.error("Video validation failed: $videoValidationMessage")
                sender.editMessageText(chatId, intermediateMessageId, "$message\n❌ Video validation failed: $videoValidationMessage")
                return false
            }
            
            // Log file size
            val fileSizeInBytes = downloadedFile.length()
            val fileSizeInMB = fileSizeInBytes / (1024.0 * 1024.0)
            logger.info("Downloaded file size: ${String.format("%.2f", fileSizeInMB)} MB (${fileSizeInBytes} bytes)")
            
            thumbnailFile = downloader.resolveSiblingThumbnail(downloadedFile)
            
            sender.editMessageText(chatId, intermediateMessageId, "$message\nGetting dimensions...")
            val dimensions = probe.getVideoDimensions(downloadedFile)
            logger.info("Video dimensions: $dimensions")
            
            val (videoWidth, videoHeight, videoDuration) = try {
                dimensions.split(",").map { it.toDouble().toInt() }
            } catch (e: Exception) {
                logger.error("Failed to parse video dimensions: $dimensions", e)
                sender.editMessageText(chatId, intermediateMessageId, "$message\n❌ Failed to get video dimensions")
                return false
            }
            
            val (artist, title) = message.lineSequence().first().split2ByDash(true)
            var sendVideo = true
            val idHasMusic = chatsAndPlaylistNames[chatId]?.contains("music", ignoreCase = true) == true
            
            // Split video into chunks if it's larger than the configured chunk size
            if (fileSizeInMB > chunkSizeMB) {
                sender.editMessageText(chatId, intermediateMessageId, "$message\nSplitting video into chunks...")
                val outputPrefix = "${downloadedFile.parentFile.absolutePath}/${downloadedFile.nameWithoutExtension}_chunk.mp4"
                chunkFiles = processor.splitVideoIntoChunks(downloadedFile, outputPrefix)
                
                if (chunkFiles.isEmpty()) {
                    logger.error("No chunk files were created")
                    sender.editMessageText(chatId, intermediateMessageId, "$message\n❌ Failed to split video into chunks")
                    return false
                }
                
                logger.info("Video split into ${chunkFiles.size} chunks")
            } else {
                chunkFiles = listOf(downloadedFile)
            }
            
            // Music chats analyze the video to decide audio vs video; non-music chats always get video.
            // forceAudio is handled in the audio-only fast path before we ever get here.
            if (idHasMusic) {
                pulser.set("typing")
                sender.editMessageText(chatId, intermediateMessageId, "$message\nAnalyzing Video...")
                sendVideo = probe.decideSendAsVideo(downloadedFile, videoDuration)
            }

            // Send audio if decision was audio (not video)
            if (!sendVideo) {
                logger.info("Sending Audio...")
                sender.editMessageText(chatId, intermediateMessageId, "$message\nSending Audio...")
                pulser.set("upload_voice")

                try {
                    // For audio, we'll use the original file (first chunk) but convert it to Telegram-compatible format
                    val sourceAudioFile = chunkFiles.first()

                    // Extract audio for Telegram
                    sender.editMessageText(chatId, intermediateMessageId, "$message\nExtracting audio...")
                    try {
                        telegramAudioFile = processor.convertToTelegramAudio(sourceAudioFile)
                    } catch (e: Exception) {
                        logger.error("Failed to extract audio: ${e.message}", e)
                        sender.editMessageText(chatId, intermediateMessageId, "$message\n❌ Failed to extract audio: ${e.message}")
                        return false
                    }

                    sender.sendAudioInPlace(
                        audioFile = telegramAudioFile,
                        chatId = chatId,
                        intermediateMessageId = intermediateMessageId,
                        caption = message.removeFirstLine(),
                        artist = artist,
                        title = title,
                        duration = videoDuration,
                        thumbnailFile = thumbnailFile
                    )
                    logger.info("Audio sent (status morphed in place)")
                } catch (e: TelegramApiException) {
                    logger.error("Failed to send audio: ${e.message}", e)
                    sender.editMessageText(chatId, intermediateMessageId, "$message\n❌ Failed to send audio: ${e.message}")
                    return false
                }
            }

            // Send video chunks
            if (sendVideo) {
                for ((index, chunkFile) in chunkFiles.withIndex()) {
                    val chunkNumber = index + 1
                    val totalChunks = chunkFiles.size
                    
                    // Update status message
                    val statusMessage = if (totalChunks > 1) {
                        "$message\nSending Video (Part $chunkNumber of $totalChunks)..."
                    } else {
                        "$message\nSending Video..."
                    }
                    
                    sender.editMessageText(chatId, intermediateMessageId, statusMessage)
                    logger.info("Sending Video chunk $chunkNumber of $totalChunks...")
                    pulser.set("upload_video")
                    
                    try {
                        // Get dimensions for this chunk
                        val chunkDimensions = probe.getVideoDimensions(chunkFile)
                        val (chunkWidth, chunkHeight, chunkDuration) = try {
                            chunkDimensions.split(",").map { it.toDouble().toInt() }
                        } catch (e: Exception) {
                            logger.error("Failed to parse video dimensions for chunk: $chunkDimensions", e)
                            sender.editMessageText(chatId, intermediateMessageId, "$message\n❌ Failed to get video dimensions for chunk $chunkNumber")
                            return false
                        }
                        
                        // Create caption for this chunk
                        val chunkCaption = if (totalChunks > 1) {
                            "$message\n\nPart $chunkNumber of $totalChunks"
                        } else {
                            message
                        }
                        
                        if (chunkNumber == 1) {
                            sender.sendVideoInPlace(
                                chatId = chatId,
                                intermediateMessageId = intermediateMessageId,
                                videoFile = chunkFile,
                                width = chunkWidth,
                                height = chunkHeight,
                                duration = chunkDuration,
                                caption = chunkCaption,
                                thumbnailFile = thumbnailFile
                            )
                        } else {
                            sender.sendVideoChunk(
                                chatId = chatId,
                                replyToMessageId = rmid,
                                videoFile = chunkFile,
                                width = chunkWidth,
                                height = chunkHeight,
                                duration = chunkDuration,
                                caption = chunkCaption,
                                thumbnailFile = thumbnailFile
                            )
                        }
                        logger.info("Video chunk $chunkNumber sent successfully")

                        // Add a small delay between sending chunks to avoid rate limiting
                        if (chunkNumber < totalChunks) {
                            delay(1000)
                        }
                    } catch (e: TelegramApiException) {
                        logger.error("Failed to send video chunk $chunkNumber: ${e.message}", e)
                        sender.editMessageText(chatId, intermediateMessageId, "$message\n❌ Failed to send video chunk $chunkNumber: ${e.message}")
                        return false
                    }
                }
            }

            return true
        } catch (e: Exception) {
            logger.error("Error in downloadAndSendVideo: ${e.message}", e)
            sender.editMessageText(chatId, intermediateMessageId, "$message\n❌ Failed to process video: ${e.message}")
            return false
        } finally {
            // Clean up files in finally block to ensure they're always deleted
            try {
                // Delete chunk files (excluding the original downloaded file which will be deleted separately)
                chunkFiles.forEach { chunkFile ->
                    if (chunkFile.exists() && chunkFile != downloadedFile) {
                        chunkFile.delete()
                        logger.info("Deleted chunk file: ${chunkFile.absolutePath}")
                    }
                }
                
                downloadedFile?.let { file ->
                    if (file.exists()) {
                        file.delete()
                        logger.info("Deleted downloaded file: ${file.absolutePath}")
                    }
                }
                thumbnailFile?.let { file ->
                    if (file.exists()) {
                        file.delete()
                        logger.info("Deleted thumbnail file: ${file.absolutePath}")
                    }
                }
                // Clean up converted Telegram audio file if it exists and wasn't already handled by delayedDelete
                telegramAudioFile?.let { file ->
                    if (file.exists()) {
                        file.delete()
                        logger.info("Deleted converted Telegram audio file: ${file.absolutePath}")
                    }
                }
            } catch (e: Exception) {
                logger.error("Error cleaning up files: ${e.message}", e)
            }
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

}
