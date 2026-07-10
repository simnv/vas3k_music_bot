package dev.storozhenko.music.run

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JobMarkerStoreTest {

    @Test
    fun `write and list roundtrip`(@TempDir dir: File) {
        val store = JobMarkerStore(dir)
        val marker = JobMarkerStore.JobMarker(5L, 42, "https://u", 1000L)
        store.write("tok", marker)
        assertEquals(mapOf("tok" to marker), store.listAll())
    }

    @Test
    fun `delete removes the marker`(@TempDir dir: File) {
        val store = JobMarkerStore(dir)
        store.write("tok", JobMarkerStore.JobMarker(1L, 2, null, 3L))
        store.delete("tok")
        assertTrue(store.listAll().isEmpty())
    }

    @Test
    fun `corrupt marker files are skipped`(@TempDir dir: File) {
        val store = JobMarkerStore(dir)
        File(dir, "bad.json").writeText("{not json")
        store.write("good", JobMarkerStore.JobMarker(1L, 2, null, 3L))
        assertEquals(setOf("good"), store.listAll().keys)
    }

    @Test
    fun `falls back to tmpdir when base dir is unusable`(@TempDir dir: File) {
        val notADir = File(dir, "occupied").apply { writeText("x") }
        val store = JobMarkerStore(notADir)
        assertTrue(store.dir.absolutePath.startsWith(File(System.getProperty("java.io.tmpdir")).absolutePath))
    }
}
