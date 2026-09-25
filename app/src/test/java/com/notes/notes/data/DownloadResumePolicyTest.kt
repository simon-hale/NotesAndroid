package com.notes.notes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the rule that prevents mixed bytes: a resumed download must never append a response whose
 * bytes do not start at the recorded offset, and a partial destination that lost data must restart.
 */
class DownloadResumePolicyTest {

    @Test
    fun `partial content continues from the recorded offset`() {
        assertEquals(
            DownloadPlan.CONTINUE_FROM_OFFSET,
            DownloadResumePolicy.planForResponse(statusCode = 206, requestedOffset = 4_096L),
        )
    }

    @Test
    fun `ok response without a range continues from zero`() {
        assertEquals(
            DownloadPlan.CONTINUE_FROM_OFFSET,
            DownloadResumePolicy.planForResponse(statusCode = 200, requestedOffset = 0L),
        )
    }

    @Test
    fun `ok response that ignored the range restarts instead of appending`() {
        assertEquals(
            DownloadPlan.RESTART_FROM_ZERO,
            DownloadResumePolicy.planForResponse(statusCode = 200, requestedOffset = 4_096L),
        )
    }

    @Test
    fun `precondition failed restarts because the remote object changed`() {
        assertEquals(
            DownloadPlan.RESTART_FROM_ZERO,
            DownloadResumePolicy.planForResponse(statusCode = 412, requestedOffset = 4_096L),
        )
    }

    @Test
    fun `range not satisfiable restarts conservatively`() {
        assertEquals(
            DownloadPlan.RESTART_FROM_ZERO,
            DownloadResumePolicy.planForResponse(statusCode = 416, requestedOffset = 9_999_999L),
        )
    }

    @Test
    fun `missing media store destination restarts with a fresh one`() {
        assertEquals(
            DownloadPlan.RESTART_FROM_ZERO,
            DownloadResumePolicy.planForDestination(
                destinationExists = false,
                destinationSize = 0L,
                recordedBytes = 1_024L,
            ),
        )
    }

    @Test
    fun `destination shorter than the recorded progress restarts`() {
        assertEquals(
            DownloadPlan.RESTART_FROM_ZERO,
            DownloadResumePolicy.planForDestination(
                destinationExists = true,
                destinationSize = 512L,
                recordedBytes = 1_024L,
            ),
        )
    }

    @Test
    fun `destination that kept the recorded bytes resumes at the offset`() {
        assertEquals(
            DownloadPlan.CONTINUE_FROM_OFFSET,
            DownloadResumePolicy.planForDestination(
                destinationExists = true,
                destinationSize = 1_024L,
                recordedBytes = 1_024L,
            ),
        )
    }

    @Test
    fun `range header is only sent for a real offset`() {
        assertNull(DownloadResumePolicy.rangeHeaderValue(0L))
        assertEquals("bytes=2048-", DownloadResumePolicy.rangeHeaderValue(2_048L))
    }
}
