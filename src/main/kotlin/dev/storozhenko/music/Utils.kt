package dev.storozhenko.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Duration.Companion.minutes

@Suppress("unused")
inline fun <reified T> T.getLogger(): Logger = LoggerFactory.getLogger(T::class.java)

private val SHELL_SAFE = "_./@:=+-,%"

fun String.shellQuote(): String {
    if (this.isEmpty()) return "''"
    if (this.all { it.isLetterOrDigit() || it in SHELL_SAFE }) return this
    return "'" + this.replace("'", "'\\''") + "'"
}

fun List<String>.shellJoin(): String = joinToString(" ") { it.shellQuote() }

fun String.split2ByDash(reverseIfSingle: Boolean = false): Pair<String, String> {
    val parts = this.split(" - ", limit = 2)
    return when {
        parts.size == 2 -> parts[0] to parts[1]
        reverseIfSingle -> "" to parts[0]
        else -> parts[0] to ""
    }
}

fun String.removeFirstLine(): String = this.lineSequence().drop(1).joinToString("\n")

fun File.changeExtension(newExtension: String): File =
    this.resolveSibling("${this.nameWithoutExtension}.$newExtension")

enum class Quality(val label: String, val formatSelector: String) {
    LOW("≤480p", "bestvideo[height<=480][ext=mp4][vcodec^=avc1]+bestaudio[ext=m4a]/best[height<=480][ext=mp4]/best[ext=mp4]/best"),
    MEDIUM("≤720p", "bestvideo[height<=720][ext=mp4][vcodec^=avc1]+bestaudio[ext=m4a]/best[height<=720][ext=mp4]/best[ext=mp4]/best"),
    HIGH("best", "bestvideo[ext=mp4][vcodec^=avc1]+bestaudio[ext=m4a]/best[ext=mp4]/best")
}

data class RequestOptions(val quality: Quality, val forceAudio: Boolean)

fun parseRequestOptions(text: String): RequestOptions {
    val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return RequestOptions(Quality.HIGH, false)
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
    return RequestOptions(quality ?: Quality.HIGH, forceAudio)
}

fun validateVideoFile(videoFile: File): Pair<Boolean, String> {
    if (!videoFile.exists() || !videoFile.isFile) return false to "File does not exist or is not a regular file"
    if (videoFile.length() == 0L) return false to "File is empty"
    val fileName = videoFile.name.lowercase()
    if (!fileName.endsWith(".mp4") && !fileName.endsWith(".mov") && !fileName.endsWith(".avi")) {
        return false to "Unsupported file format: ${videoFile.extension}. Telegram supports MP4, MOV, and AVI"
    }
    return true to "File is valid"
}

fun validateThumbnailFile(thumbnailFile: File?): Pair<Boolean, String> {
    if (thumbnailFile == null) return true to "No thumbnail provided (optional)"
    if (!thumbnailFile.exists() || !thumbnailFile.isFile) return false to "Thumbnail file does not exist or is not a regular file"
    if (thumbnailFile.length() == 0L) return false to "Thumbnail file is empty"
    val sizeBytes = thumbnailFile.length()
    if (sizeBytes > 200 * 1024) return false to "Thumbnail size exceeds recommended 200KB limit (${sizeBytes / 1024.0} KB)"
    val fileName = thumbnailFile.name.lowercase()
    if (!fileName.endsWith(".jpg") && !fileName.endsWith(".jpeg") && !fileName.endsWith(".png")) {
        return false to "Unsupported thumbnail format: ${thumbnailFile.extension}. Telegram supports JPG and PNG"
    }
    return true to "Thumbnail is valid"
}

fun CoroutineScope.delayedDelete(file: File, logger: Logger, onError: ((Throwable) -> Unit)? = null) {
    file.deleteOnExit()
    launch {
        runCatching {
            delay(55.minutes)
            file.delete()
            logger.info("${file.absolutePath} is deleted")
        }.onFailure {
            logger.error("Could not delete ${file.absolutePath}")
            onError?.invoke(it)
        }
    }
}

fun eagerlyDelete(logger: Logger, vararg files: File?) {
    files.filterNotNull().distinct().forEach { file ->
        runCatching {
            if (file.exists() && file.delete()) logger.info("Deleted ${file.absolutePath}")
        }.onFailure { logger.error("Failed to delete ${file.absolutePath}: ${it.message}", it) }
    }
}
