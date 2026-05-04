package dev.storozhenko.music.services

import dev.storozhenko.music.delayedDelete
import dev.storozhenko.music.getLogger
import dev.storozhenko.music.shellJoin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runInterruptible
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID

class MediaProcessingService(
    private val virtualDispatcher: CoroutineDispatcher,
    private val fileDeleteScope: CoroutineScope,
    private val defaultChunkSizeMB: Int,
    private val errorNotificationService: ErrorNotificationService? = null
) {
    private val logger = getLogger()

    suspend fun splitVideoIntoChunks(
        videoFile: File,
        outputPrefix: String,
        chunkSizeMB: Int = defaultChunkSizeMB
    ): List<File> = runInterruptible(virtualDispatcher) {
        logger.info("Splitting video file ${videoFile.absolutePath} into chunks of ${chunkSizeMB}MB...")
        val command = listOf(
            "mkvmerge", "--quiet",
            "-o", outputPrefix,
            "--split", "${chunkSizeMB}M",
            videoFile.absolutePath
        )
        logger.info("Executing command: ${command.shellJoin()}")
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
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
        val outputDir = videoFile.parentFile
        val chunkPrefix = File(outputPrefix).nameWithoutExtension
        logger.info("Looking for chunk files with prefix: $chunkPrefix in directory: ${outputDir.absolutePath}")
        val chunkFiles = outputDir.listFiles { _, name -> name.startsWith(chunkPrefix) }?.toList() ?: emptyList()
        logger.info("Created ${chunkFiles.size} chunk files")
        chunkFiles.forEach { logger.info("Chunk file: ${it.absolutePath}") }
        chunkFiles.sortedBy { it.name }
    }

    suspend fun convertToSquareThumbnail(inputFile: File): File =
        cropThumbnail(inputFile, "square", "crop='min(iw\\,ih)':'min(iw\\,ih)',scale=320:320")

    suspend fun convertToLandscapeThumbnail(inputFile: File): File =
        cropThumbnail(inputFile, "landscape", "crop='min(iw\\,ih*16/9)':'min(ih\\,iw*9/16)',scale=320:-2")

    private suspend fun cropThumbnail(inputFile: File, label: String, filter: String): File = runInterruptible(virtualDispatcher) {
        logger.info("Cropping thumbnail ${inputFile.absolutePath} to $label...")
        val outputFile = File(inputFile.parentFile, "${UUID.randomUUID()}_${label}_thumb.jpg")
        val command = listOf(
            "ffmpeg",
            "-hide_banner", "-nostats", "-loglevel", "error",
            "-i", inputFile.absolutePath,
            "-vf", filter,
            "-q:v", "2",
            outputFile.absolutePath
        )
        logger.info("Executing ffmpeg command: ${command.shellJoin()}")
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            reader.lineSequence().forEach { line ->
                if (line.isNotBlank()) logger.warn("ffmpeg: $line")
            }
        }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            logger.error("ffmpeg $label-thumbnail failed with exit code $exitCode")
            throw RuntimeException("Failed to crop thumbnail to $label")
        }
        scheduleDelete(outputFile)
        outputFile
    }

    suspend fun convertToTelegramAudio(inputFile: File): File = runInterruptible(virtualDispatcher) {
        logger.info("Extracting audio from ${inputFile.absolutePath} for Telegram...")
        val outputFile = File(inputFile.parentFile, "${UUID.randomUUID()}_telegram_audio.m4a")
        val command = listOf(
            "ffmpeg",
            "-hide_banner", "-nostats", "-loglevel", "error",
            "-i", inputFile.absolutePath,
            "-vn",
            "-acodec", "copy",
            outputFile.absolutePath
        )
        logger.info("Executing ffmpeg command: ${command.shellJoin()}")
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
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
        scheduleDelete(outputFile)
        outputFile
    }

    private fun scheduleDelete(file: File) {
        fileDeleteScope.delayedDelete(file, logger) { e ->
            errorNotificationService?.sendErrorNotification(e, "File Deletion: ${file.absolutePath}")
        }
    }
}
