package dev.storozhenko.music.services

import com.fasterxml.jackson.databind.ObjectMapper
import dev.storozhenko.music.getLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Spotify Web API via the client-credentials flow. Spotify is the one required platform with no
 * keyless path — its pages are JS-rendered, so neither scraping nor oEmbed yields an artist.
 *
 * Only constructed when both credentials are configured; otherwise Spotify links are omitted.
 */
class SpotifyClient(
    private val clientId: String,
    private val clientSecret: String,
    private val virtualDispatcher: CoroutineDispatcher,
) {
    private val logger = getLogger()
    private val mapper = ObjectMapper()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build()

    @Volatile
    private var token: String? = null

    @Volatile
    private var tokenExpiry: Instant = Instant.EPOCH

    private suspend fun accessToken(now: Instant): String? {
        token?.let { if (now.isBefore(tokenExpiry)) return it }
        return runInterruptible(virtualDispatcher) {
            runCatching {
                val basic = Base64.getEncoder()
                    .encodeToString("$clientId:$clientSecret".toByteArray(StandardCharsets.UTF_8))
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("https://accounts.spotify.com/api/token"))
                    .timeout(Duration.ofSeconds(6))
                    .header("Authorization", "Basic $basic")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() != 200) {
                    logger.info("Spotify token request -> HTTP ${response.statusCode()}")
                    return@runCatching null
                }
                val node = mapper.readTree(response.body())
                val value = node.path("access_token").asText("").takeIf { it.isNotBlank() }
                    ?: return@runCatching null
                // Renew a minute early so a token can't expire mid-request.
                tokenExpiry = now.plusSeconds(node.path("expires_in").asLong(3600) - 60)
                token = value
                value
            }.getOrElse {
                logger.info("Spotify token request failed: ${it.message}")
                null
            }
        }
    }

    private suspend fun apiGet(path: String, now: Instant): String? {
        val bearer = accessToken(now) ?: return null
        return runInterruptible(virtualDispatcher) {
            runCatching {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.spotify.com/v1/$path"))
                    .timeout(Duration.ofSeconds(6))
                    .header("Authorization", "Bearer $bearer")
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() != 200) {
                    logger.info("Spotify GET $path -> HTTP ${response.statusCode()}")
                    null
                } else {
                    response.body()
                }
            }.getOrElse {
                logger.info("Spotify GET $path failed: ${it.message}")
                null
            }
        }
    }

    suspend fun trackIdentity(trackId: String, now: Instant = Instant.now()): TrackIdentity? {
        val body = apiGet("tracks/$trackId", now) ?: return null
        return parseTrack(body)
    }

    suspend fun searchTrackUrl(query: String, now: Instant = Instant.now()): String? {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val body = apiGet("search?q=$encoded&type=track&limit=1", now) ?: return null
        return parseSearchUrl(body)
    }

    internal fun parseTrack(body: String): TrackIdentity? = runCatching {
        val node = mapper.readTree(body)
        val title = node.path("name").asText("")
        val artist = node.path("artists").firstOrNull()?.path("name")?.asText("").orEmpty()
        if (title.isBlank()) null else TrackIdentity(artist, title)
    }.getOrNull()

    internal fun parseSearchUrl(body: String): String? = runCatching {
        mapper.readTree(body).path("tracks").path("items").firstOrNull()
            ?.path("external_urls")?.path("spotify")?.asText("")?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
