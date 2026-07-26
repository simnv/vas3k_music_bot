package dev.storozhenko.music.services

import com.fasterxml.jackson.databind.ObjectMapper
import dev.storozhenko.music.changeExtension
import dev.storozhenko.music.delayedDelete
import dev.storozhenko.music.getLogger
import dev.storozhenko.music.shellJoin
import dev.storozhenko.music.validateThumbnailFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID

data class VideoMeta(val title: String, val durationSec: Int?)

class DownloadService(
    private val ytdlLocation: String,
    private val virtualDispatcher: CoroutineDispatcher,
    private val fileDeleteScope: CoroutineScope,
    private val ipv6UrlContains: String?,
    private val ytdlProxy: String?,
    private val ytdlProxyUrlContains: String?,
    private val errorNotificationService: ErrorNotificationService? = null
) {
    private val logger = getLogger()
    private val objectMapper = ObjectMapper()
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")

    // Sidecars yt-dlp writes next to the media file. They must never be mistaken for the download
    // itself — --write-info-json would otherwise be picked up as the "media" file by listFiles order.
    private val sidecarExtensions = imageExtensions + "json"

    private companion object {
        const val MAX_INFO_JSON_BYTES = 8L * 1024 * 1024
    }

    private fun getIpVersionParam(url: String): String? {
        if (ipv6UrlContains.isNullOrEmpty()) return null
        val list = ipv6UrlContains.split(",").map { it.trim() }
        return if (list.any { url.contains(it, ignoreCase = true) }) "-6" else "-4"
    }

    private fun getProxyParams(url: String): List<String> {
        if (ytdlProxy.isNullOrEmpty() || ytdlProxyUrlContains.isNullOrEmpty()) return emptyList()
        val list = ytdlProxyUrlContains.split(",").map { it.trim() }
        return if (list.any { url.contains(it, ignoreCase = true) }) listOf("--proxy", ytdlProxy) else emptyList()
    }

    // --write-info-json costs no extra network round-trip and carries `categories`, which is how we
    // spot a song hiding behind a plain youtube.com link (a static image with audio, uploaded to an
    // ordinary channel) without probing every video in every chat.
    fun videoFlags(url: String, formatSelector: String): List<String> =
        commonYtDlpFlags(url) + listOf(
            "-f", formatSelector, "--merge-output-format", "mp4", "--write-info-json",
        )

    fun audioFlags(url: String): List<String> =
        commonYtDlpFlags(url) + listOf(
            "-f", "bestaudio[ext=m4a]/bestaudio[ext=mp3]/bestaudio",
            "--print", "before_dl:[QUALITY] source: id=%(format_id)s codec=%(acodec)s abr=%(abr)skbps asr=%(asr)sHz ext=%(ext)s"
        )

    fun commonYtDlpFlags(url: String): List<String> = buildList {
        getIpVersionParam(url)?.let { add(it) }
        addAll(getProxyParams(url))
        addAll(listOf(
            "--cookies", "/cookies.txt",
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

    suspend fun download(filename: String, url: String, vararg params: String): File? = coroutineScope {
        logger.info("Running yt-dlp for $url...")
        val folder = File("/tmp", UUID.randomUUID().toString()).apply { mkdir() }
        val command = listOf(ytdlLocation, url, "-o", "${folder.absolutePath}/$filename") + params.toList()
        logger.info(command.shellJoin())
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val readerJob = launch(virtualDispatcher) {
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.lineSequence().forEach { logger.info(it) }
                }
            }
        }
        try {
            process.onExit().await()
        } finally {
            if (process.isAlive) {
                logger.info("yt-dlp interrupted, force-destroying subprocess for $url")
                process.destroyForcibly()
                folder.deleteRecursively()
            }
            readerJob.cancel()
        }
        logger.info("Finished running yt-dlp for $url")
        val files = folder.listFiles()
        if (files == null || files.isEmpty()) {
            logger.error("Can't download file for url $url")
            return@coroutineScope null
        }
        val mediaFile = selectMediaFile(files)
            ?: run {
                logger.error("No media file found for url $url, only: ${files.map { it.name }}")
                return@coroutineScope null
            }
        scheduleDelete(mediaFile)
        // Register sidecars up front: validation failures and cancellation can return long before
        // anything calls readCategories/resolveSiblingThumbnail, which would otherwise strand them.
        files.filter { it !== mediaFile }.forEach { scheduleDelete(it) }
        mediaFile
    }

    fun resolveSiblingThumbnail(mediaFile: File): File? {
        val candidate = mediaFile.changeExtension("jpg")
        if (!candidate.exists()) return null
        val (isValid, msg) = validateThumbnailFile(candidate)
        if (!isValid) {
            logger.warn("Thumbnail validation failed: $msg. Continuing without thumbnail.")
            return null
        }
        scheduleDelete(candidate)
        return candidate
    }

    /**
     * The downloaded media among yt-dlp's output, ignoring sidecars. Order matters: `listFiles()`
     * gives no ordering guarantee and `<uuid>.info.json` sorts before `<uuid>.mp4`, so the sidecar
     * would be returned as the media file if it were not excluded.
     */
    internal fun selectMediaFile(files: Array<File>): File? =
        files.firstOrNull { it.extension.lowercase() !in sidecarExtensions }

    /** The `.info.json` sidecar written by --write-info-json, named `<base>.info.json`. */
    fun resolveSiblingInfoJson(mediaFile: File): File? =
        mediaFile.resolveSibling("${mediaFile.nameWithoutExtension}.info.json")
            .takeIf { it.exists() }
            ?.also { scheduleDelete(it) }

    /**
     * YouTube categories for a downloaded file, e.g. `["Music"]`. Empty when the sidecar is absent
     * or unreadable — callers must treat that as "unknown", never as "not music".
     */
    fun readCategories(mediaFile: File): List<String> = runCatching {
        val info = resolveSiblingInfoJson(mediaFile) ?: return emptyList()
        // The sidecar is normally tens of KB. Cap it so a pathological file can't exhaust the
        // container's 128MB heap during a full-tree parse.
        if (info.length() > MAX_INFO_JSON_BYTES) {
            logger.warn("Ignoring oversized info json (${info.length()} bytes): ${info.absolutePath}")
            return emptyList()
        }
        val categories = objectMapper.readTree(info).path("categories")
        // Require an array: an object would otherwise have its textual children iterated, so
        // {"categories":{"primary":"Music"}} would read as ["Music"].
        if (!categories.isArray) return emptyList()
        categories.mapNotNull { node -> node.takeIf { it.isTextual }?.asText() }
    }.getOrElse {
        logger.warn("Could not read categories beside ${mediaFile.absolutePath}: ${it.message}")
        emptyList()
    }

    suspend fun ytSearchFirst(query: String, durationSec: Int? = null): String? = runInterruptible(virtualDispatcher) {
        logger.info("Running yt-dlp ytsearch for: $query (duration=$durationSec)")
        val (searchPrefix, matchFilter) = if (durationSec != null) {
            val low = (durationSec - 15).coerceAtLeast(1)
            val high = durationSec + 15
            "ytsearch5" to "duration > $low & duration < $high"
        } else "ytsearch1" to null
        val command = mutableListOf(
            "yt-dlp",
            "--cookies", "/cookies.txt",
            "--print", "%(webpage_url)s",
            "--skip-download",
            "--js-runtimes", "deno:/usr/bin/deno",
            "--remote-components", "ejs:github",
            "--retries", "5",
            "--fragment-retries", "5",
            "--socket-timeout", "30"
        )
        matchFilter?.let { command.addAll(listOf("--match-filter", it)) }
        command.add("$searchPrefix:$query")
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

    suspend fun getVideoMeta(url: String): VideoMeta = runInterruptible(virtualDispatcher) {
        logger.info("Running yt-dlp to get title and duration for $url...")
        val command = mutableListOf<String>().apply {
            addAll(listOf(
                "yt-dlp",
                "--cookies", "/cookies.txt",
                "--print", "%(title)s\t%(duration)s",
                "--js-runtimes", "deno:/usr/bin/deno",
                "--remote-components", "ejs:github",
                "--retries", "5",
                "--fragment-retries", "5",
                "--socket-timeout", "30"
            ))
            getIpVersionParam(url)?.let { add(it) }
            addAll(getProxyParams(url))
            add(url)
        }
        logger.info(command.shellJoin())
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = BufferedReader(InputStreamReader(process.inputStream)).readLine() ?: ""
        logger.info("Got $output from yt-dlp")
        process.waitFor()
        if (output.contains(": No video formats found!")) return@runInterruptible VideoMeta("", null)
        val parts = output.split("\t", limit = 2)
        VideoMeta(parts.getOrNull(0).orEmpty(), parts.getOrNull(1)?.toIntOrNull())
    }

    private fun scheduleDelete(file: File) {
        fileDeleteScope.delayedDelete(file, logger) { e ->
            errorNotificationService?.sendErrorNotification(e, "File Deletion: ${file.absolutePath}")
        }
    }
}
