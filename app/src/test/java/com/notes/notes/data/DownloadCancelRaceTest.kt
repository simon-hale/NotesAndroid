package com.notes.notes.data

import com.notes.notes.core.DownloadPhase
import com.notes.notes.core.DownloadRingMode
import com.notes.notes.core.DownloadTransfer
import com.notes.notes.core.TransferNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The race between a user decision and an old worker that is still unwinding.
 *
 * A pause or a destructive cancel is a persistent business fact the moment it is written. An attempt that
 * ends with an ordinary I/O error afterwards belongs to nobody: it must not turn a paused download into a
 * failure, it must not report anything to the user, and it must not resurrect a transfer the user is
 * deleting. All steps below go through the production rules the app itself uses.
 */
class DownloadCancelRaceTest {

    private val now = 20_000L

    private fun storedTransfer(
        phase: DownloadPhase = DownloadPhase.TRANSFERRING,
    ) = DownloadTransfer(
        transferId = "transfer-1",
        accountKey = "user",
        fileId = 7L,
        fileName = "report.pdf",
        destinationUri = "content://downloads/1",
        downloadedBytes = 0L,
        totalBytes = 0L,
        etag = "",
        language = "zh-CN",
        phase = phase,
        notice = TransferNotice.NONE,
        createdAt = 1L,
    )

    private fun ringOf(task: DownloadTaskProgress) =
        DownloadRoundProgress().reduce(listOf(task), now).ring

    @Test
    fun `a real error while the transfer is running still becomes a failure`() {
        val chain = DownloadFailureChain(storedTransfer())

        chain.attemptPublishedProgress(downloadedBytes = 40_000L, totalBytes = 100_000L)
        chain.attemptFailed()

        assertEquals(DownloadPhase.FAILED, chain.record.phase)
        assertEquals(40_000L, chain.record.downloadedBytes)
        assertEquals(100_000L, chain.record.totalBytes)
    }

    @Test
    fun `an old worker error never overwrites a user pause`() {
        val chain = DownloadFailureChain(storedTransfer())
        chain.attemptPublishedProgress(downloadedBytes = 40_000L, totalBytes = 100_000L)

        chain.userPaused()
        // The worker was cancelled by the pause but its in-flight request ends with an I/O error first.
        chain.attemptFailed()

        assertEquals(DownloadPhase.PAUSED, chain.record.phase)
        assertEquals(40_000L, chain.record.downloadedBytes)
        assertEquals(DownloadRingMode.PAUSED, ringOf(chain.roundTask(hasLiveWork = false)).mode)
    }

    @Test
    fun `a watched failure result never turns a paused transfer into a failure`() {
        val chain = DownloadFailureChain(storedTransfer())
        chain.userPaused()

        val report = chain.applyWorkResult(DownloadWorkOutcome.FAILED, watchedWhileRunning = true)

        assertFalse(report.applied)
        // A pause is not a failure, so nothing is reported to the user either.
        assertFalse(report.reported)
        assertEquals(DownloadPhase.PAUSED, chain.record.phase)
        assertEquals(DownloadRingMode.PAUSED, ringOf(chain.roundTask(hasLiveWork = false)).mode)
    }

    @Test
    fun `a failure the worker could not write is only recovered while the record is still running`() {
        val running = DownloadFailureChain(storedTransfer())
        running.attemptPublishedProgress(downloadedBytes = 40_000L, totalBytes = 100_000L)
        val recovered = running.applyWorkResult(DownloadWorkOutcome.FAILED, watchedWhileRunning = false)
        assertTrue(recovered.applied)
        assertEquals(DownloadPhase.FAILED, running.record.phase)

        val paused = DownloadFailureChain(storedTransfer())
        paused.userPaused()
        val ignored = paused.applyWorkResult(DownloadWorkOutcome.FAILED, watchedWhileRunning = false)
        assertFalse(ignored.applied)
        assertFalse(ignored.reported)
        assertEquals(DownloadPhase.PAUSED, paused.record.phase)
    }

    @Test
    fun `an old failed work item never overrides a newer running attempt`() {
        val chain = DownloadFailureChain(storedTransfer())
        // The user already retried: the transfer runs again, and this result is the previous attempt's.
        chain.attemptPublishedProgress(downloadedBytes = 40_000L, totalBytes = 100_000L)

        val report = chain.applyWorkResult(
            outcome = DownloadWorkOutcome.FAILED,
            watchedWhileRunning = true,
            supersededByLiveWork = true,
        )

        assertFalse(report.applied)
        assertFalse(report.reported)
        assertEquals(DownloadPhase.TRANSFERRING, chain.record.phase)
        assertTrue(chain.roundTask(hasLiveWork = true).active)
    }

    @Test
    fun `an old worker error never overwrites a cancel in flight`() {
        val chain = DownloadFailureChain(storedTransfer())
        chain.attemptPublishedProgress(downloadedBytes = 40_000L, totalBytes = 100_000L)

        chain.userCancelled()
        chain.attemptFailed()

        assertEquals(DownloadPhase.CANCELING, chain.record.phase)
        assertFalse(chain.applyWorkResult(DownloadWorkOutcome.FAILED, watchedWhileRunning = true).applied)
        assertEquals(DownloadPhase.CANCELING, chain.record.phase)
    }

    @Test
    fun `a cancel in flight is not active, not resumable and shows no yellow ring`() {
        val chain = DownloadFailureChain(storedTransfer())
        chain.attemptPublishedProgress(downloadedBytes = 40_000L, totalBytes = 100_000L)
        chain.userCancelled()

        val task = chain.roundTask(hasLiveWork = true)
        assertFalse(task.active)
        assertFalse(chain.record.phase.isResumable)

        val ring = ringOf(task)
        assertNotEquals(DownloadRingMode.PAUSED, ring.mode)
        assertEquals(DownloadRingMode.HIDDEN, ring.mode)
    }

    @Test
    fun `a finished cancel leaves no record behind`() {
        val chain = DownloadFailureChain(storedTransfer())
        chain.userCancelled()

        chain.cancelFinished()

        assertTrue(chain.recordsOrEmpty.isEmpty())
    }

    @Test
    fun `an interrupted cancel is finished by the next start instead of staying forever`() {
        val chain = DownloadFailureChain(storedTransfer())
        chain.userCancelled()

        // The process dies here: the record stays in CANCELING, which is nothing the user can act on.
        assertEquals(DownloadPhase.CANCELING, chain.record.phase)

        chain.finishInterruptedCancels()

        assertTrue(chain.recordsOrEmpty.isEmpty())
    }

    @Test
    fun `a cancel in flight never becomes a user pause`() {
        val chain = DownloadFailureChain(storedTransfer())
        chain.userCancelled()

        // The reconciliation of a process without live work must not turn it into the yellow ring state.
        chain.reconciledWithoutWork()

        assertEquals(DownloadPhase.CANCELING, chain.record.phase)
        assertEquals(
            DownloadRingMode.HIDDEN,
            ringOf(chain.roundTask(hasLiveWork = false)).mode,
        )
    }
}
