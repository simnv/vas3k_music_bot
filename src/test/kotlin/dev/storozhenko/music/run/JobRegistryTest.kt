package dev.storozhenko.music.run

import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JobRegistryTest {

    @Test
    fun `take returns the entry exactly once`() {
        val registry = JobRegistry()
        val job = Job()
        val entry = CancellableJob(job, "https://u", 1L, "chat", 42)
        registry.register("tok", entry)
        assertEquals(entry, registry.take("tok"))
        assertNull(registry.take("tok"))
        job.cancel()
    }

    @Test
    fun `remove clears the entry`() {
        val registry = JobRegistry()
        val job = Job()
        registry.register("tok", CancellableJob(job, null, 1L, "chat", 42))
        registry.remove("tok")
        assertNull(registry.take("tok"))
        job.cancel()
    }

    @Test
    fun `cancelKeyboard embeds the token in callback data`() {
        val kb = JobRegistry().cancelKeyboard("abc")
        val button = kb.keyboard.first().first()
        assertEquals("cancel:abc", button.callbackData)
        assertEquals("❌ Отмена", button.text)
    }
}
