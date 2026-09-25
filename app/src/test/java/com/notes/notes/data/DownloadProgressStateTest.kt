package com.notes.notes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download restart rules.
 *
 * The critical property is that a restart discards *everything* known about the previous
 * representation, including its total length: an old object's size must never be used to decide
 * whether the end of the replacement object's stream means the download is complete.
 */
class DownloadProgressStateTest {

    @Test
    fun `restart clears bytes etag and total length`() {
        val state = DownloadProgressState(
            downloadedBytes = 4_096L,
            totalBytes = 10_000L,
            etag = "\"old-object\"",
        )

        state.requestRestartFromZero()

        assertEquals(0L, state.downloadedBytes)
        assertEquals(0L, state.totalBytes)
        assertEquals("", state.etag)
        assertEquals(0f, state.fraction, 0.0001f)
        assertTrue(state.consumeRestartFromZero())
        assertFalse(state.consumeRestartFromZero())
    }

    @Test
    fun `unknown content length after a restart does not inherit the old total`() {
        val state = DownloadProgressState(totalBytes = 10_000L, etag = "\"old-object\"")

        // HTTP 412 because If-Match failed: the replacement object has its own length.
        state.requestRestartFromZero()
        // The new response carries no Content-Length at all.
        state.takeFreshTotal(contentLength = -1L)
        state.recordBytesRead(2_500L)

        assertFalse(state.hasKnownTotal)
        assertEquals(0L, state.totalBytes)
        assertEquals(0f, state.fraction, 0.0001f)
        // With no length to verify against, the end of the stream is the only completion signal.
        assertTrue(state.isCompleteAfterEndOfStream())
    }

    @Test
    fun `a completed replacement object still cannot fill the old length`() {
        val state = DownloadProgressState(downloadedBytes = 9_500L, totalBytes = 10_000L)

        state.requestRestartFromZero()
        state.recordBytesRead(400L)

        // 400 bytes of the replacement must not look like a nearly finished 10 000 byte object.
        assertEquals(0L, state.totalBytes)
        assertEquals(0f, state.fraction, 0.0001f)
    }

    @Test
    fun `partial content establishes the total from the resumed offset`() {
        val state = DownloadProgressState(downloadedBytes = 1_000L, totalBytes = 0L)

        state.takeResumedTotal(statusCode = 206, contentLength = 4_000L, requestedOffset = 1_000L)

        assertEquals(5_000L, state.totalBytes)
        assertEquals(0.2f, state.fraction, 0.0001f)
        assertFalse(state.isCompleteAfterEndOfStream())
    }

    @Test
    fun `an ignored range response replaces the total with the new object length`() {
        val state = DownloadProgressState(downloadedBytes = 4_096L, totalBytes = 10_000L)

        state.requestRestartFromZero()
        state.takeFreshTotal(contentLength = 7_777L)

        assertEquals(7_777L, state.totalBytes)
        assertEquals(0L, state.downloadedBytes)
    }

    @Test
    fun `a known total still gates completion`() {
        val state = DownloadProgressState(totalBytes = 1_000L)

        state.recordBytesRead(999L)
        assertFalse(state.isCompleteAfterEndOfStream())

        state.recordBytesRead(1L)
        assertTrue(state.isCompleteAfterEndOfStream())
    }

    @Test
    fun `blank etags never overwrite a known one`() {
        val state = DownloadProgressState(etag = "\"known\"")

        state.takeETag("")

        assertEquals("\"known\"", state.etag)
    }
}
