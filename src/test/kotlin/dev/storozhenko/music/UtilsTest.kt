package dev.storozhenko.music

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UtilsTest {

    // parseRequestOptions
    @Test
    fun `defaults to high quality video`() {
        assertEquals(RequestOptions(Quality.HIGH, false), parseRequestOptions("https://youtu.be/x"))
    }

    @Test
    fun `parses quality keywords anywhere in message`() {
        assertEquals(Quality.LOW, parseRequestOptions("https://youtu.be/x low").quality)
        assertEquals(Quality.MEDIUM, parseRequestOptions("med https://youtu.be/x").quality)
        assertEquals(Quality.HIGH, parseRequestOptions("https://youtu.be/x hi").quality)
    }

    @Test
    fun `single letter shortcuts only at message boundaries`() {
        assertEquals(Quality.LOW, parseRequestOptions("https://youtu.be/x l").quality)
        assertEquals(Quality.MEDIUM, parseRequestOptions("m https://youtu.be/x").quality)
        assertEquals(Quality.HIGH, parseRequestOptions("watch h this https://youtu.be/x").quality)
    }

    @Test
    fun `audio keywords force audio`() {
        assertTrue(parseRequestOptions("https://youtu.be/x audio").forceAudio)
        assertTrue(parseRequestOptions("a https://youtu.be/x").forceAudio)
        assertFalse(parseRequestOptions("watch a video https://youtu.be/x").forceAudio)
        assertTrue(parseRequestOptions("watch au this https://youtu.be/x").forceAudio)
        assertTrue(parseRequestOptions("give sound please https://youtu.be/x").forceAudio)
        assertTrue(parseRequestOptions("some snd here https://youtu.be/x").forceAudio)
        assertTrue(parseRequestOptions("s https://youtu.be/x").forceAudio)
    }

    @Test
    fun `empty text gives defaults`() {
        assertEquals(RequestOptions(Quality.HIGH, false), parseRequestOptions(""))
    }

    @Test
    fun `video keywords force video`() {
        assertTrue(parseRequestOptions("https://open.spotify.com/track/x video").forceVideo)
        assertTrue(parseRequestOptions("vid https://open.spotify.com/track/x").forceVideo)
        assertTrue(parseRequestOptions("https://open.spotify.com/track/x v").forceVideo)
        assertTrue(parseRequestOptions("v https://open.spotify.com/track/x").forceVideo)
    }

    @Test
    fun `single letter v only at message boundaries`() {
        assertFalse(parseRequestOptions("watch v this https://open.spotify.com/track/x").forceVideo)
    }

    @Test
    fun `no video keyword means no forceVideo`() {
        assertFalse(parseRequestOptions("https://open.spotify.com/track/x").forceVideo)
        assertFalse(parseRequestOptions("https://open.spotify.com/track/x audio").forceVideo)
    }

    @Test
    fun `qualityExplicit tracks whether a quality word was typed`() {
        assertFalse(parseRequestOptions("https://youtu.be/x").qualityExplicit)
        // HIGH is the default, so only an explicit token distinguishes it
        assertTrue(parseRequestOptions("https://youtu.be/x high").qualityExplicit)
        assertTrue(parseRequestOptions("https://youtu.be/x low").qualityExplicit)
        assertTrue(parseRequestOptions("m https://youtu.be/x").qualityExplicit)
        assertFalse(parseRequestOptions("watch h this https://youtu.be/x").qualityExplicit)
    }

    // shouldForceAudio precedence
    @Test
    fun `explicit audio wins over everything`() {
        assertTrue(shouldForceAudio(parseRequestOptions("https://youtu.be/x audio"), musicOnlySource = false))
        assertTrue(shouldForceAudio(parseRequestOptions("audio https://youtu.be/x high"), musicOnlySource = false))
        assertTrue(shouldForceAudio(parseRequestOptions("audio https://youtu.be/x video"), musicOnlySource = true))
    }

    @Test
    fun `explicit video overrides the music-host audio default`() {
        assertFalse(shouldForceAudio(parseRequestOptions("https://open.spotify.com/track/x video"), musicOnlySource = true))
    }

    @Test
    fun `explicit quality overrides the music-host audio default`() {
        assertFalse(shouldForceAudio(parseRequestOptions("https://open.spotify.com/track/x high"), musicOnlySource = true))
        assertFalse(shouldForceAudio(parseRequestOptions("low https://open.spotify.com/track/x"), musicOnlySource = true))
    }

    @Test
    fun `music-only source defaults to audio`() {
        assertTrue(shouldForceAudio(parseRequestOptions("https://open.spotify.com/track/x"), musicOnlySource = true))
    }

    @Test
    fun `non music source defaults to video`() {
        assertFalse(shouldForceAudio(parseRequestOptions("https://youtu.be/x"), musicOnlySource = false))
        assertFalse(shouldForceAudio(parseRequestOptions("https://youtu.be/x high"), musicOnlySource = false))
    }

    // split2ByDash
    @Test
    fun `splits artist and title on first dash`() {
        assertEquals("Artist" to "Title", "Artist - Title".split2ByDash())
        assertEquals("A" to "B - C", "A - B - C".split2ByDash())
    }

    @Test
    fun `single part goes to title when reverseIfSingle`() {
        assertEquals("" to "OnlyTitle", "OnlyTitle".split2ByDash(true))
        assertEquals("OnlyTitle" to "", "OnlyTitle".split2ByDash(false))
    }

    // removeFirstLine
    @Test
    fun `removeFirstLine drops the first line`() {
        assertEquals("b\nc", "a\nb\nc".removeFirstLine())
        assertEquals("", "single".removeFirstLine())
    }

    // shellQuote
    @Test
    fun `shellQuote quotes only when needed`() {
        assertEquals("abc_123", "abc_123".shellQuote())
        assertEquals("'a b'", "a b".shellQuote())
        assertEquals("''", "".shellQuote())
        assertEquals("'it'\\''s'", "it's".shellQuote())
    }

    // validateVideoFile
    @Test
    fun `rejects missing empty and unsupported video files`(@TempDir dir: File) {
        assertFalse(validateVideoFile(File(dir, "nope.mp4")).first)
        assertFalse(validateVideoFile(File(dir, "v.mp4").apply { createNewFile() }).first)
        assertFalse(validateVideoFile(File(dir, "v.mkv").apply { writeText("data") }).first)
    }

    @Test
    fun `accepts mp4 with content`(@TempDir dir: File) {
        assertTrue(validateVideoFile(File(dir, "v.mp4").apply { writeText("data") }).first)
        assertTrue(validateVideoFile(File(dir, "v.mov").apply { writeText("data") }).first)
        assertTrue(validateVideoFile(File(dir, "v.avi").apply { writeText("data") }).first)
    }

    // validateThumbnailFile
    @Test
    fun `null thumbnail is valid, oversized is not`(@TempDir dir: File) {
        assertTrue(validateThumbnailFile(null).first)
        val big = File(dir, "t.jpg").apply { writeBytes(ByteArray(201 * 1024)) }
        assertFalse(validateThumbnailFile(big).first)
        val ok = File(dir, "ok.jpg").apply { writeText("x") }
        assertTrue(validateThumbnailFile(ok).first)
    }

    @Test
    fun `accepts png and jpeg thumbnails`(@TempDir dir: File) {
        assertTrue(validateThumbnailFile(File(dir, "t.png").apply { writeText("x") }).first)
        assertTrue(validateThumbnailFile(File(dir, "t.jpeg").apply { writeText("x") }).first)
    }

    @Test
    fun `rejects missing and empty thumbnails`(@TempDir dir: File) {
        assertFalse(validateThumbnailFile(File(dir, "nope.jpg")).first)
        assertFalse(validateThumbnailFile(File(dir, "empty.jpg").apply { createNewFile() }).first)
    }
}
