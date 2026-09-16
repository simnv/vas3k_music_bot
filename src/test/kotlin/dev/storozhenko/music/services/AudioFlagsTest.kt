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
    fun `the preferred selector asks only for audio-only streams`() {
        val flags = service.audioFlags("https://youtu.be/x")
        val selector = flags[flags.indexOf("-f") + 1]
        assertTrue(selector.startsWith("bestaudio[ext=m4a]"), "audio-only must be preferred: $selector")
        // No /best here: a combined format would pull the whole video on the happy path.
        assertTrue(!selector.contains("/best\"") && !selector.endsWith("/best"), "unexpected fallback: $selector")
        assertTrue(!flags.contains("-x"), "nothing to extract from an audio-only stream")
    }

    @Test
    fun `the fallback takes audio out of a capped combined format`() {
        val flags = service.audioFallbackFlags("https://youtu.be/x")
        val selector = flags[flags.indexOf("-f") + 1]
        // Every rendition carries the same audio, so the 1080p one would be a wasted download.
        assertTrue(selector.contains("height<=480"), "fallback must stay small: $selector")
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
