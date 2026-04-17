package com.tsunaguba.corechat.data.ai

import com.tsunaguba.corechat.domain.model.AiEngineMode
import com.tsunaguba.corechat.domain.model.AiModelStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiEngineProviderTest {

    private fun aicore(
        available: Boolean,
        status: MutableStateFlow<AiModelStatus> = MutableStateFlow(AiModelStatus.Initializing),
    ): AiCoreEngine = mockk<AiCoreEngine>(relaxed = false).also {
        every { it.id } returns AiCoreEngine.ENGINE_ID
        every { it.status } returns status
        coEvery { it.isAvailable() } returns available
    }

    private fun cloud(available: Boolean): CloudGeminiEngine =
        mockk<CloudGeminiEngine>(relaxed = false).also {
            every { it.id } returns "cloud-gemini"
            coEvery { it.isAvailable() } returns available
        }

    @Test
    fun `aicore available yields on-device mode (useCloud=false)`() = runTest {
        val aicoreStatus = MutableStateFlow<AiModelStatus>(
            AiModelStatus.Ready(AiEngineMode.OnDevice),
        )
        val provider = AiEngineProvider(
            aicore = aicore(available = true, status = aicoreStatus),
            cloud = cloud(available = true),
            externalScope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()
        assertEquals(AiModelStatus.Ready(AiEngineMode.OnDevice), provider.status.first())
    }

    @Test
    fun `aicore unavailable but cloud available yields Ready(Cloud)`() = runTest {
        val provider = AiEngineProvider(
            aicore = aicore(available = false),
            cloud = cloud(available = true),
            externalScope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()
        assertEquals(AiModelStatus.Ready(AiEngineMode.Cloud), provider.status.first())
    }

    @Test
    fun `aicore and cloud both unavailable yields Unavailable`() = runTest {
        val provider = AiEngineProvider(
            aicore = aicore(available = false),
            cloud = cloud(available = false),
            externalScope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()
        assertEquals(AiModelStatus.Unavailable, provider.status.first())
    }

    @Test
    fun `initial status before probe completes is Initializing`() = runTest {
        val slow = mockk<AiCoreEngine>(relaxed = false).also {
            every { it.id } returns AiCoreEngine.ENGINE_ID
            every { it.status } returns MutableStateFlow(AiModelStatus.Initializing)
            // isAvailable never returns within this test — probe stays pending.
            coEvery { it.isAvailable() } coAnswers {
                kotlinx.coroutines.delay(Long.MAX_VALUE)
                true
            }
        }
        val provider = AiEngineProvider(
            aicore = slow,
            cloud = cloud(available = true),
            externalScope = TestScope(StandardTestDispatcher(testScheduler)),
        )
        // Probe coroutine is queued but not dispatched yet.
        assertEquals(AiModelStatus.Initializing, provider.status.first())
    }

    @Test
    fun `cloud-only stream failure transitions status to Error`() = runTest {
        val cloudEngine = mockk<CloudGeminiEngine>(relaxed = false).also {
            every { it.id } returns "cloud-gemini"
            coEvery { it.isAvailable() } returns true
            every { it.stream(any(), any()) } returns flow<String> {
                throw IllegalStateException("upstream 503")
            }
        }
        val provider = AiEngineProvider(
            aicore = aicore(available = false),
            cloud = cloudEngine,
            externalScope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()
        // Sanity: cloud-only ready first.
        assertEquals(AiModelStatus.Ready(AiEngineMode.Cloud), provider.status.first())

        // Consuming the stream should propagate the error AND persist it into status.
        var caught: Throwable? = null
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        scope.launch {
            try {
                provider.stream(emptyList(), "hi").collect { /* no-op */ }
            } catch (t: Throwable) {
                caught = t
            }
        }
        advanceUntilIdle()

        assertTrue(caught is IllegalStateException)
        val finalStatus = provider.status.first()
        assertTrue("expected Error, got $finalStatus", finalStatus is AiModelStatus.Error)
    }

    @Test
    fun `successful stream clears a prior persistent error`() = runTest {
        var throwOnce = true
        val cloudEngine = mockk<CloudGeminiEngine>(relaxed = false).also {
            every { it.id } returns "cloud-gemini"
            coEvery { it.isAvailable() } returns true
            every { it.stream(any(), any()) } answers {
                if (throwOnce) {
                    throwOnce = false
                    flow<String> { throw IllegalStateException("transient") }
                } else {
                    flowOf("ok")
                }
            }
        }
        val provider = AiEngineProvider(
            aicore = aicore(available = false),
            cloud = cloudEngine,
            externalScope = TestScope(UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()

        // First stream fails → persistentError set → status becomes Error.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        scope.launch {
            runCatching { provider.stream(emptyList(), "a").collect { /* no-op */ } }
        }
        advanceUntilIdle()
        assertTrue(provider.status.first() is AiModelStatus.Error)

        // Second stream succeeds → clears persistentError → status returns to Ready(Cloud).
        scope.launch {
            provider.stream(emptyList(), "b").collect { /* no-op */ }
        }
        advanceUntilIdle()
        assertEquals(AiModelStatus.Ready(AiEngineMode.Cloud), provider.status.first())
    }
}
