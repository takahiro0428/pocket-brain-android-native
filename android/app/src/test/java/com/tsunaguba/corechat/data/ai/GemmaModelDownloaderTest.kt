package com.tsunaguba.corechat.data.ai

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class GemmaModelDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var httpClient: OkHttpClient

    // Fixture bytes: short enough for tests, long enough to trigger the chunked
    // read loop at least once. SHA-256 precomputed below.
    private val fixtureBytes = "hello world".toByteArray()
    private val fixtureSha256 = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        context = mockk(relaxed = true)
        every { context.filesDir } returns tempFolder.root
        httpClient = OkHttpClient.Builder().build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `successful download writes target file with correct bytes`() = runTest {
        server.enqueue(
            MockResponse().setBody(Buffer().write(fixtureBytes))
                .setHeader("Content-Length", fixtureBytes.size.toString()),
        )

        val target = File(tempFolder.root, "gemma.litertlm")
        val downloader = GemmaModelDownloader(
            context = context,
            httpClient = httpClient,
            expectedSha256 = fixtureSha256,
        )

        val result = downloader.ensure(
            target = target,
            expectedSizeBytes = fixtureBytes.size.toLong(),
            url = server.url("/gemma").toString(),
            onProgress = {},
        )

        assertTrue(result.exists())
        assertEquals(fixtureBytes.size.toLong(), result.length())
        assertArrayEquals(fixtureBytes, result.readBytes())
    }

    @Test
    fun `checksum mismatch throws ChecksumMismatchException`() = runTest {
        server.enqueue(
            MockResponse().setBody(Buffer().write(fixtureBytes))
                .setHeader("Content-Length", fixtureBytes.size.toString()),
        )

        val downloader = GemmaModelDownloader(
            context = context,
            httpClient = httpClient,
            expectedSha256 = "deadbeef".repeat(8), // wrong on purpose
        )

        try {
            downloader.ensure(
                target = File(tempFolder.root, "gemma.litertlm"),
                expectedSizeBytes = fixtureBytes.size.toLong(),
                url = server.url("/gemma").toString(),
                onProgress = {},
            )
            fail("expected ChecksumMismatchException")
        } catch (e: GemmaModelDownloader.ChecksumMismatchException) {
            // On mismatch the `.partial` file should be removed so a later retry
            // starts clean.
            val partial = File(tempFolder.root, "gemma.litertlm.partial")
            assertFalse(partial.exists())
        }
    }

    @Test
    fun `http 500 throws IOException and leaves target missing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val target = File(tempFolder.root, "gemma.litertlm")
        val downloader = GemmaModelDownloader(
            context = context,
            httpClient = httpClient,
            expectedSha256 = fixtureSha256,
        )

        try {
            downloader.ensure(
                target = target,
                expectedSizeBytes = fixtureBytes.size.toLong(),
                url = server.url("/gemma").toString(),
                onProgress = {},
            )
            fail("expected IOException on 5xx")
        } catch (e: java.io.IOException) {
            assertFalse(target.exists())
        }
    }

    @Test
    fun `existing target with matching size skips download`() = runTest {
        val target = File(tempFolder.root, "gemma.litertlm")
        target.writeBytes(fixtureBytes)

        // No server enqueue — if the code fetches, the test hangs/fails.
        val downloader = GemmaModelDownloader(
            context = context,
            httpClient = httpClient,
            expectedSha256 = fixtureSha256,
        )

        val result = downloader.ensure(
            target = target,
            expectedSizeBytes = fixtureBytes.size.toLong(),
            url = server.url("/gemma").toString(),
            onProgress = {},
        )

        assertNotNull(result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `progress callbacks fire between 0 and 1 during download`() = runTest {
        server.enqueue(
            MockResponse().setBody(Buffer().write(fixtureBytes))
                .setHeader("Content-Length", fixtureBytes.size.toString()),
        )

        val progressValues = mutableListOf<Float>()
        val downloader = GemmaModelDownloader(
            context = context,
            httpClient = httpClient,
            expectedSha256 = fixtureSha256,
        )

        downloader.ensure(
            target = File(tempFolder.root, "gemma.litertlm"),
            expectedSizeBytes = fixtureBytes.size.toLong(),
            url = server.url("/gemma").toString(),
            onProgress = { p -> progressValues.add(p) },
        )

        assertTrue("expected at least one progress tick", progressValues.isNotEmpty())
        // First progress should be >0 (we got bytes), last should be 1f (completion).
        assertTrue(progressValues.last() >= 0.99f)
        // Progress values should be monotonically non-decreasing.
        for (i in 1 until progressValues.size) {
            assertTrue(
                "progress regressed at index $i: ${progressValues[i - 1]} -> ${progressValues[i]}",
                progressValues[i] >= progressValues[i - 1],
            )
        }
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("byte $i differs", expected[i], actual[i])
        }
    }
}
