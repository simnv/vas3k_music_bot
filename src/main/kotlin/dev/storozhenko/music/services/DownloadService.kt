package dev.storozhenko.music.services

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
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")

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
        val mediaFile = files.firstOrNull { it.extension.lowercase() !in imageExtensions }
            ?: run {
                logger.error("No media file found for url $url, only: ${files.map { it.name }}")
                return@coroutineScope null
            }
        scheduleDelete(mediaFile)
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
