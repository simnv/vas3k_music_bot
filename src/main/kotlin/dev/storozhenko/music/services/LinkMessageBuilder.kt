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

    fun extractFirstUrlByText(html: String, linkText: String): String? {
        val doc = Jsoup.parse(html)
        val element = doc.select("a:containsOwn($linkText)").first()
        return element?.attr("href")
    }

    fun formatTitleWithDuration(meta: VideoMeta): String =
        meta.durationSec?.let { "${meta.title} [%02d:%02d]".format(it / 60, it % 60) } ?: meta.title

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

    fun isVkOrRutube(url: String): Boolean = runCatching {
        val host = URI(url).host.lowercase()
        host == "rutube.ru" || host.endsWith(".rutube.ru") ||
            host == "vk.com" || host.endsWith(".vk.com") ||
            host == "vk.ru" || host.endsWith(".vk.ru") ||
            host == "vkvideo.ru" || host.endsWith(".vkvideo.ru")
    }.getOrDefault(false)
}
