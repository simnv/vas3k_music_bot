package dev.storozhenko.music.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `categories` from the `--write-info-json` sidecar is what lets us spot a song behind a plain
 * youtube.com link — a still image with audio on an ordinary channel, which no host or aspect-ratio
 * check can distinguish from a real video.
 */
class DownloadServiceCategoriesTest {

    private val service = DownloadService(
        ytdlLocation = "yt-dlp",
        virtualDispatcher = Dispatchers.IO,
        fileDeleteScope = CoroutineScope(Dispatchers.IO),
        ipv6UrlContains = null,
        ytdlProxy = null,
        ytdlProxyUrlContains = null,
    )

    private fun media(dir: File) = File(dir, "abc123.mp4").apply { writeText("video-bytes") }

    private fun sidecar(dir: File, json: String) =
        File(dir, "abc123.info.json").apply { writeText(json) }

    @Test
    fun `reads categories from the sidecar`(@TempDir dir: File) {
        val file = media(dir)
        sidecar(dir, """{"id":"abc123","categories":["Music"],"duration":183}""")
        assertEquals(listOf("Music"), service.readCategories(file))
    }

    @Test
    fun `a non-music category is reported as-is`(@TempDir dir: File) {
        val file = media(dir)
        sidecar(dir, """{"categories":["Film & Animation"]}""")
        assertEquals(listOf("Film & Animation"), service.readCategories(file))
    }

    @Test
    fun `missing sidecar yields unknown rather than not-music`(@TempDir dir: File) {
        assertTrue(service.readCategories(media(dir)).isEmpty())
        assertNull(service.resolveSiblingInfoJson(media(dir)))
    }

    @Test
    fun `malformed or null-valued json degrades to empty`(@TempDir dir: File) {
        val file = media(dir)
        sidecar(dir, "{not valid json")
        assertTrue(service.readCategories(file).isEmpty())

        sidecar(dir, """{"categories":null}""")
        assertTrue(service.readCategories(file).isEmpty())

        sidecar(dir, """{"categories":[null,"Music"]}""")
        assertEquals(listOf("Music"), service.readCategories(file))
    }

    @Test
    fun `sidecar is located next to the media file`(@TempDir dir: File) {
        val file = media(dir)
        val info = sidecar(dir, """{"categories":[]}""")
        assertEquals(info, service.resolveSiblingInfoJson(file))
    }

    @Test
    fun `an object-valued categories field is not mistaken for a list`(@TempDir dir: File) {
        val file = media(dir)
        // Iterating an object's children would wrongly yield ["Music"].
        sidecar(dir, """{"categories":{"primary":"Music"}}""")
        assertTrue(service.readCategories(file).isEmpty())
        sidecar(dir, """{"categories":"Music"}""")
        assertTrue(service.readCategories(file).isEmpty())
    }

    @Test
    fun `the info json sidecar is never selected as the media file`(@TempDir dir: File) {
        val mp4 = media(dir)
        val info = sidecar(dir, """{"categories":["Music"]}""")
        val jpg = File(dir, "abc123.jpg").apply { writeText("thumb") }
        // ".info.json" sorts before ".mp4", so a naive first-non-image pick returns the sidecar.
        assertEquals(mp4, service.selectMediaFile(arrayOf(info, jpg, mp4)))
        assertEquals(mp4, service.selectMediaFile(arrayOf(mp4, info, jpg)))
    }

    @Test
    fun `no media file among sidecars alone`(@TempDir dir: File) {
        val info = sidecar(dir, "{}")
        val jpg = File(dir, "abc123.jpg").apply { writeText("thumb") }
        assertNull(service.selectMediaFile(arrayOf(info, jpg)))
    }
}
