package com.tsunaguba.corechat.data.ai

import com.tsunaguba.corechat.domain.model.UnavailableReason
import java.net.UnknownHostException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudGeminiEngineTest {

    // Any value at or above MIN_PLAUSIBLE_KEY_LENGTH so the length heuristic
    // doesn't short-circuit the probe.
    private val validLengthKey = "A".repeat(CloudGeminiEngine.MIN_PLAUSIBLE_KEY_LENGTH + 10)

    @Test
    fun `blank api key reports unavailable with ApiKeyMissing reason`() = runTest {
        var probeCalls = 0
        val engine = CloudGeminiEngine(
            apiKey = "",
            probe = { probeCalls++ },
        )
        assertFalse(engine.isAvailable())
        assertEquals(0, probeCalls)
        assertEquals(UnavailableReason.ApiKeyMissing, engine.lastUnavailableReason)
    }

    @Test
    fun `short api key reports ApiKeyMalformed without invoking probe`() = runTest {
        var probeCalls = 0
        val engine = CloudGeminiEngine(
            apiKey = "short",
            probe = { probeCalls++ },
        )
        assertFalse(engine.isAvailable())
        assertEquals(0, probeCalls)
        assertEquals(UnavailableReason.ApiKeyMalformed, engine.lastUnavailableReason)
    }

    @Test
    fun `probe success yields available and clears reason`() = runTest {
        val engine = CloudGeminiEngine(
            apiKey = validLengthKey,
            probe = { /* pretend the countTokens call succeeded */ },
        )
        assertTrue(engine.isAvailable())
        assertNull(engine.lastUnavailableReason)
    }

    @Test
    fun `probe 401 message yields ApiKeyRejected`() = runTest {
        val engine = CloudGeminiEngine(
            apiKey = validLengthKey,
            probe = { throw IllegalStateException("401 Unauthorized") },
        )
        assertFalse(engine.isAvailable())
        assertEquals(UnavailableReason.ApiKeyRejected, engine.lastUnavailableReason)
    }

    @Test
    fun `probe UnknownHostException yields NetworkUnreachable`() = runTest {
        val engine = CloudGeminiEngine(
            apiKey = validLengthKey,
            probe = { throw UnknownHostException("dns failed") },
        )
        assertFalse(engine.isAvailable())
        assertEquals(UnavailableReason.NetworkUnreachable, engine.lastUnavailableReason)
    }

    @Test
    fun `probe timeout yields ProbeTimeout reason`() = runTest {
        val engine = CloudGeminiEngine(
            apiKey = validLengthKey,
            probe = {
                // Exceeds CLOUD_PROBE_TIMEOUT_MS. Under the virtual test clock
                // runTest auto-advances so this resolves instantly.
                delay(CloudGeminiEngine.CLOUD_PROBE_TIMEOUT_MS + 1_000L)
            },
        )
        assertFalse(engine.isAvailable())
        assertEquals(UnavailableReason.ProbeTimeout, engine.lastUnavailableReason)
    }

    @Test
    fun `unknown exception yields Unknown reason`() = runTest {
        val engine = CloudGeminiEngine(
            apiKey = validLengthKey,
            probe = { throw IllegalStateException("something unrelated") },
        )
        assertFalse(engine.isAvailable())
        assertEquals(UnavailableReason.Unknown, engine.lastUnavailableReason)
    }
}
