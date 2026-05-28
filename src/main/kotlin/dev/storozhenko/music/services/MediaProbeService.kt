package dev.storozhenko.music.services

import dev.storozhenko.music.getLogger
import dev.storozhenko.music.shellJoin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

data class VideoDimensions(val width: Int, val height: Int, val duration: Int)

class MediaProbeService(private val virtualDispatcher: CoroutineDispatcher) {
    private val logger = getLogger()

    suspend fun getMediaDuration(file: File): Int? = runInterruptible(virtualDispatcher) {
        val command = listOf(
            "ffprobe", "-v", "error",
            "-show_entries", "format=duration",
            "-of", "csv=p=0",
            file.absolutePath
        )
        try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.toDoubleOrNull()?.toInt()
        } catch (e: Exception) {
            logger.warn("ffprobe duration failed for ${file.absolutePath}: ${e.message}")
            null
        }
    }

    suspend fun getVideoDimensions(videoFile: File): VideoDimensions? = runInterruptible(virtualDispatcher) {
        logger.info("Running ffprobe for $videoFile...")
        val command = listOf(
            "ffprobe", "-v", "error",
            "-select_streams", "v:0",
            "-show_entries", "stream=width,height:stream_side_data=rotation:stream_tags=rotate:format=duration",
            "-of", "default=noprint_wrappers=1",
            videoFile.absolutePath
        )
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val lines = BufferedReader(InputStreamReader(process.inputStream)).readLines()
        logger.info("Got ${lines.size} lines from ffprobe: $lines")
        process.waitFor()
        val values = lines.mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }.toMap()
        runCatching {
            val rawWidth = values.getValue("width").toDouble().toInt()
            val rawHeight = values.getValue("height").toDouble().toInt()
            val duration = values.getValue("duration").toDouble().toInt()
            // ffprobe reports the *stored* frame size; phone/Shorts videos are often stored
            // landscape with a rotation tag, so swap to display dimensions on 90°/270° rotation.
            val rotation = values.entries
                .firstOrNull { it.key.equals("rotation", true) || it.key.endsWith("rotate", true) }
                ?.value?.toDoubleOrNull()?.toInt() ?: 0
            val swap = Math.floorMod(rotation, 180) == 90
            VideoDimensions(
                width = if (swap) rawHeight else rawWidth,
                height = if (swap) rawWidth else rawHeight,
                duration = duration
            )
        }.getOrElse {
            logger.error("Failed to parse video dimensions: $lines", it)
            null
        }
    }

    suspend fun getTotalFreezeDuration(videoFile: File, startTime: Double, duration: Double): Double = runInterruptible(virtualDispatcher) {
        logger.info("Running ffmpeg for $videoFile to find freezes...")
        val command = listOf(
            "ffmpeg", "-v", "error",
            "-ss", startTime.toString(), "-t", duration.toString(),
            "-i", videoFile.absolutePath,
            "-vf", "freezedetect=n=-30dB:d=1,metadata=mode=print:file=-",
            "-map", "0:v:0", "-f", "null", "-"
        )
        logger.info(command.shellJoin())
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        var totalFreezeDuration = 0.0
        var openFreezeStart: Double? = null
        val freezeStartPattern = Regex("lavfi\\.freezedetect\\.freeze_start=(\\d+\\.?\\d*)")
        val freezeDurationPattern = Regex("lavfi\\.freezedetect\\.freeze_duration=(\\d+\\.?\\d*)")
        val freezeEndPattern = Regex("lavfi\\.freezedetect\\.freeze_end=(\\d+\\.?\\d*)")
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            reader.lineSequence().forEach { line ->
                freezeStartPattern.find(line)?.let { openFreezeStart = it.groupValues[1].toDouble() }
                freezeDurationPattern.find(line)?.let { totalFreezeDuration += it.groupValues[1].toDouble() }
                freezeEndPattern.find(line)?.let { openFreezeStart = null }
            }
        }
        process.waitFor()
        openFreezeStart?.let { start ->
            val ongoing = (duration - start).coerceAtLeast(0.0)
            totalFreezeDuration += ongoing
            logger.info("Open freeze from $start to clip end (+$ongoing)")
        }
        logger.info("Total Freeze Duration: $totalFreezeDuration")
        totalFreezeDuration
    }

    suspend fun getSceneChangeCount(videoFile: File, startTime: Double, duration: Double): Int = runInterruptible(virtualDispatcher) {
        logger.info("Running ffmpeg for $videoFile to count scene changes...")
        val command = listOf(
            "ffmpeg", "-v", "error",
            "-ss", startTime.toString(), "-t", duration.toString(),
            "-i", videoFile.absolutePath,
            "-vf", "select='gt(scene,0.05)',metadata=print:file=-",
            "-map", "0:v:0", "-an", "-f", "null", "-"
        )
        logger.info(command.shellJoin())
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
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

    /**
     * Decides whether a downloaded video should be sent as video (true) or audio (false)
     * by sampling scene changes and freeze ratios across spaced windows.
     */
    suspend fun decideSendAsVideo(file: File, durationSec: Int): Boolean {
        val windows: List<Pair<Double, Double>> = when {
            durationSec <= 30 -> listOf(0.0 to durationSec.toDouble())
            durationSec > 60 -> {
                val start = 30.0
                val span = durationSec - 30.0
                listOf(0.10, 0.30, 0.50, 0.70, 0.90).map { (start + span * it) to 5.0 }
            }
            else -> listOf(0.10, 0.30, 0.50, 0.70, 0.90).map { (durationSec * it) to 5.0 }
        }
        logger.info("${file.absolutePath} duration=${durationSec}s, analysis windows=$windows")
        val (totalSceneCount, totalFreezeDuration) = coroutineScope {
            val sceneJobs = windows.map { (s, d) -> async { getSceneChangeCount(file, s, d) } }
            val freezeJobs = windows.map { (s, d) -> async { getTotalFreezeDuration(file, s, d) } }
            sceneJobs.awaitAll().sum() to freezeJobs.awaitAll().sum()
        }
        if (totalSceneCount >= 2) {
            logger.info("${file.absolutePath} totalSceneCount=$totalSceneCount >= 2, treating as moving")
            return true
        }
        val totalAnalyzed = windows.sumOf { (_, d) -> d }
        val ratio = totalFreezeDuration / totalAnalyzed
        logger.info("${file.absolutePath} totalSceneCount=$totalSceneCount, totalFreezeDuration / totalAnalyzed = $totalFreezeDuration / $totalAnalyzed = $ratio")
        return ratio < 0.5
    }
}
