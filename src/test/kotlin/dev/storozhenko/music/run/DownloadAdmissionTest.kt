package dev.storozhenko.music.run

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadAdmissionTest {

    @Test
    fun `per-chat limit serializes jobs in one chat`() = runTest {
        val admission = DownloadAdmission(globalLimit = 10, perChatLimit = 1)
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = launch {
            admission.withSlot(1L) { order.add("first-start"); gate.await(); order.add("first-end") }
        }
        val second = launch { admission.withSlot(1L) { order.add("second-start") } }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("first-start"), order)
        gate.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf("first-start", "first-end", "second-start"), order)
    }

    @Test
    fun `different chats do not block each other`() = runTest {
        val admission = DownloadAdmission(globalLimit = 10, perChatLimit = 1)
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<Long>()
        launch { admission.withSlot(1L) { started.add(1L); gate.await() } }
        launch { admission.withSlot(2L) { started.add(2L); gate.await() } }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(1L, 2L), started)
        gate.complete(Unit)
    }

    @Test
    fun `global limit caps concurrency across chats`() = runTest {
        val admission = DownloadAdmission(globalLimit = 2, perChatLimit = 5)
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<Long>()
        (1L..3L).forEach { id -> launch { admission.withSlot(id) { started.add(id); gate.await() } } }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(1L, 2L), started)
        gate.complete(Unit)
    }

    @Test
    fun `hasFreeSlot reflects saturation and release`() = runTest {
        val admission = DownloadAdmission(globalLimit = 1, perChatLimit = 1)
        assertTrue(admission.hasFreeSlot(1L))
        val gate = CompletableDeferred<Unit>()
        launch { admission.withSlot(1L) { gate.await() } }
        testScheduler.advanceUntilIdle()
        assertFalse(admission.hasFreeSlot(1L))
        assertFalse(admission.hasFreeSlot(2L)) // global slot exhausted
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(admission.hasFreeSlot(1L))
    }
}
