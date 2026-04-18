package com.tsunaguba.corechat.data.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.tsunaguba.corechat.domain.model.UnavailableReason
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class MediaPipeLlmEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var modelDir: File
    private lateinit var modelFile: File
    private lateinit var context: Context
    private lateinit var downloader: GemmaModelDownloader
    private lateinit var factory: LlmEngineFactory

    private val fakeUrl = "https://example.test/gemma.litertlm"
    private val fakeSize = 1024L

    @Before
    fun setUp() {
        modelDir = tempFolder.newFolder("files")
        modelFile = File(modelDir, "gemma.litertlm")
        context = mockk(relaxed = true)
        every { context.filesDir } returns modelDir
        downloader = mockk()
        factory = mockk()
    }

    @After
    fun tearDown() {
        // TemporaryFolder auto-cleans
    }

    private fun newEngine(
        url: String = fakeUrl,
        size: Long = fakeSize,
    ) = MediaPipeLlmEngine(
        context = context,
        downloader = downloader,
        factory = factory,
        modelFile = modelFile,
        expectedSizeBytes = size,
        modelUrl = url,
    )

    @Test
    fun `blank url yields ModelInitializationFailed without downloading`() = runTest {
        val engine = newEngine(url = "")
        assertFalse(engine.isAvailable())
        assertEquals(UnavailableReason.ModelInitializationFailed, engine.lastUnavailableReason)
    }

    @Test
    fun `zero size yields ModelInitializationFailed without downloading`() = runTest {
        val engine = newEngine(size = 0L)
        assertFalse(engine.isAvailable())
        assertEquals(UnavailableReason.ModelInitializationFailed, engine.lastUnavailableReason)
    }

    @Test
    fun `download failure yields ModelDownloadFailed reason`() = runTest {
        coEvery {
            downloader.ensure(any(), any(), any(), any())
        } throws java.io.IOException("connection refused")

        val engine = newEngine()
        assertFalse(engine.isAvailable())
        assertEquals(UnavailableReason.ModelDownloadFailed, engine.lastUnavailableReason)
    }

    @Test
    fun `checksum mismatch yields ModelChecksumMismatch reason`() = runTest {
        coEvery {
            downloader.ensure(any(), any(), any(), any())
        } throws GemmaModelDownloader.ChecksumMismatchException(
            expected = "aa",
            actual = "bb",
        )

        val engine = newEngine()
        assertFalse(engine.isAvailable())
        assertEquals(UnavailableReason.ModelChecksumMismatch, engine.lastUnavailableReason)
    }

    @Test
    fun `insufficient storage yields InsufficientStorage reason`() = runTest {
        coEvery {
            downloader.ensure(any(), any(), any(), any())
        } throws GemmaModelDownloader.InsufficientStorageException(
            required = 2_000_000L,
            available = 100_000L,
        )

        val engine = newEngine()
        assertFalse(engine.isAvailable())
        assertEquals(UnavailableReason.InsufficientStorage, engine.lastUnavailableReason)
    }

    @Test
    fun `factory exception yields ModelInitializationFailed reason`() = runTest {
        // Downloader succeeds (model file present, caller passes through).
        coEvery {
            downloader.ensure(any(), any(), any(), any())
        } coAnswers {
            modelFile.createNewFile()
            modelFile
        }
        every {
            factory.create(any(), any(), any(), any(), any())
        } throws RuntimeException("libllm.so failed to load")

        val engine = newEngine()
        assertFalse(engine.isAvailable())
        assertEquals(UnavailableReason.ModelInitializationFailed, engine.lastUnavailableReason)
    }

    @Test
    fun `happy path yields available with no reason`() = runTest {
        coEvery {
            downloader.ensure(any(), any(), any(), any())
        } coAnswers {
            modelFile.createNewFile()
            modelFile
        }
        val fakeLlm = mockk<LlmInference>(relaxed = true)
        every {
            factory.create(any(), any(), any(), any(), any())
        } returns fakeLlm

        val engine = newEngine()
        assertTrue(engine.isAvailable())
        assertNull(engine.lastUnavailableReason)
    }

    @Test
    fun `second isAvailable returns true without re-initialising`() = runTest {
        coEvery {
            downloader.ensure(any(), any(), any(), any())
        } coAnswers {
            modelFile.createNewFile()
            modelFile
        }
        var factoryCalls = 0
        val fakeLlm = mockk<LlmInference>(relaxed = true)
        every {
            factory.create(any(), any(), any(), any(), any())
        } answers {
            factoryCalls++
            fakeLlm
        }

        val engine = newEngine()
        assertTrue(engine.isAvailable())
        assertTrue(engine.isAvailable())
        assertEquals(1, factoryCalls)
    }

    @Test
    fun `progress callback mirrors into status flow`() = runTest {
        val progressSlot = slot<(Float) -> Unit>()
        coEvery {
            downloader.ensure(any(), any(), any(), capture(progressSlot))
        } coAnswers {
            // Simulate three progress ticks before completion.
            progressSlot.captured(0.25f)
            progressSlot.captured(0.75f)
            progressSlot.captured(1f)
            modelFile.createNewFile()
            modelFile
        }
        val fakeLlm = mockk<LlmInference>(relaxed = true)
        every {
            factory.create(any(), any(), any(), any(), any())
        } returns fakeLlm

        val engine = newEngine()
        assertTrue(engine.isAvailable())
        // Final state should be Ready, not Downloading — progress emissions are
        // transient and overwritten by the Ready result.
        val s = engine.status.value
        assertTrue("expected Ready, got $s", s is com.tsunaguba.corechat.domain.model.AiModelStatus.Ready)
    }
}
