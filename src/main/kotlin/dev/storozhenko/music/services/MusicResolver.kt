package dev.storozhenko.music.services

import com.fasterxml.jackson.databind.ObjectMapper
import dev.storozhenko.music.getLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** A track identified from a posted link. */
data class TrackIdentity(val artist: String, val title: String) {
    val query: String get() = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
}

/**
 * Links for one track. [youtubeUrl] is kept structured rather than parsed back out of the rendered
 * message, because it is what the download pipeline actually fetches.
 */
data class ResolvedTrack(
    val identity: TrackIdentity,
    val links: Map<String, String>,
    val youtubeUrl: String?,
)

/**
 * Resolves a music-service link to a track and to links on the other services, replacing the
 * Odesli API whose free tier now returns 401 PUBLIC_API_ACCESS_DEPRECATED.
 *
 * Two stages: identify (artist, title) from the posted link, then look the track up elsewhere.
 * Lookups run concurrently — they are independent, and doing them in series would add their
 * latencies together inside the admission slot.
 */
class MusicResolver(
    private val web: WebFetcher,
    private val ytSearch: suspend (String) -> String?,
    private val spotify: SpotifyClient? = null,
) {
    private val logger = getLogger()
    private val mapper = ObjectMapper()

    suspend fun resolve(sourceUrl: String): ResolvedTrack? {
        val identity = identify(sourceUrl) ?: return null
        val query = identity.query
        if (query.isBlank()) return null

        val (apple, yandex, spotifyUrl, youtube) = coroutineScope {
            // Seed each platform with the posted URL rather than searching for what we were given.
            val appleJob = async { sourceIfHost(sourceUrl, "apple.com") ?: searchApple(query) }
            val yandexJob = async { sourceIfHost(sourceUrl, "yandex.ru", "yandex.com") ?: searchYandex(query) }
            val spotifyJob = async { sourceIfHost(sourceUrl, "spotify.com") ?: spotify?.searchTrackUrl(query) }
            val youtubeJob = async { runCatching { ytSearch(query) }.getOrNull() }
            Quad(appleJob.await(), yandexJob.await(), spotifyJob.await(), youtubeJob.await())
        }

        val links = linkedMapOf<String, String>()
        yandex?.let { links["Yandex.Music"] = it }
        youtube?.let { links["YouTube"] = it }
        apple?.let { links["Apple Music"] = it }
        spotifyUrl?.let { links["Spotify"] = it }
        if (links.isEmpty()) return null
        return ResolvedTrack(identity, links, youtube)
    }

    private data class Quad(val a: String?, val b: String?, val c: String?, val d: String?)

    private fun sourceIfHost(url: String, vararg suffixes: String): String? = runCatching {
        val host = URI(url).host.lowercase()
        url.takeIf { suffixes.any { s -> host == s || host.endsWith(".$s") } }
    }.getOrNull()

    // ---- stage 1: identify -------------------------------------------------

    suspend fun identify(url: String): TrackIdentity? {
        val host = runCatching { URI(url).host.lowercase() }.getOrNull() ?: return null
        return when {
            host.endsWith("apple.com") -> appleLookup(url)
            host.endsWith("yandex.ru") || host.endsWith("yandex.com") -> ogIdentity(url)
            host.endsWith("spotify.com") -> spotify?.trackIdentity(spotifyTrackId(url) ?: return null)
            else -> ogIdentity(url)
        }
    }

    /** Apple/iTunes links carry the track id in `?i=`; the keyless lookup API gives exact metadata. */
    internal fun appleTrackId(url: String): String? =
        Regex("[?&]i=(\\d+)").find(url)?.groupValues?.get(1)

    private suspend fun appleLookup(url: String): TrackIdentity? {
        val id = appleTrackId(url) ?: return ogIdentity(url)
        val body = web.get("https://itunes.apple.com/lookup?id=$id") ?: return null
        return parseItunes(body)
    }

    internal fun parseItunes(body: String): TrackIdentity? = runCatching {
        val first = mapper.readTree(body).path("results").firstOrNull() ?: return null
        val artist = first.path("artistName").asText("")
        val title = first.path("trackName").asText("")
        if (artist.isBlank() && title.isBlank()) null else TrackIdentity(artist, title)
    }.getOrNull()

    internal fun parseItunesUrl(body: String): String? = runCatching {
        mapper.readTree(body).path("results").firstOrNull()
            ?.path("trackViewUrl")?.asText("")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private suspend fun ogIdentity(url: String): TrackIdentity? =
        web.get(url)?.let { parseOg(it) }

    /**
     * Yandex renders `og:title` as the track name and `og:description` as "Artist • Трек • Year",
     * so the artist is the first bullet-separated field.
     */
    internal fun parseOg(html: String): TrackIdentity? {
        val title = metaContent(html, "og:title") ?: return null
        val description = metaContent(html, "og:description").orEmpty()
        val artist = description.split("•").firstOrNull()?.trim().orEmpty()
        if (title.isBlank()) return null
        return TrackIdentity(artist, title)
    }

    private fun metaContent(html: String, property: String): String? =
        Regex(
            "<meta[^>]+(?:property|name)=[\"']$property[\"'][^>]*content=[\"']([^\"']*)[\"']",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1)
            ?: Regex(
                "<meta[^>]+content=[\"']([^\"']*)[\"'][^>]*(?:property|name)=[\"']$property[\"']",
                RegexOption.IGNORE_CASE,
            ).find(html)?.groupValues?.get(1)

    internal fun spotifyTrackId(url: String): String? =
        Regex("/track/([A-Za-z0-9]{22})").find(url)?.groupValues?.get(1)

    // ---- stage 2: search ---------------------------------------------------

    private suspend fun searchApple(query: String): String? {
        val body = web.get("https://itunes.apple.com/search?term=${encode(query)}&entity=song&limit=1")
            ?: return null
        return parseItunesUrl(body)
    }

    /**
     * The public search page. The internal `handlers/music-search.jsx` endpoint the old
     * MusicSearchService used now returns 404, and Yandex publishes no other API.
     */
    private suspend fun searchYandex(query: String): String? {
        val html = web.get("https://music.yandex.ru/search?text=${encode(query)}&type=tracks") ?: return null
        return firstYandexTrack(html)
    }

    /** First `/album/N/track/M` in the markup is the top search hit (verified against known tracks). */
    internal fun firstYandexTrack(html: String): String? =
        Regex("/album/\\d+/track/\\d+").find(html)?.value?.let { "https://music.yandex.ru$it" }

    private fun encode(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)
}
