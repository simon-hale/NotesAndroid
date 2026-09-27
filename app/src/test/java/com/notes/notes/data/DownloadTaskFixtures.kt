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

/** What the state owner did with a finished failed work item. */
internal data class FailureReport(
    val outcome: DownloadFailureOutcome,
    /** True when the user is told about it: only an owned result this process watched is reported. */
    val reported: Boolean,
) {
    /** True when this pass wrote the terminal failure. */
    val applied: Boolean get() = outcome == DownloadFailureOutcome.APPLIED
}

/**
 * The store, the UI projection and the parties that touch them: the worker running an attempt, the user
 * who pauses or cancels, and the state owner that reads a finished work item afterwards — including the
 * next process, which only sees what the store kept.
 *
 * The two states are kept apart on purpose. [recordsOrEmpty] is the authoritative persisted state, while
 * [mirror] is the projection the UI renders; a test can force the mirror to lag behind exactly the way a
 * queued store emission does. Every step calls the production rule it stands for, so a test drives the
 * real chain instead of a copy of it.
 */
internal class DownloadFailureChain(initial: DownloadTransfer) {

    private val records = mutableListOf(initial)

    /** The UI projection of the stored records; `null` once the row is gone. */
    var mirror: DownloadTransfer? = initial
        private set

    /** The stored records; empty once a finished cancel removed its transfer. */
    val recordsOrEmpty: List<DownloadTransfer> get() = records.toList()

    /** The only stored record. Fails loudly when a cancel already deleted it. */
    val record: DownloadTransfer
        get() = checkNotNull(records.firstOrNull()) { "the download record was deleted" }

    /** The phase the UI row shows right now. */
    val mirroredPhase: DownloadPhase? get() = mirror?.phase

    /** Forces the UI projection to lag behind the store, the way a queued emission would. */
    fun projectMirrorPhase(phase: DownloadPhase) {
        mirror = mirror?.copy(phase = phase)
    }

    private fun update(transform: (DownloadTransfer) -> DownloadTransfer) {
        records.forEachIndexed { index, current ->
            records[index] = transform(current)
        }
        mirror = records.firstOrNull()
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

    /** The destructive cancel completing: the record and its row go away. */
    fun cancelFinished() {
        records.clear()
        mirror = null
    }

    /** The stored record disappearing without the UI having projected it yet. */
    fun dropRecordKeepingMirror() {
        records.clear()
    }

    /** What the next start does with the cancels a previous process could not finish. */
    fun finishInterruptedCancels() {
        val interrupted = interruptedCancels(records)
        records.removeAll(interrupted.toSet())
        mirror = records.firstOrNull()
    }

    /** The phase reconciliation of a process that knows nothing about live work for this record. */
    fun reconciledWithoutWork() {
        update { it.copy(phase = it.phase.withoutLiveWork()) }
    }

    /**
     * What the state owner does with a finished failed work item.
     *
     * The stored record decides — the same judgement [TransferStore.resolveDownloadFailure] makes inside
     * one edit — and the UI projection is only corrected afterwards, which is what makes a stale mirror
     * harmless. Only an owned result this process watched is reported to the user.
     */
    fun applyWorkResult(
        watchedWhileRunning: Boolean,
        supersededByLiveWork: Boolean = false,
    ): FailureReport {
        val stored = records.firstOrNull()
        val outcome = resolveDownloadFailureOutcome(
            storedPhase = stored?.phase,
            supersededByLiveWork = supersededByLiveWork,
        )

        when (outcome) {
            DownloadFailureOutcome.APPLIED -> update { it.failedAttemptIfRunning() }

            DownloadFailureOutcome.ALREADY_FAILED,
            DownloadFailureOutcome.IGNORED_PHASE,
            -> mirror = stored

            DownloadFailureOutcome.MISSING -> mirror = null

            DownloadFailureOutcome.SUPERSEDED -> Unit
        }

        val ownsFailure =
            outcome == DownloadFailureOutcome.APPLIED || outcome == DownloadFailureOutcome.ALREADY_FAILED

        return FailureReport(
            outcome = outcome,
            reported = ownsFailure && watchedWhileRunning,
        )
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
