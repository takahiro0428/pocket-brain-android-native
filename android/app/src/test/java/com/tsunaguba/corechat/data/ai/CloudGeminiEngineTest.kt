package com.tsunaguba.corechat.data.ai

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudGeminiEngineTest {

    @Test
    fun `blank api key reports unavailable without invoking probe`() = runTest {
        var probeCalls = 0
        val engine = CloudGeminiEngine(
            apiKey = "",
            probe = {
                probeCalls++
            },
        )
        assertFalse(engine.isAvailable())
        assertTrue(probeCalls == 0)
    }

    @Test
    fun `probe success yields available`() = runTest {
        val engine = CloudGeminiEngine(
            apiKey = "test-key",
            probe = { /* pretend the countTokens call succeeded */ },
        )
        assertTrue(engine.isAvailable())
    }

    @Test
    fun `probe exception yields unavailable`() = runTest {
        val engine = CloudGeminiEngine(
            apiKey = "test-key",
            probe = { throw IllegalStateException("401 Unauthorized") },
        )
        assertFalse(engine.isAvailable())
    }

    @Test
    fun `probe timeout yields unavailable`() = runTest {
        val engine = CloudGeminiEngine(
            apiKey = "test-key",
            probe = {
                // Exceeds CLOUD_PROBE_TIMEOUT_MS. Under the virtual test clock
                // runTest auto-advances so this resolves instantly.
                delay(CloudGeminiEngine.CLOUD_PROBE_TIMEOUT_MS + 1_000L)
            },
        )
        assertFalse(engine.isAvailable())
    }
}
