package dev.storozhenko.music.run

import dev.storozhenko.music.OdesilResponse
import dev.storozhenko.music.getLogger
import dev.storozhenko.music.services.ErrorNotificationService
import dev.storozhenko.music.services.OdesilService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.Closeable
import kotlin.time.Duration.Companion.minutes
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction
import org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions
import org.telegram.telegrambots.meta.api.objects.MessageEntity
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.generics.TelegramClient
import org.telegram.telegrambots.meta.api.methods.send.SendVideo
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.media.InputMediaAudio
import org.telegram.telegrambots.meta.api.objects.media.InputMediaVideo
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageMedia
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import org.jsoup.Jsoup
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL

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
    
    private fun getIpVersionParam(url: String): String? {
        if (ipv6UrlContains.isNullOrEmpty()) {
            return null
        }

        val urlContainsList = ipv6UrlContains.split(",").map { it.trim() }
        return if (urlContainsList.any { url.contains(it, ignoreCase = true) }) {
            "-6"
        } else {
            "-4"
        }
    }

    private fun getProxyParams(url: String): List<String> {
        if (ytdlProxy.isNullOrEmpty() || ytdlProxyUrlContains.isNullOrEmpty()) {
            return emptyList()
        }
        val urlContainsList = ytdlProxyUrlContains.split(",").map { it.trim() }
        return if (urlContainsList.any { url.contains(it, ignoreCase = true) }) {
            listOf("--proxy", ytdlProxy)
        } else {
            emptyList()
        }
    }

    private enum class Quality(val label: String, val formatSelector: String) {
        LOW("≤480p", "bestvideo[height<=480][ext=mp4][vcodec^=avc1]+bestaudio[ext=m4a]/best[height<=480][ext=mp4]/best[ext=mp4]/best"),
        MEDIUM("≤720p", "bestvideo[height<=720][ext=mp4][vcodec^=avc1]+bestaudio[ext=m4a]/best[height<=720][ext=mp4]/best[ext=mp4]/best"),
        HIGH("best", "bestvideo[ext=mp4][vcodec^=avc1]+bestaudio[ext=m4a]/best[ext=mp4]/best")
    }

    private data class RequestOptions(val quality: Quality, val forceAudio: Boolean)

    private fun parseRequestOptions(text: String): RequestOptions {
        val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return RequestOptions(Quality.MEDIUM, false)
        val lastIdx = tokens.size - 1
        var quality: Quality? = null
        var forceAudio = false
        tokens.forEachIndexed { i, raw ->
            val tok = raw.lowercase()
            val atBoundary = i == 0 || i == lastIdx
            if (quality == null) {
                quality = when {
                    tok == "low" -> Quality.LOW
                    tok == "medium" || tok == "med" || tok == "mid" -> Quality.MEDIUM
                    tok == "high" || tok == "hi" -> Quality.HIGH
                    atBoundary && tok == "l" -> Quality.LOW
                    atBoundary && tok == "m" -> Quality.MEDIUM
                    atBoundary && tok == "h" -> Quality.HIGH
                    else -> null
                }
            }
            if (!forceAudio) {
                forceAudio = tok == "audio" || tok == "au" || tok == "sound" || tok == "snd" ||
                    (atBoundary && (tok == "a" || tok == "s"))
            }
        }
        return RequestOptions(quality ?: Quality.MEDIUM, forceAudio)
    }

    private inner class ChatActionPulser(private val chatId: Long, initialAction: String) : Closeable {
        @Volatile private var action: String = initialAction
        private val job: Job = coroutine.launch {
            while (isActive) {
                try {
                    telegramClient.executeAsync(SendChatAction(chatId.toString(), action)).await()
                } catch (e: Exception) {
                    logger.info("Failed to send chat action: ${e.message}")
                }
                delay(5_000)
            }
        }
        fun set(newAction: String) {
            action = newAction
        }
        override fun close() {
            job.cancel()
        }
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

        val pulser = ChatActionPulser(chatId, "typing")
        try {
        val odesilDetections = urlEntities.mapNotNull(odesilService::detect)
        val links = odesilDetections.map { mapOdesilResponse(it.odesilResponse) }
        val validLinks = urlEntities.filter { entity -> isValidDownloadUrl(entity.text) }

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
            val videoTitle = getVideoTitle(validLink.text)
            linksMessage = "$videoTitle\n<a href=\"${validLink.text}\">${validLink.text}</a>"
        }

        val youtubeFromMessage = extractFirstUrlByText(linksMessage, "Youtube")
        val ytSearchUrl: String? = if (youtubeFromMessage == null && odesilDetections.isNotEmpty()) {
            val firstDetection = odesilDetections.first().odesilResponse
            val data = firstDetection.entitiesByUniqueId[firstDetection.entityUniqueId]
            val query = listOfNotNull(data?.artistName, data?.title)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            if (query.isNotBlank()) ytSearchFirst(query) else null
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

    private suspend fun downloadAndSendVideo(url: String, message:String, intermediateMessageId: Int, rmid: Int, chatId: Long, quality: Quality, forceAudio: Boolean, pulser: ChatActionPulser): Boolean {
        var downloadedFile: File? = null
        var thumbnailFile: File? = null
        var chunkFiles: List<File> = emptyList()
        var telegramAudioFile: File? = null

        try {
            pulser.set("typing")
            editMessageText(chatId, intermediateMessageId, "$message\nDownloading ${quality.label}... Use <code>low</code>/<code>medium</code>/<code>high</code> or <code>audio</code> after link to change.")
            val ipVersionParam = getIpVersionParam(url)
            val downloadParams = mutableListOf<String>().apply {
                ipVersionParam?.let { add(it) }
                addAll(getProxyParams(url))
                addAll(listOf(
                    "--cookies", "/cookies.txt",
                    "-f", quality.formatSelector, "--merge-output-format", "mp4",
                    "-I", "0",
                    "--playlist-items", "1",
                    "--write-thumbnail",
                    "--embed-thumbnail",
                    "--convert-thumbnails", "jpg",
                    "--js-runtimes", "deno:/usr/bin/deno",
                    "--remote-components", "ejs:github",
                    "--retries", "5",
                    "--fragment-retries", "5",
                    "--socket-timeout", "30"
                ))
            }
            downloadedFile = download(
                "${UUID.randomUUID()}.%(ext)s",
                url,
                *downloadParams.toTypedArray()
            ) ?: run {
                editMessageText(chatId, intermediateMessageId, "$message\n❌ Failed to download file: empty result")
                return false
            }
            
            // Validate downloaded file
            val (isVideoValid, videoValidationMessage) = validateVideoFile(downloadedFile)
            if (!isVideoValid) {
                logger.error("Video validation failed: $videoValidationMessage")
                editMessageText(chatId, intermediateMessageId, "$message\n❌ Video validation failed: $videoValidationMessage")
                return false
            }
            
            // Log file size
            val fileSizeInBytes = downloadedFile.length()
            val fileSizeInMB = fileSizeInBytes / (1024.0 * 1024.0)
            logger.info("Downloaded file size: ${String.format("%.2f", fileSizeInMB)} MB (${fileSizeInBytes} bytes)")
            
            thumbnailFile = downloadedFile.changeExtension("jpg")
            if (!thumbnailFile.exists()) {
                logger.warn("Thumbnail file does not exist: ${thumbnailFile.absolutePath}")
                // Create a placeholder thumbnail or continue without it
                thumbnailFile = null
            } else {
                // Validate thumbnail file
                val (isThumbnailValid, thumbnailValidationMessage) = validateThumbnailFile(thumbnailFile)
                if (!isThumbnailValid) {
                    logger.warn("Thumbnail validation failed: $thumbnailValidationMessage. Continuing without thumbnail.")
                    thumbnailFile = null
                } else {
                    delayedDelete(thumbnailFile)
                }
            }
            
            editMessageText(chatId, intermediateMessageId, "$message\nGetting dimensions...")
            val dimensions = getVideoDimensions(downloadedFile)
            logger.info("Video dimensions: $dimensions")
            
            val (videoWidth, videoHeight, videoDuration) = try {
                dimensions.split(",").map { it.toDouble().toInt() }
            } catch (e: Exception) {
                logger.error("Failed to parse video dimensions: $dimensions", e)
                editMessageText(chatId, intermediateMessageId, "$message\n❌ Failed to get video dimensions")
                return false
            }
            
            val (artist, title) = message.lineSequence().first().split2ByDash(true)
            var sendVideo = true
            val idHasMusic = chatsAndPlaylistNames[chatId]?.contains("music", ignoreCase = true) == true
            
            // Split video into chunks if it's larger than the configured chunk size
            if (fileSizeInMB > chunkSizeMB) {
                editMessageText(chatId, intermediateMessageId, "$message\nSplitting video into chunks...")
                val outputPrefix = "${downloadedFile.parentFile.absolutePath}/${downloadedFile.nameWithoutExtension}_chunk.mp4"
                chunkFiles = splitVideoIntoChunks(downloadedFile, outputPrefix)
                
                if (chunkFiles.isEmpty()) {
                    logger.error("No chunk files were created")
                    editMessageText(chatId, intermediateMessageId, "$message\n❌ Failed to split video into chunks")
                    return false
                }
                
                logger.info("Video split into ${chunkFiles.size} chunks")
            } else {
                chunkFiles = listOf(downloadedFile)
            }
            
            // Decide audio vs video for music chats / forceAudio (non-music chats always get video)
            if (idHasMusic || forceAudio) {
                if (forceAudio) {
                    logger.info("forceAudio set, skipping video analysis")
                    sendVideo = false
                } else {
                    pulser.set("typing")
                    editMessageText(chatId, intermediateMessageId, "$message\nAnalyzing Video...")
                    val windows: List<Pair<Double, Double>> = when {
                        videoDuration <= 30 -> listOf(0.0 to videoDuration.toDouble())
                        videoDuration > 60 -> {
                            val start = 30.0
                            val span = videoDuration - 30.0
                            listOf(0.10, 0.30, 0.50, 0.70, 0.90).map { (start + span * it) to 5.0 }
                        }
                        else -> listOf(0.10, 0.30, 0.50, 0.70, 0.90).map { (videoDuration * it) to 5.0 }
                    }
                    logger.info("${downloadedFile.absolutePath} duration=${videoDuration}s, analysis windows=$windows")
                    val downloadedFileLocal = downloadedFile
                    val (totalSceneCount, totalFreezeDuration) = coroutineScope {
                        val sceneJobs = windows.map { (s, d) -> async { getSceneChangeCount(downloadedFileLocal, s, d) } }
                        val freezeJobs = windows.map { (s, d) -> async { getTotalFreezeDuration(downloadedFileLocal, s, d) } }
                        sceneJobs.awaitAll().sum() to freezeJobs.awaitAll().sum()
                    }
                    sendVideo = if (totalSceneCount >= 2) {
                        logger.info("${downloadedFile.absolutePath} totalSceneCount=$totalSceneCount >= 2, treating as moving")
                        true
                    } else {
                        val totalAnalyzed = windows.sumOf { (_, d) -> d }
                        val ratio = totalFreezeDuration / totalAnalyzed
                        logger.info("${downloadedFile.absolutePath} totalSceneCount=$totalSceneCount, totalFreezeDuration / totalAnalyzed = $totalFreezeDuration / $totalAnalyzed = $ratio")
                        ratio < 0.5
                    }
                }
            }

            // Send audio if decision was audio (not video)
            if (!sendVideo) {
                logger.info("Sending Audio...")
                editMessageText(chatId, intermediateMessageId, "$message\nSending Audio...")
                pulser.set("upload_voice")

                try {
                    // For audio, we'll use the original file (first chunk) but convert it to Telegram-compatible format
                    val sourceAudioFile = chunkFiles.first()

                    // Extract audio for Telegram
                    editMessageText(chatId, intermediateMessageId, "$message\nExtracting audio...")
                    try {
                        telegramAudioFile = convertToTelegramAudio(sourceAudioFile)
                    } catch (e: Exception) {
                        logger.error("Failed to extract audio: ${e.message}", e)
                        editMessageText(chatId, intermediateMessageId, "$message\n❌ Failed to extract audio: ${e.message}")
                        return false
                    }

                    val outerArtist = artist
                    val outerTitle = title
                    val audioMedia = InputMediaAudio(telegramAudioFile, "audio").also {
                        it.caption = message.removeFirstLine()
                        it.parseMode = "HTML"
                        it.duration = videoDuration
                        it.performer = outerArtist
                        it.title = outerTitle
                        thumbnailFile?.let { tf -> it.thumbnail = InputFile(tf) }
                    }
                    val edit = EditMessageMedia.builder()
                        .chatId(chatId.toString())
                        .messageId(intermediateMessageId)
                        .media(audioMedia)
                        .build()
                    telegramClient.executeAsync(edit).await()
                    logger.info("Audio sent (status morphed in place)")
                } catch (e: TelegramApiException) {
                    logger.error("Failed to send audio: ${e.message}", e)
                    editMessageText(chatId, intermediateMessageId, "$message\n❌ Failed to send audio: ${e.message}")
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
                    
                    editMessageText(chatId, intermediateMessageId, statusMessage)
                    logger.info("Sending Video chunk $chunkNumber of $totalChunks...")
                    pulser.set("upload_video")
                    
                    try {
                        // Get dimensions for this chunk
                        val chunkDimensions = getVideoDimensions(chunkFile)
                        val (chunkWidth, chunkHeight, chunkDuration) = try {
                            chunkDimensions.split(",").map { it.toDouble().toInt() }
                        } catch (e: Exception) {
                            logger.error("Failed to parse video dimensions for chunk: $chunkDimensions", e)
                            editMessageText(chatId, intermediateMessageId, "$message\n❌ Failed to get video dimensions for chunk $chunkNumber")
                            return false
                        }
                        
                        // Create caption for this chunk
                        val chunkCaption = if (totalChunks > 1) {
                            "$message\n\nPart $chunkNumber of $totalChunks"
                        } else {
                            message
                        }
                        
                        if (chunkNumber == 1) {
                            // Replace status text with the first chunk in place — no flicker, one fewer round-trip.
                            val videoMedia = InputMediaVideo(chunkFile, "video").also {
                                it.caption = chunkCaption
                                it.parseMode = "HTML"
                                it.width = chunkWidth
                                it.height = chunkHeight
                                it.duration = chunkDuration
                                it.supportsStreaming = true
                                it.showCaptionAboveMedia = true
                                thumbnailFile?.let { tf ->
                                    it.thumbnail = InputFile(tf)
                                    it.cover = InputFile(tf)
                                }
                            }
                            val edit = EditMessageMedia.builder()
                                .chatId(chatId.toString())
                                .messageId(intermediateMessageId)
                                .media(videoMedia)
                                .build()
                            telegramClient.executeAsync(edit).await()
                        } else {
                            val videoBuilder = SendVideo.builder()
                                .video(InputFile(chunkFile))
                                .width(chunkWidth)
                                .height(chunkHeight)
                                .duration(chunkDuration)
                                .caption(chunkCaption)
                                .parseMode("HTML")
                                .showCaptionAboveMedia(true)
                                .supportsStreaming(true)
                                .disableNotification(true)
                                .chatId(chatId)
                                .replyToMessageId(rmid)
                            thumbnailFile?.let {
                                videoBuilder.thumbnail(InputFile(it))
                                videoBuilder.cover(InputFile(it))
                            }
                            telegramClient.executeAsync(videoBuilder.build()).await()
                        }
                        logger.info("Video chunk $chunkNumber sent successfully")

                        // Add a small delay between sending chunks to avoid rate limiting
                        if (chunkNumber < totalChunks) {
                            delay(1000)
                        }
                    } catch (e: TelegramApiException) {
                        logger.error("Failed to send video chunk $chunkNumber: ${e.message}", e)
                        editMessageText(chatId, intermediateMessageId, "$message\n❌ Failed to send video chunk $chunkNumber: ${e.message}")
                        return false
                    }
                }
            }

            return true
        } catch (e: Exception) {
            logger.error("Error in downloadAndSendVideo: ${e.message}", e)
            editMessageText(chatId, intermediateMessageId, "$message\n❌ Failed to process video: ${e.message}")
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

    private fun delayedDelete(fileToDelete: File) {
        fileToDelete.deleteOnExit()
        fileDeleteScope.launch {
            runCatching {
                delay(55.minutes)
                fileToDelete.delete()
                logger.info("${fileToDelete.absolutePath} is deleted")
            }.onFailure {
                logger.error("Could not delete ${fileToDelete.absolutePath}")
                errorNotificationService?.sendErrorNotification(it, "File Deletion: ${fileToDelete.absolutePath}")
            }
        }
    }

    private suspend fun download(filename: String, url: String, vararg params: String): File? = runInterruptible(virtualDispatcher) {
        logger.info("Running yt-dlp for $url...")
        val folderName = UUID.randomUUID().toString()
        val folder = File("/tmp", folderName).apply { mkdir() }
        val command = listOf(ytdlLocation, url, "-o", "${folder.absolutePath}/$filename") + params.toList()
        logger.info(command.shellJoin())
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            reader.lineSequence().forEach { logger.info(it) }
        }
        process.waitFor()
        logger.info("Finished running yt-dlp for $url")
        val files = folder.listFiles()
        if (files == null || files.isEmpty()) {
            logger.error("Can't download file for url $url")
            return@runInterruptible null
        }
        var downloadedFile = files.first()
        downloadedFile = downloadedFile.changeExtension("mp4")
        delayedDelete(downloadedFile)
        downloadedFile
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

    private suspend fun getVideoDimensions(videoFile: File): String = runInterruptible(virtualDispatcher) {
        logger.info("Running ffprobe for $videoFile...")
        val command = listOf("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height:format=duration", "-of", "csv=p=0:s=,", videoFile.absolutePath)

        val process = ProcessBuilder(command)
            .redirectErrorStream(true) // Merge error stream with output
            .start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val lines = reader.readLines()
        logger.info("Got ${lines.size} lines from ffprobe: $lines")
        process.waitFor()

        // Combine first two lines into one comma-separated line
        if (lines.size >= 2) {
            "${lines[0]},${lines[1]}"
        } else {
            lines.firstOrNull() ?: ""
        }
    }

    private suspend fun getTotalFreezeDuration(videoFile: File, startTime: Double, duration: Double): Double = runInterruptible(virtualDispatcher) {
        logger.info("Running ffmpeg for $videoFile to find freezes...")
        val command = listOf(
            "ffmpeg",
            "-v", "error",
            "-ss", startTime.toString(),
            "-t", duration.toString(),
            "-i", videoFile.absolutePath,
            "-vf", "freezedetect=n=-30dB:d=1,metadata=mode=print:file=-",
            "-map", "0:v:0",
            "-f", "null",
            "-"
        )
        logger.info(command.shellJoin())

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        var totalFreezeDuration = 0.0
        var openFreezeStart: Double? = null
        val freezeStartPattern = Regex("lavfi\\.freezedetect\\.freeze_start=(\\d+\\.?\\d*)")
        val freezeDurationPattern = Regex("lavfi\\.freezedetect\\.freeze_duration=(\\d+\\.?\\d*)")
        val freezeEndPattern = Regex("lavfi\\.freezedetect\\.freeze_end=(\\d+\\.?\\d*)")

        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            reader.lineSequence().forEach { line ->
                freezeStartPattern.find(line)?.let { openFreezeStart = it.groupValues[1].toDouble() }
                freezeDurationPattern.find(line)?.let {
                    totalFreezeDuration += it.groupValues[1].toDouble()
                }
                freezeEndPattern.find(line)?.let { openFreezeStart = null }
            }
        }

        process.waitFor()
        // If a freeze started but never ended within the clip, count from start to clip end.
        openFreezeStart?.let { start ->
            val ongoing = (duration - start).coerceAtLeast(0.0)
            totalFreezeDuration += ongoing
            logger.info("Open freeze from $start to clip end (+$ongoing)")
        }
        logger.info("Total Freeze Duration: $totalFreezeDuration")
        totalFreezeDuration
    }

    private suspend fun getSceneChangeCount(videoFile: File, startTime: Double, duration: Double): Int = runInterruptible(virtualDispatcher) {
        logger.info("Running ffmpeg for $videoFile to count scene changes...")
        val command = listOf(
            "ffmpeg",
            "-v", "error",
            "-ss", startTime.toString(),
            "-t", duration.toString(),
            "-i", videoFile.absolutePath,
            "-vf", "select='gt(scene,0.05)',metadata=print:file=-",
            "-map", "0:v:0",
            "-an",
            "-f", "null",
            "-"
        )
        logger.info(command.shellJoin())

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        var sceneCount = 0
        val sceneScorePattern = Regex("lavfi\\.scene_score=")

        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            reader.lineSequence().forEach { line ->
                if (sceneScorePattern.containsMatchIn(line)) sceneCount++
            }
        }

        process.waitFor()
        logger.info("Scene change count: $sceneCount")
        sceneCount
    }

    private suspend fun ytSearchFirst(query: String): String? = runInterruptible(virtualDispatcher) {
        logger.info("Running yt-dlp ytsearch for: $query")
        val command = listOf(
            "yt-dlp",
            "--cookies", "/cookies.txt",
            "--print", "%(webpage_url)s",
            "--skip-download",
            "--js-runtimes", "deno:/usr/bin/deno",
            "--remote-components", "ejs:github",
            "--retries", "5",
            "--fragment-retries", "5",
            "--socket-timeout", "30",
            "ytsearch1:$query"
        )
        logger.info(command.shellJoin())
        try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            logger.info("ytsearch output: $output")
            output.lineSequence().firstOrNull { it.startsWith("http") }
        } catch (e: Exception) {
            logger.error("ytsearch failed for $query", e)
            null
        }
    }

    private suspend fun getVideoTitle(url: String): String = runInterruptible(virtualDispatcher) {
        logger.info("Running yt-dlp to get title and duration for $url...")
        val ipVersionParam = getIpVersionParam(url)
        val command = mutableListOf<String>().apply {
            addAll(listOf(
                "yt-dlp",
                "--cookies", "/cookies.txt",
                "--print", "%(title)s [%(duration)s]",
                "--js-runtimes", "deno:/usr/bin/deno",
                "--remote-components", "ejs:github",
                "--retries", "5",
                "--fragment-retries", "5",
                "--socket-timeout", "30"
            ))
            ipVersionParam?.let { add(it) }
            addAll(getProxyParams(url))
            add(url)
        }
        logger.info(command.shellJoin())

        val process = ProcessBuilder(command)
            .redirectErrorStream(true) // Merge error stream with output
            .start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = reader.readLine() // Read first line of output
        logger.info("Got $output from yt-dlp")
        process.waitFor()
        if (output.contains(": No video formats found!")) {
            return@runInterruptible ""
        }

        // Format the duration from seconds to MM:SS
        output.replace(Regex("\\[(\\d+)\\]")) { matchResult ->
            val seconds = matchResult.groupValues[1].toInt()
            val minutes = seconds / 60
            val remainingSeconds = seconds % 60
            " [%02d:%02d]".format(minutes, remainingSeconds)
        }
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
            allowedPaths = setOf("/watch", "/shorts/", "/embed/", "/v/", "/"),
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
            logger.info("Validating URL: $urlString")
            val url = URL(urlString)
            val host = url.host.lowercase()
            val path = url.path.lowercase()
            logger.info("Parsed URL - host: $host, path: $path")

            // Find the first config whose host matches (exact or sub-domain)
            val cfg = VALID_URL_CONFIG.values.firstOrNull { cfg ->
                val hostMatches = cfg.hosts.any { exact ->
                    val matches = host == exact || host.endsWith(".$exact")
                    if (matches) {
                        logger.info("Host '$host' matches config for '$exact'")
                    }
                    matches
                }
                hostMatches
            } ?: run {
                logger.info("No config found for host: $host")
                return false
            }

            logger.info("Using config with hosts: ${cfg.hosts}, allowedPaths: ${cfg.allowedPaths}, blockedPathPrefixes: ${cfg.blockedPathPrefixes}, extraPathTokens: ${cfg.extraPathTokens}")

            // 1. Reject blocked prefixes
            val blockedPrefix = cfg.blockedPathPrefixes.find { path.startsWith(it) }
            if (blockedPrefix != null) {
                logger.info("URL rejected: path '$path' starts with blocked prefix '$blockedPrefix'")
                return false
            }

            // 2. Accept explicit allowed prefixes
            val allowedPrefix = cfg.allowedPaths.find { path.startsWith(it) }
            if (allowedPrefix != null) {
                logger.info("URL accepted: path '$path' starts with allowed prefix '$allowedPrefix'")
                return true
            }

            // 3. Accept extra in-path tokens (VK-style "/video-123", etc.)
            val extraToken = cfg.extraPathTokens.find { path.contains(it) }
            if (extraToken != null) {
                logger.info("URL accepted: path '$path' contains extra token '$extraToken'")
                return true
            }

            logger.info("URL rejected: path '$path' doesn't match any allowed patterns")
            false
        }.getOrElse { exception ->
            logger.error("Error validating URL '$urlString': ${exception.message}", exception)
            false
        }

    /**
     * Splits a video file into chunks using mkvmerge
     * @param videoFile The video file to split
     * @param outputPrefix The prefix for output chunk files
     * @param chunkSizeMB The maximum size of each chunk in MB
     * @return List of chunk files
     */
    private suspend fun splitVideoIntoChunks(videoFile: File, outputPrefix: String, chunkSizeMB: Int = this.chunkSizeMB): List<File> = runInterruptible(virtualDispatcher) {
        logger.info("Splitting video file ${videoFile.absolutePath} into chunks of ${chunkSizeMB}MB...")
        
        val command = listOf(
            "mkvmerge",
            "--quiet",
            "-o", outputPrefix,
            "--split", "${chunkSizeMB}M",
            videoFile.absolutePath
        )

        logger.info("Executing command: ${command.shellJoin()}")

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        // --quiet suppresses progress/info; anything left is a warning or error.
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            reader.lineSequence().forEach { line ->
                if (line.isNotBlank()) logger.warn("mkvmerge: $line")
            }
        }
        
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            logger.error("mkvmerge failed with exit code $exitCode")
            throw RuntimeException("Failed to split video file using mkvmerge")
        }
        
        // Find all chunk files
        val outputDir = videoFile.parentFile
        val chunkPrefix = File(outputPrefix).nameWithoutExtension
        logger.info("Looking for chunk files with prefix: $chunkPrefix in directory: ${outputDir.absolutePath}")
        val chunkFiles = outputDir.listFiles { _, name -> name.startsWith(chunkPrefix) }?.toList() ?: emptyList()
        
        logger.info("Created ${chunkFiles.size} chunk files")
        chunkFiles.forEach { file -> logger.info("Chunk file: ${file.absolutePath}") }

        chunkFiles.sortedBy { it.name }
    }

    /**
     * Extracts audio from a file for Telegram using ffmpeg
     * @param inputFile The input video/audio file
     * @return The extracted audio file
     */
    private suspend fun convertToTelegramAudio(inputFile: File): File = runInterruptible(virtualDispatcher) {
        logger.info("Extracting audio from ${inputFile.absolutePath} for Telegram...")
        
        // Create output file with .m4a extension in the same directory (Telegram supports m4a)
        val outputFile = File(inputFile.parentFile, "${UUID.randomUUID()}_telegram_audio.m4a")
        
        // Simple command to extract audio without conversion
        val command = listOf(
            "ffmpeg",
            "-hide_banner", "-nostats", "-loglevel", "error",
            "-i", inputFile.absolutePath,
            "-vn",                   // Extract audio only
            "-acodec", "copy",      // Copy audio without re-encoding
            outputFile.absolutePath
        )

        logger.info("Executing ffmpeg command: ${command.shellJoin()}")

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        // Read and log output (only surfaces real errors thanks to -loglevel error)
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            reader.lineSequence().forEach { line ->
                if (line.isNotBlank()) logger.warn("ffmpeg: $line")
            }
        }
        
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            logger.error("ffmpeg failed with exit code $exitCode")
            throw RuntimeException("Failed to extract audio using ffmpeg")
        }
        
        logger.info("Successfully extracted audio to ${outputFile.absolutePath}")

        // Register the file for delayed deletion
        delayedDelete(outputFile)

        outputFile
    }


    private suspend fun editMessageText(chatId: Long, messageId: Int, newText: String) {
        val editMessage = EditMessageText.builder()
            .chatId(chatId.toString())
            .messageId(messageId)
            .text(newText)
            .parseMode("HTML")
            .linkPreviewOptions(LinkPreviewOptions.builder().isDisabled(true).build())
            .build()

        try {
            telegramClient.executeAsync(editMessage).await()
        } catch (e: TelegramApiException) {
            // Handle error (message might not exist or bot doesn't have permission)
            logger.info("Failed to edit message: ${e.message}")
        }
    }
}

private fun String.shellQuote(): String {
    if (this.isEmpty()) return "''"
    if (this.all { it.isLetterOrDigit() || it in "_./@:=+-,%" }) return this
    return "'" + this.replace("'", "'\\''") + "'"
}

private fun List<String>.shellJoin(): String = joinToString(" ") { it.shellQuote() }

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

/**
 * Validates if a video file is compatible with Telegram's requirements
 * @param videoFile The video file to validate
 * @return Pair of Boolean (isValid) and String (errorMessage)
 */
private fun validateVideoFile(videoFile: File): Pair<Boolean, String> {
    if (!videoFile.exists() || !videoFile.isFile) {
        return false to "File does not exist or is not a regular file"
    }
    
    if (videoFile.length() == 0L) {
        return false to "File is empty"
    }
    
    // // Check file size (Telegram has a 2000MB limit for regular uploads)
    // val fileSizeInBytes = videoFile.length()
    // if (fileSizeInBytes > 2000 * 1024 * 1024) {
    //     return false to "File size exceeds Telegram's 2000MB limit (${fileSizeInBytes / (1024.0 * 1024.0)} MB)"
    // }
    
    // Check file extension
    val fileName = videoFile.name.lowercase()
    if (!fileName.endsWith(".mp4") && !fileName.endsWith(".mov") && !fileName.endsWith(".avi")) {
        return false to "Unsupported file format: ${videoFile.extension}. Telegram supports MP4, MOV, and AVI"
    }
    
    return true to "File is valid"
}

/**
 * Validates if a thumbnail file is compatible with Telegram's requirements
 * @param thumbnailFile The thumbnail file to validate
 * @return Pair of Boolean (isValid) and String (errorMessage)
 */
private fun validateThumbnailFile(thumbnailFile: File?): Pair<Boolean, String> {
    if (thumbnailFile == null) {
        return true to "No thumbnail provided (optional)"
    }
    
    if (!thumbnailFile.exists() || !thumbnailFile.isFile) {
        return false to "Thumbnail file does not exist or is not a regular file"
    }
    
    if (thumbnailFile.length() == 0L) {
        return false to "Thumbnail file is empty"
    }
    
    // Check thumbnail size (Telegram recommends thumbnails under 200KB)
    val thumbnailSizeInBytes = thumbnailFile.length()
    if (thumbnailSizeInBytes > 200 * 1024) {
        return false to "Thumbnail size exceeds recommended 200KB limit (${thumbnailSizeInBytes / 1024.0} KB)"
    }
    
    // Check thumbnail format
    val fileName = thumbnailFile.name.lowercase()
    if (!fileName.endsWith(".jpg") && !fileName.endsWith(".jpeg") && !fileName.endsWith(".png")) {
        return false to "Unsupported thumbnail format: ${thumbnailFile.extension}. Telegram supports JPG and PNG"
    }
    
    return true to "Thumbnail is valid"
}
