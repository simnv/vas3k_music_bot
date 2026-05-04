package dev.storozhenko.music.services

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import dev.storozhenko.music.OdesilPlatformData
import dev.storozhenko.music.OdesilResponse
import dev.storozhenko.music.getLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.time.Duration

class MusicSearchService {
    private val logger = getLogger()
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val objectMapper = ObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    suspend fun enrichMissingPlatforms(response: OdesilResponse): OdesilResponse {
        val data = response.entitiesByUniqueId[response.entityUniqueId]
        val artist = data?.artistName?.takeIf { it.isNotBlank() } ?: return response
        val title = data.title?.takeIf { it.isNotBlank() } ?: return response
        val needsYandex = "yandex" !in response.linksByPlatform
        val needsApple = "appleMusic" !in response.linksByPlatform
        if (!needsYandex && !needsApple) return response
        val query = buildSearchQuery(artist, title)

        val (yandex, apple) = coroutineScope {
            val y = if (needsYandex) async { searchYandex(query) } else null
            val a = if (needsApple) async { searchAppleMusic(query) } else null
            (y?.await() to a?.await())
        }

        val newLinks = response.linksByPlatform.toMutableMap()
        if (needsYandex && yandex != null) newLinks["yandex"] = OdesilPlatformData(yandex)
        if (needsApple && apple != null) newLinks["appleMusic"] = OdesilPlatformData(apple)
        if (newLinks.size != response.linksByPlatform.size) {
            logger.info("Enriched Odesli (query='$query'): yandex=$yandex apple=$apple")
        }
        return response.copy(linksByPlatform = newLinks)
    }

    private fun buildSearchQuery(artist: String, title: String): String {
        // Strip YouTube channel-name country-code suffixes (e.g. "Marilyn Manson FR" → "Marilyn Manson").
        val cleanArtist = stripAnnotations(artist)
            .replace(Regex("\\s+(FR|DE|RU|US|UK|JP|IT|ES|BR|MX|KR|TH|VN|CN|TW|PL|NL|SE|NO|FI|DK|AU|CA)$"), "")
            .trim()
        val cleanTitle = stripAnnotations(title).let { t ->
            if (t.contains(" - ")) t.substringAfter(" - ").trim() else t
        }
        return listOf(cleanArtist, cleanTitle).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun stripAnnotations(s: String): String =
        s.replace(Regex("\\s*\\[[^\\]]*\\]"), "")
            .replace(Regex("\\s*\\([^)]*\\)"), "")
            .trim()
            .replace(Regex("\\s+"), " ")

    // Unofficial internal Yandex.Music endpoint (no public API). May break without notice.
    fun searchYandex(query: String): String? = runCatching {
        val encoded = URLEncoder.encode(query, Charset.defaultCharset())
        val req = HttpRequest.newBuilder()
            .uri(URI.create("https://music.yandex.ru/handlers/music-search.jsx?text=$encoded&type=tracks"))
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/json")
            .header("X-Retpath-Y", "/")
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) {
            logger.info("Yandex search non-200 for '$query': ${resp.statusCode()}")
            return@runCatching null
        }
        val tree = objectMapper.readTree(resp.body())
        val track = tree["tracks"]?.get("items")?.firstOrNull() ?: return@runCatching null
        val trackId = track["id"]?.asText()?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val albumId = track["albums"]?.firstOrNull()?.get("id")?.asText()?.takeIf { it.isNotBlank() }
            ?: return@runCatching null
        "https://music.yandex.ru/album/$albumId/track/$trackId"
    }.onFailure { logger.warn("Yandex search failed for '$query': ${it.message}") }.getOrNull()

    fun searchAppleMusic(query: String): String? = runCatching {
        val encoded = URLEncoder.encode(query, Charset.defaultCharset())
        val req = HttpRequest.newBuilder()
            .uri(URI.create("https://itunes.apple.com/search?term=$encoded&entity=song&limit=1"))
            .timeout(Duration.ofSeconds(5))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) {
            logger.info("iTunes search non-200 for '$query': ${resp.statusCode()}")
            return@runCatching null
        }
        val tree = objectMapper.readTree(resp.body())
        val first = tree["results"]?.firstOrNull() ?: return@runCatching null
        first["trackViewUrl"]?.asText()?.takeIf { it.isNotBlank() }
    }.onFailure { logger.warn("iTunes search failed for '$query': ${it.message}") }.getOrNull()
}
