package dev.storozhenko.music.run

import dev.storozhenko.music.Quality
import dev.storozhenko.music.eagerlyDelete
import dev.storozhenko.music.getLogger
import dev.storozhenko.music.removeFirstLine
import dev.storozhenko.music.split2ByDash
import dev.storozhenko.music.validateVideoFile
import dev.storozhenko.music.services.DownloadService
import dev.storozhenko.music.services.MediaProbeService
import dev.storozhenko.music.services.MediaProcessingService
import dev.storozhenko.music.services.TelegramSender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.exceptions.TelegramApiException
import java.io.File
import java.util.UUID

data class DownloadRequest(
    val url: String,
    val message: String,
    val statusMessageId: Int,
    val replyToMessageId: Int,
    val chatId: Long,
    val quality: Quality,
    val forceAudio: Boolean,
    val isMusicChat: Boolean,
    /** Source URL is a known music host we *can* download directly (music.youtube.com), so its
     *  content is ambiguous — art track vs real music video — and earns the probe. */
    val isMusicSource: Boolean = false,
    /** Explicit `video` keyword: suppresses auto-detection entirely. */
    val forceVideo: Boolean = false,
    val prefetchedUrl: String? = null,
    val prefetchedDownload: Deferred<File?>? = null,
)

class DownloadPipeline(
    private val downloader: DownloadService,
    private val probe: MediaProbeService,
    private val processor: MediaProcessingService,
    private val sender: TelegramSender,
    private val chunkSizeMB: Int,
) {
    private val logger = getLogger()

    suspend fun run(
        request: DownloadRequest,
        pulser: TelegramSender.ChatActionPulser,
        cancelKb: InlineKeyboardMarkup?,
    ): Boolean {
        val reporter = StatusReporter(sender, request.chatId, request.statusMessageId, request.message, cancelKb)
        return if (request.forceAudio) downloadAndSendAudioOnly(request, reporter, pulser)
        else downloadAndSendVideo(request, reporter, pulser, cancelKb)
    }

    private suspend fun downloadAndSendAudioOnly(
        request: DownloadRequest,
        reporter: StatusReporter,
        pulser: TelegramSender.ChatActionPulser,
    ): Boolean {
        val url = request.url
        val message = request.message
        val chatId = request.chatId
        val intermediateMessageId = request.statusMessageId
        val prefetchedUrl = request.prefetchedUrl
        val prefetchedDownload = request.prefetchedDownload
        var downloadedFile: File? = null
        var thumbnailFile: File? = null
        try {
            pulser.set("typing")
            reporter.status("Downloading audio...")
            downloadedFile = awaitOrDownload(prefetchedUrl, prefetchedDownload, url, downloader.audioFlags(url))
                ?: run { reporter.fail("Failed to download audio: empty result"); return false }

            thumbnailFile = downloader.resolveSiblingThumbnail(downloadedFile)
                ?.let { runCatching { processor.convertToSquareThumbnail(it) }.getOrNull() ?: it }
            val duration = probe.getMediaDuration(downloadedFile)

            val (artist, title) = message.lineSequence().first().split2ByDash(true)
            reporter.status("Sending Audio...")
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Error in downloadAndSendAudioOnly: ${e.message}", e)
            reporter.fail("Failed to send audio: ${e.message}")
            return false
        } finally {
            eagerlyDelete(logger, downloadedFile, thumbnailFile)
        }
    }

    private suspend fun downloadAndSendVideo(
        request: DownloadRequest,
        reporter: StatusReporter,
        pulser: TelegramSender.ChatActionPulser,
        cancelKb: InlineKeyboardMarkup?,
    ): Boolean {
        val url = request.url
        val message = request.message
        val intermediateMessageId = request.statusMessageId
        val rmid = request.replyToMessageId
        val chatId = request.chatId
        val quality = request.quality
        val prefetchedUrl = request.prefetchedUrl
        val prefetchedDownload = request.prefetchedDownload
        var downloadedFile: File? = null
        var thumbnailFile: File? = null
        var chunkFiles: List<File> = emptyList()
        var telegramAudioFile: File? = null

        try {
            pulser.set("typing")
            val downloadingMsg = if (quality == Quality.HIGH) "Downloading..." else "Downloading <code>${quality.label}</code>..."
            reporter.status("$downloadingMsg Use <code>low</code>/<code>medium</code>/<code>high</code> or <code>audio</code> after link to change quality.")
            downloadedFile = awaitOrDownload(prefetchedUrl, prefetchedDownload, url, downloader.videoFlags(url, quality.formatSelector))
                ?: run { reporter.fail("Failed to download file: empty result"); return false }

            val (isVideoValid, videoValidationMessage) = validateVideoFile(downloadedFile)
            if (!isVideoValid) {
                logger.error("Video validation failed: $videoValidationMessage")
                reporter.fail("Video validation failed: $videoValidationMessage")
                return false
            }

            val fileSizeInBytes = downloadedFile.length()
            val fileSizeInMB = fileSizeInBytes / (1024.0 * 1024.0)
            logger.info("Downloaded file size: ${String.format("%.2f", fileSizeInMB)} MB (${fileSizeInBytes} bytes)")

            thumbnailFile = downloader.resolveSiblingThumbnail(downloadedFile)

            reporter.status("Getting dimensions...")
            val videoDims = probe.getVideoDimensions(downloadedFile) ?: run {
                reporter.fail("Failed to get video dimensions"); return false
            }
            val videoDuration = videoDims.duration

            val (artist, title) = message.lineSequence().first().split2ByDash(true)
            var sendVideo = true
            // A song can hide behind a plain youtube.com link: a still image with audio on an
            // ordinary channel, 16:9 and so indistinguishable by host or aspect ratio. YouTube's own
            // category is the cheap signal, and it leaves static-camera clips in cinema/photo chats
            // on the video path because their category isn't Music.
            val idHasMusic = !request.forceVideo && (
                request.isMusicChat || request.isMusicSource ||
                    downloader.readCategories(downloadedFile).any { it.equals("Music", ignoreCase = true) }
                )

            if (fileSizeInMB > chunkSizeMB) {
                reporter.status("Splitting video into chunks...")
                val outputPrefix = "${downloadedFile.parentFile.absolutePath}/${downloadedFile.nameWithoutExtension}_chunk.mp4"
                chunkFiles = processor.splitVideoIntoChunks(downloadedFile, outputPrefix)

                if (chunkFiles.isEmpty()) {
                    logger.error("No chunk files were created")
                    reporter.fail("Failed to split video into chunks")
                    return false
                }

                logger.info("Video split into ${chunkFiles.size} chunks")
            } else {
                chunkFiles = listOf(downloadedFile)
            }

            if (idHasMusic) {
                pulser.set("typing")
                reporter.status("Analyzing Video...")
                sendVideo = probe.decideSendAsVideo(downloadedFile, videoDuration)
            }

            if (!sendVideo) {
                logger.info("Sending Audio...")
                reporter.status("Sending Audio...")
                pulser.set("upload_voice")

                try {
                    val sourceAudioFile = chunkFiles.first()

                    reporter.status("Extracting audio...")
                    try {
                        telegramAudioFile = processor.convertToTelegramAudio(sourceAudioFile)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error("Failed to extract audio: ${e.message}", e)
                        reporter.fail("Failed to extract audio: ${e.message}")
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
                    logger.info("Audio sent (status morphed in place)")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TelegramApiException) {
                    logger.error("Failed to send audio: ${e.message}", e)
                    reporter.fail("Failed to send audio: ${e.message}")
                    return false
                }
            }

            if (sendVideo) {
                if (thumbnailFile != null && videoDims.height > videoDims.width) {
                    thumbnailFile = runCatching { processor.convertToPortraitThumbnail(thumbnailFile!!) }.getOrNull() ?: thumbnailFile
                }
                val probedChunks = chunkFiles.map { f ->
                    val dims = probe.getVideoDimensions(f) ?: run {
                        reporter.fail("Failed to get video dimensions for a chunk")
                        return false
                    }
                    f to dims
                }
                if (!sender.sendVideoChunks(chatId, intermediateMessageId, rmid, probedChunks, message, thumbnailFile, pulser, cancelKb)) return false
            }

            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Error in downloadAndSendVideo: ${e.message}", e)
            reporter.fail("Failed to process video: ${e.message}")
            return false
        } finally {
            val infoJson = downloadedFile?.let { downloader.resolveSiblingInfoJson(it) }
            val files = (chunkFiles + listOfNotNull(downloadedFile, thumbnailFile, telegramAudioFile, infoJson)).toTypedArray()
            eagerlyDelete(logger, *files)
        }
    }

    private suspend fun awaitOrDownload(prefetchedUrl: String?, prefetchedDownload: Deferred<File?>?, url: String, flags: List<String>): File? {
        if (prefetchedDownload != null && prefetchedUrl == url) return prefetchedDownload.await()
        // URL mismatch — cancel; if it had already completed, recover the orphan file and delete it.
        prefetchedDownload?.let { deferred ->
            deferred.cancel()
            runCatching { deferred.await() }.getOrNull()?.delete()
        }
        return downloader.download("${UUID.randomUUID()}.%(ext)s", url, *flags.toTypedArray())
    }
}
