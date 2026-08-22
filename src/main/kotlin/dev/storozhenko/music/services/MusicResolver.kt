package dev.storozhenko.music.services

import com.fasterxml.jackson.databind.ObjectMapper
import dev.storozhenko.music.getLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
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
 * Lookups run concurrently — they are independent, and running them in series would add their
 * latencies together inside the admission slot.
 */
class MusicResolver(
    private val web: WebFetcher,
    private val ytSearch: suspend (String) -> String?,
    private val spotify: SpotifyClient? = null,
    private val appleStorefront: String = "ru",
) {
    private val logger = getLogger()
    private val mapper = ObjectMapper()

    suspend fun resolve(sourceUrl: String): ResolvedTrack? {
        val identity = identify(sourceUrl) ?: return null
        val query = identity.query
        if (query.isBlank()) return null

        val results = coroutineScope {
            // Seed each platform with the posted URL rather than searching for what we were given.
            val apple = async { safe { sourceIfHost(sourceUrl, "apple.com") ?: searchApple(query) } }
            val yandex = async { safe { sourceIfHost(sourceUrl, "yandex.ru", "yandex.com") ?: searchYandex(query) } }
            val spot = async { safe { sourceIfHost(sourceUrl, "spotify.com") ?: spotify?.searchTrackUrl(query) } }
            val youtube = async {
                safe { sourceIfHost(sourceUrl, "youtube.com", "youtu.be") ?: ytSearch(query) }
            }
            listOf(apple.await(), yandex.await(), spot.await(), youtube.await())
        }
        val (apple, yandex, spotifyUrl, youtube) = results

        val links = linkedMapOf<String, String>()
        yandex?.let { links["Yandex.Music"] = it }
        youtube?.let { links["YouTube"] = it }
        apple?.let { links["Apple Music"] = it }
        spotifyUrl?.let { links["Spotify"] = it }
        if (links.isEmpty()) return null
        return ResolvedTrack(identity, links, youtube)
    }

    /**
     * One failing lookup must not cancel its siblings — `coroutineScope` would otherwise propagate
     * the failure and lose the platforms that did resolve. Cancellation itself must still escape.
     */
    private suspend fun <T> safe(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.info("Lookup failed: ${e.message}")
        null
    }

    internal fun hostMatches(host: String, suffix: String): Boolean =
        host == suffix || host.endsWith(".$suffix")

    private fun sourceIfHost(url: String, vararg suffixes: String): String? = runCatching {
        val host = URI(url).host.lowercase()
        url.takeIf { suffixes.any { s -> hostMatches(host, s) } }
    }.getOrNull()

    // ---- stage 1: identify -------------------------------------------------

    suspend fun identify(url: String): TrackIdentity? {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return null
        return when {
            hostMatches(host, "apple.com") -> appleLookup(url)
            hostMatches(host, "yandex.ru") || hostMatches(host, "yandex.com") -> yandexLookup(url)
            hostMatches(host, "spotify.com") -> spotifyIdentity(url)
            else -> ogIdentity(url, bulletArtist = false)
        }
    }

    /** Apple/iTunes links carry the track id in `?i=`; the keyless lookup API gives exact metadata. */
    internal fun appleTrackId(url: String): String? =
        Regex("[?&]i=(\\d+)(?:&|$)").find(url)?.groupValues?.get(1)

    private suspend fun appleLookup(url: String): TrackIdentity? {
        val id = appleTrackId(url) ?: return ogIdentity(url, bulletArtist = false)
        val body = web.get("https://itunes.apple.com/lookup?id=$id") ?: return null
        return parseItunes(body)
    }

    /**
     * Spotify pages are JS-rendered with no og: tags. With credentials the Web API gives artist and
     * title; without them oEmbed still yields the title, which is a weaker but usable query.
     */
    private suspend fun spotifyIdentity(url: String): TrackIdentity? {
        val id = spotifyTrackId(url) ?: return null
        spotify?.trackIdentity(id)?.let { return it }
        val body = web.get("https://open.spotify.com/oembed?url=$url") ?: return null
        return parseOembedTitle(body)
    }

    internal fun parseOembedTitle(body: String): TrackIdentity? = runCatching {
        mapper.readTree(body).path("title").asText("").takeIf { it.isNotBlank() }
            ?.let { TrackIdentity("", it) }
    }.getOrNull()

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

    private suspend fun ogIdentity(url: String, bulletArtist: Boolean): TrackIdentity? =
        web.get(url)?.let { parseOg(it, bulletArtist) }

    /**
     * Parsed with Jsoup rather than a regex: og content is HTML, so it carries entities
     * (`&amp;`, `&#39;`) that must be decoded, and a hand-rolled attribute pattern truncates a
     * double-quoted value at the first apostrophe — "Don't Stop" would become "Don".
     *
     * @param bulletArtist Yandex renders og:description as "Artist • Трек • Year". That convention
     *   is Yandex's own, so it is not applied to other hosts.
     */
    internal fun parseOg(html: String, bulletArtist: Boolean = true): TrackIdentity? {
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("meta[name=og:title]")?.attr("content")
            ?: return null
        if (title.isBlank()) return null
        val artist = if (bulletArtist) {
            val description = doc.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()
            description.substringBefore("•").trim()
        } else {
            ""
        }
        return TrackIdentity(artist, title)
    }

    internal fun spotifyTrackId(url: String): String? =
        Regex("/track/([A-Za-z0-9]{22})(?:[/?#]|$)").find(url)?.groupValues?.get(1)

    // ---- stage 2: search ---------------------------------------------------

    /**
     * Apple has no storefront-neutral link — an URL without a country segment just 301s to `/us/`.
     * Search the [appleStorefront] first so links open in the right store, and fall back to the
     * default (US) catalogue only when that storefront does not carry the track.
     */
    private suspend fun searchApple(query: String): String? {
        val term = encode(query)
        val local = web.get(
            "https://itunes.apple.com/search?term=$term&entity=song&limit=1&country=$appleStorefront"
        )?.let { parseItunesUrl(it) }
        if (local != null) return local
        return web.get("https://itunes.apple.com/search?term=$term&entity=song&limit=1")
            ?.let { parseItunesUrl(it) }
    }

    /**
     * Yandex's app-facing API, proxied. The web front end is unusable from a datacenter address —
     * the search page answers with a captcha and track pages with a metadata-free JS shell — and
     * `api.music.yandex.net` returns HTTP 451 unless the request exits through the SOCKS proxy that
     * already carries VK and RuTube traffic. The old `handlers/music-search.jsx` endpoint is 404.
     */
    private suspend fun searchYandex(query: String): String? {
        val body = web.get(
            "https://api.music.yandex.net/search?text=${encode(query)}&type=track&page=0",
            useProxy = true,
        ) ?: return null
        return parseYandexSearch(body)
    }

    private suspend fun yandexLookup(url: String): TrackIdentity? {
        val id = yandexTrackId(url) ?: return null
        val body = web.get("https://api.music.yandex.net/tracks/$id", useProxy = true) ?: return null
        return parseYandexTrack(body)
    }

    internal fun yandexTrackId(url: String): String? =
        Regex("/track/(\\d+)(?:[/?#]|$)").find(url)?.groupValues?.get(1)

    internal fun parseYandexTrack(body: String): TrackIdentity? = runCatching {
        val track = mapper.readTree(body).path("result").let { if (it.isArray) it.firstOrNull() else it }
            ?: return null
        val title = track.path("title").asText("")
        val artist = track.path("artists").firstOrNull()?.path("name")?.asText("").orEmpty()
        if (title.isBlank()) null else TrackIdentity(artist, title)
    }.getOrNull()

    /** Top search hit, rendered as the canonical `/album/{albumId}/track/{trackId}` web URL. */
    internal fun parseYandexSearch(body: String): String? = runCatching {
        val hit = mapper.readTree(body).path("result").path("tracks").path("results").firstOrNull()
            ?: return null
        val trackId = hit.path("id").asText("").takeIf { it.isNotBlank() } ?: return null
        val albumId = hit.path("albums").firstOrNull()?.path("id")?.asText("")?.takeIf { it.isNotBlank() }
            ?: return null
        "https://music.yandex.ru/album/$albumId/track/$trackId"
    }.getOrNull()

    private fun encode(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)
}
