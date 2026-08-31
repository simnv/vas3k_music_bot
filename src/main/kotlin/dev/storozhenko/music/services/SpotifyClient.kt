package dev.storozhenko.music.services

import com.fasterxml.jackson.databind.ObjectMapper
import dev.storozhenko.music.getLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** Token and its expiry as one immutable snapshot: two independent volatiles could pair an old
     *  token with a newly written expiry and hand back a credential that has already expired. */
    private data class CachedToken(val value: String, val expiry: Instant)

    @Volatile
    private var cached: CachedToken? = null

    /** Serialises refresh so an expiry does not send all concurrent requests to Spotify at once. */
    private val refreshLock = Mutex()

    private suspend fun accessToken(now: Instant): String? {
        cached?.let { if (now.isBefore(it.expiry)) return it.value }
        return refreshLock.withLock {
            // Re-check: another coroutine may have refreshed while we waited for the lock.
            cached?.let { if (now.isBefore(it.expiry)) return@withLock it.value }
            fetchToken(now)
        }
    }

    private suspend fun fetchToken(now: Instant): String? {
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
                cached = CachedToken(value, now.plusSeconds(node.path("expires_in").asLong(3600) - 60))
                value
            }.getOrElse {
                if (it is InterruptedException) throw it
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
                if (it is InterruptedException) throw it
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

    suspend fun albumIdentity(albumId: String, now: Instant = Instant.now()): TrackIdentity? {
        val body = apiGet("albums/$albumId", now) ?: return null
        return parseTrack(body) // albums carry the same name/artists shape
    }

    suspend fun searchAlbumUrl(query: String, now: Instant = Instant.now()): String? {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val body = apiGet("search?q=$encoded&type=album&limit=1", now) ?: return null
        return parseAlbumSearchUrl(body)
    }

    internal fun parseAlbumSearchUrl(body: String): String? = runCatching {
        mapper.readTree(body).path("albums").path("items").firstOrNull()
            ?.path("external_urls")?.path("spotify")?.asText("")?.takeIf { it.isNotBlank() }
    }.getOrNull()

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
