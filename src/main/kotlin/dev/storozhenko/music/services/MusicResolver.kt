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
    private val appleStorefront: String = "gb",
) {
    private val logger = getLogger()
    private val mapper = ObjectMapper()

    suspend fun resolve(sourceUrl: String): ResolvedTrack? =
        identify(sourceUrl)?.let { resolveFor(it, sourceUrl) }

    /**
     * Stage two on its own, for sources we can already name without inspecting their page — a
     * YouTube link in a music chat, where yt-dlp has given us the title.
     */
    suspend fun resolveFor(identity: TrackIdentity, sourceUrl: String): ResolvedTrack? {
        val query = identity.query
        if (query.isBlank()) return null

        val results = coroutineScope {
            // Seed each platform with the posted URL rather than searching for what we were given.
            val apple = async {
                safe { sourceIfHost(sourceUrl, "apple.com")?.let { appleSourceInOurStorefront(it) } ?: searchApple(query) }
            }
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

    // ---- albums ------------------------------------------------------------

    /**
     * Albums are linked, never downloaded: the user shared a record, not a file, and pulling every
     * track would dump a dozen uploads into the chat.
     *
     * Returns null for anything that is not an album URL, so callers can fall through to the track
     * path. [ResolvedTrack.youtubeUrl] is always null here, which is what stops the download.
     */
    suspend fun resolveAlbum(sourceUrl: String): ResolvedTrack? {
        if (!isAlbumUrl(sourceUrl)) return null
        // Failing to name the album is no reason to answer with nothing: the posted link is still a
        // perfectly good link, so fall back to echoing it rather than reporting a failure.
        val identity = identifyAlbum(sourceUrl)?.takeIf { it.query.isNotBlank() }
            ?: return sourceOnly(sourceUrl)

        val results = coroutineScope {
            val apple = async {
                safe { sourceIfHost(sourceUrl, "apple.com")?.let { appleSourceInOurStorefront(it) } ?: searchAppleAlbum(identity.query) }
            }
            val yandex = async {
                safe { sourceIfHost(sourceUrl, "yandex.ru", "yandex.com") ?: searchYandexAlbum(identity.query) }
            }
            val spot = async { safe { sourceIfHost(sourceUrl, "spotify.com") ?: spotify?.searchAlbumUrl(identity.query) } }
            listOf(apple.await(), yandex.await(), spot.await())
        }
        val (apple, yandex, spotifyUrl) = results

        val links = linkedMapOf<String, String>()
        yandex?.let { links["Yandex.Music"] = it }
        apple?.let { links["Apple Music"] = it }
        spotifyUrl?.let { links["Spotify"] = it }
        if (links.isEmpty()) return sourceOnly(sourceUrl)
        return ResolvedTrack(identity, links, youtubeUrl = null)
    }

    /** Last resort: the link we were given, labelled with its own service. */
    private fun sourceOnly(sourceUrl: String): ResolvedTrack? {
        val service = serviceName(sourceUrl) ?: return null
        return ResolvedTrack(TrackIdentity("", ""), linkedMapOf(service to sourceUrl), youtubeUrl = null)
    }

    internal fun serviceName(url: String): String? = runCatching {
        val host = URI(url).host?.lowercase() ?: return null
        when {
            hostMatches(host, "apple.com") -> "Apple Music"
            hostMatches(host, "yandex.ru") || hostMatches(host, "yandex.com") -> "Yandex.Music"
            hostMatches(host, "spotify.com") -> "Spotify"
            else -> null
        }
    }.getOrNull()

    /** An album URL is one that names an album but no track within it. */
    internal fun isAlbumUrl(url: String): Boolean = albumRef(url) != null

    /**
     * A playlist is somebody's arbitrary selection, not a release, so there is no equivalent to
     * look up on the other services. Detected only to answer clearly instead of reporting that a
     * track could not be found.
     */
    fun isPlaylistUrl(url: String): Boolean = runCatching {
        val host = URI(url).host?.lowercase() ?: return false
        when {
            hostMatches(host, "spotify.com") -> url.contains("/playlist/")
            hostMatches(host, "yandex.ru") || hostMatches(host, "yandex.com") -> url.contains("/playlists/")
            hostMatches(host, "apple.com") -> url.contains("/playlist/")
            else -> false
        }
    }.getOrDefault(false)

    private data class AlbumRef(val service: String, val id: String)

    private fun albumRef(url: String): AlbumRef? {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return null
        return when {
            (hostMatches(host, "yandex.ru") || hostMatches(host, "yandex.com")) && yandexTrackId(url) == null ->
                Regex("/album/(\\d+)").find(url)?.groupValues?.get(1)?.let { AlbumRef("yandex", it) }
            hostMatches(host, "apple.com") && appleTrackId(url) == null ->
                appleAlbumId(url)?.let { AlbumRef("apple", it) }
            hostMatches(host, "spotify.com") ->
                Regex("/album/([A-Za-z0-9]{22})(?:[/?#]|$)").find(url)?.groupValues?.get(1)
                    ?.let { AlbumRef("spotify", it) }
            else -> null
        }
    }

    private suspend fun identifyAlbum(url: String): TrackIdentity? {
        val ref = albumRef(url) ?: return null
        return when (ref.service) {
            "yandex" -> web.get("https://api.music.yandex.net/albums/${ref.id}", useProxy = true)
                ?.let { parseYandexAlbum(it) }
            "apple" -> appleIdLookup(url, ref.id, "album")?.let { parseItunesAlbum(it) }
            "spotify" -> spotify?.albumIdentity(ref.id)
            else -> null
        }
    }

    internal fun parseYandexAlbum(body: String): TrackIdentity? = runCatching {
        val album = mapper.readTree(body).path("result").let { if (it.isArray) it.firstOrNull() else it }
            ?: return null
        val title = album.path("title").asText("")
        val artist = album.path("artists").firstOrNull()?.path("name")?.asText("").orEmpty()
        if (title.isBlank()) null else TrackIdentity(artist, title)
    }.getOrNull()

    internal fun parseItunesAlbum(body: String): TrackIdentity? = runCatching {
        val first = mapper.readTree(body).path("results").firstOrNull() ?: return null
        val title = first.path("collectionName").asText("")
        val artist = first.path("artistName").asText("")
        if (title.isBlank()) null else TrackIdentity(artist, title)
    }.getOrNull()

    internal fun parseYandexAlbumSearch(body: String): String? = runCatching {
        val hit = mapper.readTree(body).path("result").path("albums").path("results").firstOrNull()
            ?: return null
        hit.path("id").asText("").takeIf { it.isNotBlank() }?.let { "https://music.yandex.ru/album/$it" }
    }.getOrNull()

    private suspend fun searchAppleAlbum(query: String): String? {
        val term = encode(query)
        val local = web.get(
            "https://itunes.apple.com/search?term=$term&entity=album&limit=1&country=$appleStorefront"
        )?.let { parseItunesAlbumUrl(it) }
        if (local != null) return local
        return web.get("https://itunes.apple.com/search?term=$term&entity=album&limit=1")
            ?.let { parseItunesAlbumUrl(it) }
    }

    internal fun parseItunesAlbumUrl(body: String): String? = runCatching {
        mapper.readTree(body).path("results").firstOrNull()
            ?.path("collectionViewUrl")?.asText("")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private suspend fun searchYandexAlbum(query: String): String? =
        web.get("https://api.music.yandex.net/search?text=${encode(query)}&type=album&page=0", useProxy = true)
            ?.let { parseYandexAlbumSearch(it) }

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

    /**
     * Collection id from `.../album/<slug>/<id>` or `.../album/<id>`.
     *
     * The id must be a whole path segment. An unanchored pattern with an optional trailing slash
     * backtracks into a numeric slug — `/album/1999` yielded the id `9` — and would then look up
     * an unrelated release.
     */
    internal fun appleAlbumId(url: String): String? =
        Regex("/album/(?:[^/]+/)?(\\d+)(?:[/?#]|$)").find(url)?.groupValues?.get(1)

    private suspend fun appleLookup(url: String): TrackIdentity? {
        val id = appleTrackId(url) ?: return ogIdentity(url, bulletArtist = false)
        return appleIdLookup(url, id, entity = null)?.let { parseItunes(it) }
    }

    /** The storefront segment of an Apple URL, e.g. `dk` in music.apple.com/dk/album/... */
    internal fun appleUrlStorefront(url: String): String? =
        Regex("//music\\.apple\\.com/([a-z]{2})/", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.lowercase()

    internal fun withAppleStorefront(url: String, storefront: String): String =
        url.replace(Regex("(//music\\.apple\\.com/)[a-z]{2}/", RegexOption.IGNORE_CASE), "$1$storefront/")

    /**
     * Rewrites a posted Apple link into the configured storefront, so every Apple link the bot
     * emits points at the same store rather than whichever one the sender happened to use.
     *
     * The rewrite is confirmed by an id lookup first: a release present in the sender's store is
     * not necessarily licensed in ours, and pointing at a store that lacks it would be worse than
     * leaving the original link alone.
     */
    private suspend fun appleSourceInOurStorefront(sourceUrl: String): String {
        if (appleUrlStorefront(sourceUrl) == appleStorefront) return sourceUrl
        val id = appleTrackId(sourceUrl) ?: appleAlbumId(sourceUrl) ?: return sourceUrl
        val available = web.get("https://itunes.apple.com/lookup?id=$id&country=$appleStorefront")
            ?.let { hasResults(it) } == true
        return if (available) withAppleStorefront(sourceUrl, appleStorefront) else sourceUrl
    }

    /**
     * Looks an id up in the storefront the link came from before falling back to the default
     * catalogue. A release can be absent from the US store while existing in the one the user
     * linked — `/dk/album/half-told-tales` returns nothing without `country=dk`, which made the bot
     * claim it could not find an album that was right there in the URL.
     */
    private suspend fun appleIdLookup(sourceUrl: String, id: String, entity: String?): String? {
        val suffix = entity?.let { "&entity=$it" }.orEmpty()
        appleUrlStorefront(sourceUrl)?.let { store ->
            web.get("https://itunes.apple.com/lookup?id=$id$suffix&country=$store")
                ?.takeIf { hasResults(it) }
                ?.let { return it }
        }
        return web.get("https://itunes.apple.com/lookup?id=$id$suffix")?.takeIf { hasResults(it) }
    }

    private fun hasResults(body: String): Boolean =
        runCatching { mapper.readTree(body).path("resultCount").asInt(0) > 0 }.getOrDefault(false)

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
     * Apple has no storefront-neutral link — an URL without a country segment just 301s to `/us/`,
     * so a storefront must be chosen. Search the [appleStorefront] first so links open there, and
     * fall back to the default (US) catalogue only when it does not carry the track.
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
