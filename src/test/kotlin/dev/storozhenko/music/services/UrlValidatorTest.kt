package dev.storozhenko.music.services

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UrlValidatorTest {
    private val validator = UrlValidator()

    @Test
    fun `youtube playlists are not downloadable`() {
        // yt-dlp used to take item 1, so an album link returned one arbitrary track.
        assertFalse(validator.isValidDownloadUrl("https://music.youtube.com/playlist?list=OLAK5uy_abc"))
        assertFalse(validator.isValidDownloadUrl("https://www.youtube.com/playlist?list=PL123"))
        // A watch URL carrying a list is still a single video.
        assertTrue(validator.isValidDownloadUrl("https://www.youtube.com/watch?v=abc&list=PL123"))
    }

    @Test
    fun `accepts known video hosts`() {
        assertTrue(validator.isValidDownloadUrl("https://www.youtube.com/watch?v=abc"))
        assertTrue(validator.isValidDownloadUrl("https://youtu.be/abc"))
        assertTrue(validator.isValidDownloadUrl("https://www.youtube.com/shorts/abc"))
        assertTrue(validator.isValidDownloadUrl("https://vt.tiktok.com/ZS123/"))
        assertTrue(validator.isValidDownloadUrl("https://vk.com/video-123_456"))
        assertTrue(validator.isValidDownloadUrl("https://vkvideo.ru/video-123_456"))
        assertTrue(validator.isValidDownloadUrl("https://rutube.ru/video/abc123/"))
    }

    @Test
    fun `rejects unknown hosts and blocked paths`() {
        assertFalse(validator.isValidDownloadUrl("https://example.com/watch?v=abc"))
        assertFalse(validator.isValidDownloadUrl("https://www.youtube.com/post/xyz"))
        assertFalse(validator.isValidDownloadUrl("https://vk.com/wall-123_456"))
    }

    @Test
    fun `rejects garbage input`() {
        assertFalse(validator.isValidDownloadUrl("not a url"))
        assertFalse(validator.isValidDownloadUrl(""))
        assertFalse(validator.isValidDownloadUrl("youtube.com/watch?v=abc")) // schemeless
    }

    @Test
    fun `rejects non-http schemes`() {
        assertFalse(validator.isValidDownloadUrl("ftp://youtube.com/watch?v=x"))
        assertFalse(validator.isValidDownloadUrl("javascript:alert(1)"))
    }

    @Test
    fun `rejects urls with userinfo`() {
        assertFalse(validator.isValidDownloadUrl("https://user@youtube.com/watch?v=x"))
        assertFalse(validator.isValidDownloadUrl("https://user:pass@youtube.com/watch?v=x"))
    }

    @Test
    fun `rejects lookalike and literal-ip hosts`() {
        assertFalse(validator.isValidDownloadUrl("https://youtube.com.evil.com/watch?v=x"))
        assertFalse(validator.isValidDownloadUrl("https://142.250.68.14/watch?v=x"))
    }
}
