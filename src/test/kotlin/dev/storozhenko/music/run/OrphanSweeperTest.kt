package dev.storozhenko.music.run

import dev.storozhenko.music.services.TelegramSender
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class OrphanSweeperTest {

    @Test
    fun `sweep notifies each orphan and clears markers`(@TempDir dir: File) = runTest {
        val store = JobMarkerStore(dir)
        store.write("tok", JobMarkerStore.JobMarker(5L, 42, "https://u", 0L))
        val sender = mockk<TelegramSender>(relaxed = true)

        OrphanSweeper(store, sender).sweep()

        coVerify { sender.editMessageText(eq(5L), eq(42), match { it.startsWith("❌") }, any()) }
        assertTrue(store.listAll().isEmpty())
    }

    @Test
    fun `sweep with no markers touches nothing`(@TempDir dir: File) = runTest {
        val sender = mockk<TelegramSender>(relaxed = true)
        OrphanSweeper(JobMarkerStore(dir), sender).sweep()
        coVerify(exactly = 0) { sender.editMessageText(any(), any(), any(), any()) }
    }

    @Test
    fun `sweep continues past a failing edit and clears all markers`(@TempDir dir: File) = runTest {
        val store = JobMarkerStore(dir)
        store.write("tok1", JobMarkerStore.JobMarker(1L, 1, null, 0L))
        store.write("tok2", JobMarkerStore.JobMarker(2L, 2, null, 0L))
        val sender = mockk<TelegramSender>(relaxed = true)
        coEvery { sender.editMessageText(eq(1L), any(), any(), any()) } throws RuntimeException("permission denied")

        OrphanSweeper(store, sender).sweep()

        coVerify { sender.editMessageText(eq(2L), eq(2), any(), any()) }
        assertTrue(store.listAll().isEmpty())
    }
}
