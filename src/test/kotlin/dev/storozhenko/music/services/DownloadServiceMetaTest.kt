package dev.storozhenko.music.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * yt-dlp's stderr is merged into stdout, so the `--print` value is not necessarily the first line.
 * Reading line one blindly put "WARNING: [youtube] ...: Some web client..." in place of the video
 * title, which then became the Telegram caption and the music-search query.
 */
class DownloadServiceMetaTest {

    private val service = DownloadService(
        ytdlLocation = "yt-dlp",
        virtualDispatcher = Dispatchers.IO,
        fileDeleteScope = CoroutineScope(Dispatchers.IO),
        ipv6UrlContains = null,
        ytdlProxy = null,
        ytdlProxyUrlContains = null,
    )

    @Test
    fun `takes the printed line, not a leading warning`() {
        val lines = listOf(
            "WARNING: [youtube] o5ZuzFW0gag: Some web client https formats have been skipped",
            "Real Title\t214",
        )
        assertEquals(VideoMeta("Real Title", 214), service.parseVideoMeta(lines))
    }

    @Test
    fun `skips multiple diagnostics and extractor chatter`() {
        val lines = listOf(
            "[youtube] Extracting URL: https://youtu.be/x",
            "[youtube] x: Downloading webpage",
            "WARNING: something happened",
            "ERROR: not really fatal",
            "Artist - Song\t95",
        )
        assertEquals(VideoMeta("Artist - Song", 95), service.parseVideoMeta(lines))
    }

    @Test
    fun `plain single line still works`() {
        assertEquals(VideoMeta("Title", 60), service.parseVideoMeta(listOf("Title\t60")))
    }

    @Test
    fun `unknown duration yields a null duration, keeping the title`() {
        assertEquals(VideoMeta("Title", null), service.parseVideoMeta(listOf("Title\tNA")))
    }

    @Test
    fun `no video formats is reported as empty regardless of position`() {
        val lines = listOf("[youtube] x: Downloading", "ERROR: [youtube] x: No video formats found!")
        assertEquals(VideoMeta("", null), service.parseVideoMeta(lines))
    }

    @Test
    fun `a title containing a tab does not swallow the duration`() {
        // limit=2 keeps everything after the first tab as the duration field, which then fails to
        // parse and degrades to null rather than corrupting the title.
        assertEquals("Odd", service.parseVideoMeta(listOf("Odd\tTitle\t42")).title)
    }

    @Test
    fun `empty or diagnostic-only output degrades to blank`() {
        assertEquals(VideoMeta("", null), service.parseVideoMeta(emptyList()))
        assertEquals(VideoMeta("", null), service.parseVideoMeta(listOf("WARNING: only noise")))
        assertEquals(VideoMeta("", null), service.parseVideoMeta(listOf("")))
    }
}
