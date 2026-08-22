package dev.storozhenko.music.services

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Odesli's keyless tier now returns 401 PUBLIC_API_ACCESS_DEPRECATED, so the service stays switched
 * off unless a key is configured. Without this gate every music link would spend a doomed HTTP
 * round trip inside the admission slot before falling back to [MusicResolver].
 */
class OdesilServiceTest {

    @Test
    fun `disabled without an api key`() {
        assertFalse(OdesilService().enabled)
        assertFalse(OdesilService(apiKey = "").enabled)
        assertFalse(OdesilService(apiKey = "   ").enabled)
    }

    @Test
    fun `enabled once a key is configured`() {
        assertTrue(OdesilService(apiKey = "abc123").enabled)
    }

    @Test
    fun `detect short-circuits to null when disabled, making no request`() = runTest {
        // No network stub is needed precisely because no request may be attempted.
        assertNull(OdesilService().detect("https://open.spotify.com/track/123"))
    }
}
