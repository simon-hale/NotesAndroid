package com.notes.notes.data

import com.notes.notes.core.DownloadPhase
import com.notes.notes.core.DownloadRingMode
import com.notes.notes.core.DownloadTransfer
import com.notes.notes.core.TransferNotice
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The failure chain of a download, from the failed attempt to the process that comes after it.
 *
 * No test here builds a FAILED record by hand. The record starts as TRANSFERRING, the failure is produced
 * by the very rules the download worker uses, and the next state owner only reads what the store kept —
 * which is the only way to catch the case this chain is about: a download that fails while no UI is alive.
 */
class DownloadFailureRecoveryTest {

    private val now = 10_000L

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

    @Test
    fun `a failed attempt keeps the newest bytes and becomes a terminal failure`() {
        val chain = DownloadFailureChain(storedTransfer())

        chain.attemptPublishedProgress(
            downloadedBytes = 40_000L,
            totalBytes = 100_000L,
            etag = "\"v1\"",
        )
        chain.attemptFailed()

        assertEquals(DownloadPhase.FAILED, chain.record.phase)
        assertEquals(40_000L, chain.record.downloadedBytes)
        assertEquals(100_000L, chain.record.totalBytes)
        assertEquals("\"v1\"", chain.record.etag)
        assertEquals("content://downloads/1", chain.record.destinationUri)
        assertEquals("report.pdf", chain.record.fileName)
    }

    @Test
    fun `a progress write after the failure cannot bring the record back to transferring`() {
        val chain = DownloadFailureChain(storedTransfer())
        chain.attemptPublishedProgress(downloadedBytes = 40_000L, totalBytes = 100_000L)
        chain.attemptFailed()

        chain.attemptPublishedProgress(downloadedBytes = 45_000L, totalBytes = 100_000L)

        assertEquals(DownloadPhase.FAILED, chain.record.phase)
        assertEquals(45_000L, chain.record.downloadedBytes)
    }

    @Test
    fun `a failure recorded while the app was closed survives the next process without a yellow ring`() {
        val chain = DownloadFailureChain(storedTransfer())
        chain.attemptPublishedProgress(downloadedBytes = 40_000L, totalBytes = 100_000L)

        // No UI was alive: the worker records the failure itself.
        chain.attemptFailed()

        // The next process only ever sees a finished work item. The failure is already stored, so nothing
        // is written twice, and the record stays failed.
        val report = chain.applyWorkResult(DownloadWorkOutcome.FAILED, watchedWhileRunning = false)
        assertFalse(report.applied)
        assertFalse(report.reported)
        assertEquals(DownloadPhase.FAILED, chain.record.phase)

        chain.reconciledWithoutWork()
        assertEquals(DownloadPhase.FAILED, chain.record.phase)

        val task = chain.roundTask(hasLiveWork = false)
        assertFalse(task.active)

        val ring = DownloadRoundProgress().reduce(listOf(task), now).ring
        assertNotEquals(DownloadRingMode.PAUSED, ring.mode)
        assertEquals(DownloadRingMode.HIDDEN, ring.mode)
    }

    @Test
    fun `a failure that was never recorded is recovered from the first finished work item`() {
        val chain = DownloadFailureChain(storedTransfer())
        chain.attemptPublishedProgress(downloadedBytes = 40_000L, totalBytes = 100_000L)

        // The process died before the terminal phase could be written, so the record still claims to run.
        val report = chain.applyWorkResult(DownloadWorkOutcome.FAILED, watchedWhileRunning = false)
        assertTrue(report.applied)

        assertEquals(DownloadPhase.FAILED, chain.record.phase)
        // The resumable state the worker persisted is untouched.
        assertEquals(40_000L, chain.record.downloadedBytes)
        assertEquals(100_000L, chain.record.totalBytes)

        chain.reconciledWithoutWork()
        assertEquals(DownloadPhase.FAILED, chain.record.phase)
        assertFalse(chain.roundTask(hasLiveWork = false).active)
    }

    @Test
    fun `a user pause is never read as a failure`() {
        val chain = DownloadFailureChain(storedTransfer(phase = DownloadPhase.PAUSED))

        val report = chain.applyWorkResult(DownloadWorkOutcome.CANCELED, watchedWhileRunning = false)
        assertFalse(report.applied)
        assertEquals(DownloadPhase.PAUSED, chain.record.phase)

        chain.reconciledWithoutWork()
        assertEquals(DownloadPhase.PAUSED, chain.record.phase)

        val task = chain.roundTask(hasLiveWork = false)
        assertFalse(task.active)
        assertEquals(
            DownloadRingMode.PAUSED,
            DownloadRoundProgress().reduce(listOf(task), now).ring.mode,
        )
    }

    @Test
    fun `a failed attempt is never read as a user pause`() {
        val chain = DownloadFailureChain(storedTransfer(phase = DownloadPhase.FAILED))

        chain.reconciledWithoutWork()

        assertEquals(DownloadPhase.FAILED, chain.record.phase)
    }

    @Test
    fun `only a real error is a persistable failure`() {
        assertFalse(isPersistableDownloadFailure(CancellationException("download paused")))
        assertTrue(isPersistableDownloadFailure(IOException("connection reset")))
        assertTrue(isPersistableDownloadFailure(NotesServiceException.Http(500, "server error")))
        assertTrue(isPersistableDownloadFailure(NotesServiceException.Business("quota exceeded")))
        assertTrue(isPersistableDownloadFailure(DownloadForegroundException(null)))
        assertTrue(isPersistableDownloadFailure(IllegalStateException("Missing download session data")))
    }

    @Test
    fun `a cancelled work item is never a failure`() {
        assertEquals(
            DownloadWorkOutcome.CANCELED,
            downloadWorkOutcome(DownloadWorker.OUTCOME_CANCELED),
        )
        assertEquals(
            DownloadWorkOutcome.FAILED,
            downloadWorkOutcome(DownloadWorker.OUTCOME_FAILED),
        )
        assertEquals(
            DownloadWorkOutcome.SUCCEEDED,
            downloadWorkOutcome(DownloadWorker.OUTCOME_SUCCESS),
        )
        assertEquals(DownloadWorkOutcome.NONE, downloadWorkOutcome(null))

        val chain = DownloadFailureChain(storedTransfer())
        val report = chain.applyWorkResult(DownloadWorkOutcome.CANCELED, watchedWhileRunning = true)
        assertFalse(report.applied)
        assertEquals(DownloadPhase.TRANSFERRING, chain.record.phase)
    }

    @Test
    fun `a retried failure starts a new round at its historical progress`() {
        val chain = DownloadFailureChain(storedTransfer())
        chain.attemptPublishedProgress(downloadedBytes = 40_000L, totalBytes = 100_000L)
        chain.attemptFailed()

        val round = DownloadRoundProgress()
        val whileFailed = round.reduce(listOf(chain.roundTask(hasLiveWork = false)), now)
        assertEquals(DownloadRingMode.HIDDEN, whileFailed.ring.mode)
        assertNotEquals(DownloadRingMode.PAUSED, whileFailed.ring.mode)

        // The user retries: the transfer runs again and the enqueue grace covers the new work item.
        chain.retried()
        val afterRetry = round.reduce(
            listOf(chain.roundTask(hasLiveWork = false, withinEnqueueGrace = true)),
            now,
        )

        assertEquals(DownloadRingMode.DETERMINATE, afterRetry.ring.mode)
        assertEquals(0.4f, afterRetry.ring.progress, 0.0001f)
        assertEquals(listOf("transfer-1"), round.memberIds)
        assertEquals(100_000L, round.totalBytes)
    }
}
