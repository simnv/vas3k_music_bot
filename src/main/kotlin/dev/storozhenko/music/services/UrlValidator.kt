package dev.storozhenko.music.services

import dev.storozhenko.music.getLogger
import java.net.URL

class UrlValidator {
    private val logger = getLogger()

    data class DomainConfig(
        val hosts: Set<String>,
        val allowedPaths: Set<String>,
        val blockedPathPrefixes: Set<String> = emptySet(),
        val extraPathTokens: Set<String> = emptySet()
    )

    private val configs = mapOf(
        "youtube" to DomainConfig(
            hosts = setOf("youtube.com", "youtu.be"),
            allowedPaths = setOf("/watch", "/shorts/", "/embed/", "/v/", "/"),
            blockedPathPrefixes = setOf("/post/")
        ),
        "tiktok" to DomainConfig(
            hosts = setOf("tiktok.com", "vt.tiktok.com"),
            allowedPaths = setOf("/")
        ),
        "vk" to DomainConfig(
            hosts = setOf("vk.com", "vk.ru", "vkvideo.ru"),
            allowedPaths = setOf("/video", "/clip"),
            extraPathTokens = setOf("/video-", "/clip-")
        ),
        "rutube" to DomainConfig(
            hosts = setOf("rutube.ru"),
            allowedPaths = setOf("/video")
        )
    )

    fun isValidDownloadUrl(urlString: String): Boolean = runCatching {
        logger.info("Validating URL: $urlString")
        val url = URL(urlString)
        val host = url.host.lowercase()
        val path = url.path.lowercase()
        logger.info("Parsed URL - host: $host, path: $path")

        val cfg = configs.values.firstOrNull { cfg ->
            cfg.hosts.any { exact ->
                val matches = host == exact || host.endsWith(".$exact")
                if (matches) logger.info("Host '$host' matches config for '$exact'")
                matches
            }
        } ?: run {
            logger.info("No config found for host: $host")
            return@runCatching false
        }

        logger.info("Using config with hosts: ${cfg.hosts}, allowedPaths: ${cfg.allowedPaths}, blockedPathPrefixes: ${cfg.blockedPathPrefixes}, extraPathTokens: ${cfg.extraPathTokens}")

        cfg.blockedPathPrefixes.find { path.startsWith(it) }?.let { blocked ->
            logger.info("URL rejected: path '$path' starts with blocked prefix '$blocked'")
            return@runCatching false
        }
        cfg.allowedPaths.find { path.startsWith(it) }?.let { allowed ->
            logger.info("URL accepted: path '$path' starts with allowed prefix '$allowed'")
            return@runCatching true
        }
        cfg.extraPathTokens.find { path.contains(it) }?.let { token ->
            logger.info("URL accepted: path '$path' contains extra token '$token'")
            return@runCatching true
        }
        logger.info("URL rejected: path '$path' doesn't match any allowed patterns")
        false
    }.getOrElse { e ->
        logger.error("Error validating URL '$urlString': ${e.message}", e)
        false
    }
}
