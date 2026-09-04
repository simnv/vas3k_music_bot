package dev.storozhenko.music.services

import dev.storozhenko.music.OdesilResponse
import org.jsoup.Jsoup
import java.net.URI

class LinkMessageBuilder {
    private val platformOrder = listOf(
        "yandex" to "Yandex.Music",
        "youtube" to "YouTube",
        // "youtubeMusic" to "YouTube Music",
        "appleMusic" to "Apple Music",
        "itunes" to "iTunes",
        "spotify" to "Spotify",
        "google" to "Google",
        "googleStore" to "Google Store",
        "soundcloud" to "SoundCloud"
    )

    fun mapOdesilResponse(odesilResponse: OdesilResponse): String {
        val odesilEntityData = odesilResponse.entitiesByUniqueId[odesilResponse.entityUniqueId]
        val title = odesilEntityData?.title ?: ""
        val artistName = odesilEntityData?.artistName ?: ""
        val platforms = platformOrder.mapNotNull { (platformId, platformName) ->
            odesilResponse.linksByPlatform[platformId]?.let { platformData -> platformName to platformData }
        }
        val songName = "$artistName - $title\n"
        return songName + platforms.joinToString(separator = " | ")
        { (platformName, platformData) -> "<a href=\"${platformData.url}\">${platformName}</a>" }
    }

    /**
     * Renders a [ResolvedTrack] in the same shape as the Odesli message: "Artist - Title" then the
     * platform links. Artist/title come from scraped pages and third-party APIs, so they are
     * escaped — an apostrophe or ampersand in a track name would otherwise break Telegram's HTML
     * parse mode and drop the whole message.
     */
    fun formatResolved(track: ResolvedTrack, linkPrefix: String = ""): String {
        val name = listOf(track.identity.artist, track.identity.title)
            .filter { it.isNotBlank() }
            .joinToString(" - ")
        val links = track.links.entries.joinToString(" | ") { (platform, url) ->
            "<a href=\"${escapeHtml(url)}\">${escapeHtml(platform)}</a>"
        }
        return "${escapeHtml(name)}\n$linkPrefix$links"
    }

    internal fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun extractFirstUrlByText(html: String, linkText: String): String? {
        val doc = Jsoup.parse(html)
        val element = doc.select("a:containsOwn($linkText)").first()
        return element?.attr("href")
    }

    /**
     * The title must stay out of the format string. Interpolating it first meant a title ending in
     * "18%" produced "…18% [%02d:%02d]", where Formatter read "% " as a conversion and threw
     * UnknownFormatConversionException, killing the whole update.
     */
    fun formatTitleWithDuration(meta: VideoMeta): String =
        meta.durationSec?.let { "${meta.title} [${"%02d:%02d".format(it / 60, it % 60)}]" } ?: meta.title

    fun stripAnnotations(s: String): String =
        s.replace(Regex("\\s*\\[[^\\]]*\\]"), "")
            .replace(Regex("\\s*\\([^)]*\\)"), "")
            .trim()
            .replace(Regex("\\s+"), " ")

    // Hosts where we know we'll do real work — either validLinks-downloadable (handled by
    // UrlValidator) or Odesli-recognized music platforms. Used to gate the early "typing" pulser
    // so random non-music URLs don't trigger a speculative chat-action.
    private val odesliKnownHosts = setOf(
        "music.youtube.com",
        "music.yandex.ru", "music.yandex.com",
        "music.apple.com", "itunes.apple.com",
        "open.spotify.com",
        "soundcloud.com",
        "deezer.com",
        "tidal.com",
        "music.amazon.com", "amazon.com",
        "pandora.com",
    )

    fun isKnownOdesliMusicUrl(url: String): Boolean = runCatching {
        val host = URI(url).host.lowercase()
        odesliKnownHosts.any { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)

    /**
     * How a message's URLs map onto the audio/video default.
     *
     * @param musicOnly a music host we can't download directly (Spotify, Apple, Deezer, ...). It is
     *   resolved through Odesli and then `ytsearch`, so the YouTube video we land on is incidental
     *   and the user asked for a song — send audio.
     * @param detectable a music host we *can* download directly (`music.youtube.com`). We fetch the
     *   exact URL posted, which may be a static art track or a real music video — let the probe decide.
     */
    data class MusicSource(val musicOnly: Boolean, val detectable: Boolean)

    fun classifyMusicSource(allUrls: List<String>, downloadableUrls: List<String>): MusicSource =
        MusicSource(
            musicOnly = downloadableUrls.isEmpty() && allUrls.any { isKnownOdesliMusicUrl(it) },
            // Mirrors the downloadUrl precedence in Bot: the first downloadable URL is the one we
            // actually fetch, so it must also be the one we classify.
            detectable = downloadableUrls.firstOrNull()?.let { isKnownOdesliMusicUrl(it) } == true,
        )

    fun isVkOrRutube(url: String): Boolean = runCatching {
        val host = URI(url).host.lowercase()
        host == "rutube.ru" || host.endsWith(".rutube.ru") ||
            host == "vk.com" || host.endsWith(".vk.com") ||
            host == "vk.ru" || host.endsWith(".vk.ru") ||
            host == "vkvideo.ru" || host.endsWith(".vkvideo.ru")
    }.getOrDefault(false)
}
