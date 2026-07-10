package dev.storozhenko.music.run

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.storozhenko.music.getLogger
import java.io.File

/**
 * Persists one small JSON file per in-flight download so a restarted bot can find and
 * clean up orphaned status messages. Lives on the /data volume to survive restarts.
 */
class JobMarkerStore(baseDir: File) {
    private val logger = getLogger()
    private val mapper = ObjectMapper().registerKotlinModule()

    val dir: File = if ((baseDir.isDirectory || baseDir.mkdirs()) && baseDir.canWrite()) {
        baseDir
    } else {
        File(System.getProperty("java.io.tmpdir"), "music-bot-jobs").apply { mkdirs() }
            .also { logger.warn("Cannot use ${baseDir.absolutePath} for job markers, falling back to ${it.absolutePath}") }
    }

    data class JobMarker(
        val chatId: Long,
        val statusMessageId: Int,
        val url: String?,
        val createdAtEpochMs: Long,
    )

    fun write(token: String, marker: JobMarker) {
        runCatching { mapper.writeValue(File(dir, "$token.json"), marker) }
            .onFailure { logger.error("Failed to write job marker $token: ${it.message}", it) }
    }

    fun delete(token: String) {
        runCatching { File(dir, "$token.json").delete() }
            .onFailure { logger.error("Failed to delete job marker $token: ${it.message}", it) }
    }

    fun listAll(): Map<String, JobMarker> =
        dir.listFiles { f: File -> f.extension == "json" }.orEmpty()
            .mapNotNull { f ->
                runCatching { f.nameWithoutExtension to mapper.readValue(f, JobMarker::class.java) }
                    .onFailure { logger.warn("Skipping corrupt job marker ${f.name}: ${it.message}") }
                    .getOrNull()
            }
            .toMap()
}
