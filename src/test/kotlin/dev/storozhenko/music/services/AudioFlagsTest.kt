package dev.storozhenko.music.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * YouTube can offer no audio-only stream at all. Without a combined-format fallback and an
 * extraction step, the audio selector matches nothing and every audio request fails, while video
 * keeps working off the same combined format.
 */
class AudioFlagsTest {
    private val service = DownloadService(
        ytdlLocation = "yt-dlp",
        virtualDispatcher = Dispatchers.IO,
        fileDeleteScope = CoroutineScope(Dispatchers.IO),
        ipv6UrlContains = null,
        ytdlProxy = null,
        ytdlProxyUrlContains = null,
    )

    @Test
    fun `audio selector falls back to a combined format`() {
        val selector = service.audioFlags("https://youtu.be/x").let { it[it.indexOf("-f") + 1] }
        assertTrue(selector.endsWith("/best"), "no combined-format fallback: $selector")
        assertTrue(selector.startsWith("bestaudio[ext=m4a]"), "audio-only must still be preferred: $selector")
    }

    @Test
    fun `audio flags extract to an audio container`() {
        val flags = service.audioFlags("https://youtu.be/x")
        assertTrue(flags.contains("-x"), "a combined format would otherwise be sent as video")
        assertTrue(flags.windowed(2).contains(listOf("--audio-format", "m4a")))
    }

    @Test
    fun `video flags are unchanged and keep their own fallback`() {
        val flags = service.videoFlags("https://youtu.be/x", "sel")
        assertTrue(flags.contains("--merge-output-format"))
        assertTrue(!flags.contains("-x"), "video must not be stripped to audio")
    }
}
