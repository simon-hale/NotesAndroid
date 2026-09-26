package com.notes.notes.data

import com.notes.notes.core.DownloadPhase
import com.notes.notes.core.DownloadTransfer

/**
 * Builds one round-accounting task exactly the way the ViewModel does, through the production
 * [downloadTaskProgress] factory, so the tests exercise the real "is this download active" rule instead
 * of a copy of it.
 *
 * `hasLiveWork` mirrors an unfinished WorkManager item and `withinEnqueueGrace` mirrors a transfer whose
 * work was requested a moment ago.
 */
internal fun downloadTask(
    id: String,
    downloaded: Long,
    total: Long,
    phase: DownloadPhase = DownloadPhase.TRANSFERRING,
    hasLiveWork: Boolean = true,
    withinEnqueueGrace: Boolean = false,
): DownloadTaskProgress = downloadTaskProgress(
    transferId = id,
    phase = phase,
    totalBytes = total,
    downloadedBytes = downloaded,
    hasLiveWork = hasLiveWork,
    withinEnqueueGrace = withinEnqueueGrace,
)

/** What the state owner did with a finished work item. */
internal data class FailureReport(
    /** True when the failure still owned the transfer and was therefore written. */
    val applied: Boolean = false,
    /** True when the user is told about it, which only happens for an applied result that was watched. */
    val reported: Boolean = false,
)

/**
 * One persisted download record plus the parties that touch it: the worker running an attempt, the user
 * who pauses or cancels, and the state owner that reads the result afterwards — including the next
 * process, which only sees what the store kept.
 *
 * Every step calls the production rule it stands for, so a test drives the real chain instead of a copy
 * of it.
 */
internal class DownloadFailureChain(initial: DownloadTransfer) {

    private val records = mutableListOf(initial)

    /** The stored records; empty once a finished cancel removed its transfer. */
    val recordsOrEmpty: List<DownloadTransfer> get() = records.toList()

    /** The only record of this chain. Fails loudly when a cancel already deleted it. */
    val record: DownloadTransfer
        get() = checkNotNull(records.firstOrNull()) { "the download record was deleted" }

    private fun update(transform: (DownloadTransfer) -> DownloadTransfer) {
        records.forEachIndexed { index, current ->
            records[index] = transform(current)
        }
    }

    /** One attempt publishing its progress, exactly like the worker's `persistRun`. */
    fun attemptPublishedProgress(
        downloadedBytes: Long,
        totalBytes: Long,
        etag: String = "",
    ) {
        update { current ->
            current.withAttemptProgress(
                fileName = current.fileName,
                destinationUri = current.destinationUri,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                etag = etag,
                notice = current.notice,
            )
        }
    }

    /** The worker ending with a real error: the terminal phase is written only while it still owns it. */
    fun attemptFailed() {
        update { it.failedAttemptIfRunning() }
    }

    /** The user pausing the transfer, which is what the ViewModel persists. */
    fun userPaused() {
        update { it.copy(phase = DownloadPhase.PAUSED) }
    }

    /** The user cancelling the transfer: the cancel intent is persisted before the worker is stopped. */
    fun userCancelled() {
        update { it.asCancelling() }
    }

    /** An explicit retry: the user starts a paused or failed transfer again. */
    fun retried() {
        update { it.copy(phase = DownloadPhase.TRANSFERRING) }
    }

    /** The destructive cancel completing: the record and its partial destination go away. */
    fun cancelFinished() {
        records.clear()
    }

    /** What the next start does with the cancels a previous process could not finish. */
    fun finishInterruptedCancels() {
        val interrupted = interruptedCancels(records)
        records.removeAll(interrupted.toSet())
    }

    /** The phase reconciliation of a process that knows nothing about live work for this record. */
    fun reconciledWithoutWork() {
        update { it.copy(phase = it.phase.withoutLiveWork()) }
    }

    /**
     * What the state owner does with a finished work item: the stored phase decides whether the result is
     * applied, and only an applied result this process watched is reported to the user.
     */
    fun applyWorkResult(
        outcome: DownloadWorkOutcome,
        watchedWhileRunning: Boolean,
        supersededByLiveWork: Boolean = false,
    ): FailureReport {
        if (outcome != DownloadWorkOutcome.FAILED) return FailureReport()
        val applied = shouldRecordDownloadFailure(
            record = records.firstOrNull(),
            supersededByLiveWork = supersededByLiveWork,
        )
        if (applied) update { it.failedAttemptIfRunning() }
        return FailureReport(applied = applied, reported = applied && watchedWhileRunning)
    }

    fun roundTask(
        hasLiveWork: Boolean,
        withinEnqueueGrace: Boolean = false,
    ) = downloadTaskProgress(
        transferId = record.transferId,
        phase = record.phase,
        totalBytes = record.totalBytes,
        downloadedBytes = record.downloadedBytes,
        hasLiveWork = hasLiveWork,
        withinEnqueueGrace = withinEnqueueGrace,
    )
}
