package com.tsunaguba.corechat.data.ai

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads the Gemma `.litertlm` (or `.task`) model file to the app's internal
 * storage, verifies integrity against a SHA-256 digest, and surfaces progress to
 * the caller so the UI can render [com.tsunaguba.corechat.domain.model.AiModelStatus.Downloading].
 *
 * ### Why its own class
 * - Tests can use MockWebServer to exercise HTTP, checksum, and filesystem paths
 *   without loading the MediaPipe runtime.
 * - [MediaPipeLlmEngine] stays focused on inference lifecycle; download concerns
 *   (storage checks, atomic rename, SHA-256) are co-located here.
 *
 * ### Atomicity
 * Bytes are streamed into a `.partial` file, verified, then atomically renamed to
 * the final target. Interrupting mid-download leaves a `.partial` file which the
 * next call will overwrite — no risk of a half-downloaded `.litertlm` being
 * passed to MediaPipe (which would fail init with an opaque native error).
 */
class GemmaModelDownloader(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val expectedSha256: String,
) {

    /**
     * Ensures the Gemma model is present and valid at [target].
     *
     * @return the target [File] if the model is available (either was already
     *         present with correct size, or was downloaded and verified).
     * @throws InsufficientStorageException if the device has less free space than
     *         required ([expectedSizeBytes] + 20% margin).
     * @throws ChecksumMismatchException if the downloaded bytes don't match
     *         [expectedSha256] — typically means tampering or truncated transfer.
     * @throws IOException on network/IO failures during download.
     */
    suspend fun ensure(
        target: File,
        expectedSizeBytes: Long,
        url: String,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        // Fast path: model already present at expected size. SHA verification here
        // would add ~10s for a 1.5GB file on every app start, which is too slow.
        // The SHA is the "did the download arrive intact" gate; once the file is
        // on disk, we trust it. If a user's storage corrupts the file, MediaPipe's
        // own init will fail and we surface ModelInitializationFailed.
        if (target.exists() && target.length() == expectedSizeBytes) {
            onProgress(1f)
            return@withContext target
        }

        // Ensure parent dir exists (first-run: filesDir/models/ may not exist).
        target.parentFile?.mkdirs()

        // Pre-flight: check free space with a 20% margin so the download doesn't
        // consume the last byte on the device and render the OS unusable. The
        // margin covers partial-file overhead (`.partial` + final rename means
        // we briefly hold two copies) and SD-card-like rounding errors.
        val required = (expectedSizeBytes * 1.2).toLong()
        val available = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }
            .getOrDefault(Long.MAX_VALUE) // if we can't read, don't block the download
        if (available < required) {
            throw InsufficientStorageException(
                required = required,
                available = available,
            )
        }

        val partial = File(target.parentFile, target.name + ".partial")
        partial.delete() // start clean — previous interrupted run left garbage

        val request = Request.Builder().url(url).build()
        val sha = MessageDigest.getInstance("SHA-256")

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for $url")
            }
            val body = response.body ?: throw IOException("empty body for $url")
            val totalFromHeader = body.contentLength().takeIf { it > 0 } ?: expectedSizeBytes

            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var downloaded = 0L
                    var lastReportedProgress = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        sha.update(buffer, 0, read)
                        downloaded += read

                        // Throttle progress callbacks: a 1.5 GB download at 8 KiB
                        // chunks is ~200k iterations. Emitting on every read would
                        // flood the UI. 0-100 integer ticks is enough resolution.
                        val pct = ((downloaded.toDouble() / totalFromHeader.toDouble()) * 100)
                            .toInt()
                            .coerceIn(0, 100)
                        if (pct != lastReportedProgress) {
                            onProgress(pct / 100f)
                            lastReportedProgress = pct
                        }
                    }
                }
            }
        }

        val actualSha = sha.digest().joinToString("") { "%02x".format(it) }
        if (!actualSha.equals(expectedSha256, ignoreCase = true)) {
            partial.delete()
            throw ChecksumMismatchException(expected = expectedSha256, actual = actualSha)
        }

        // Atomic swap: only after the hash matches do we expose the final path.
        // renameTo across the same directory is atomic on ext4/f2fs (Android's
        // typical filesystems), so a reader can't see a half-built target.
        if (!partial.renameTo(target)) {
            // Fallback for rare filesystems without atomic rename support.
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }

        onProgress(1f)
        target
    }

    class InsufficientStorageException(
        val required: Long,
        val available: Long,
    ) : IOException("Gemma model needs $required bytes, only $available available")

    class ChecksumMismatchException(
        val expected: String,
        val actual: String,
    ) : IOException("SHA-256 mismatch: expected=$expected actual=$actual")

    private companion object {
        /** 64 KiB read buffer — typical sweet spot for HTTP streaming on mobile. */
        private const val BUFFER_BYTES = 64 * 1024
    }
}
