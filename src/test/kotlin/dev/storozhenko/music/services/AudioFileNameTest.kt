package dev.storozhenko.music.services

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Telegram shows the attached file name when it cannot read the media as audio. Uploading it as
 * "audio" with no extension is why a track appeared as "audio" instead of performer and title.
 */
class AudioFileNameTest {
    private val sender = TelegramSender(io.mockk.mockk(relaxed = true), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO))

    @Test
    fun `names the upload after the track, keeping the real extension`(@TempDir dir: File) {
        val f = File(dir, "abc_telegram_audio.m4a").apply { writeText("x") }
        assertEquals("Depeche Mode - Walking In My Shoes.m4a", sender.audioFileName("Depeche Mode", "Walking In My Shoes", f))
    }

    @Test
    fun `falls back sensibly without an artist`(@TempDir dir: File) {
        val f = File(dir, "x.mp3").apply { writeText("x") }
        assertEquals("Solo Track.mp3", sender.audioFileName("", "Solo Track", f))
    }

    @Test
    fun `strips characters that are not valid in a file name`(@TempDir dir: File) {
        val f = File(dir, "x.m4a").apply { writeText("x") }
        assertEquals("AC_DC - Back_ In Black.m4a", sender.audioFileName("AC/DC", "Back: In Black", f))
    }

    @Test
    fun `keeps a usable name when there are no tags at all`(@TempDir dir: File) {
        val f = File(dir, "x.m4a").apply { writeText("x") }
        assertEquals("audio.m4a", sender.audioFileName("", "", f))
    }

    @Test
    fun `assumes m4a when the file has no extension`(@TempDir dir: File) {
        val f = File(dir, "noext").apply { writeText("x") }
        assertEquals("A - B.m4a", sender.audioFileName("A", "B", f))
    }
}
