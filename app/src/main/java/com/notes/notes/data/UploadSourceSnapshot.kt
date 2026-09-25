package com.notes.notes.data

import com.notes.notes.core.TransferNotice
import com.notes.notes.core.UploadTransfer

/** The persistent transfer prepared for one resumable upload attempt. */
data class UploadSourcePlan(
    /** True when the local document is a different revision than the persisted snapshot. */
    val sourceChanged: Boolean,
    val transfer: UploadTransfer,
)

/**
 * Outcome of preparing one attempt from a freshly requested STS ticket.
 *
 * A rejected attempt produces no prepared transfer, which is what keeps the worker from updating the
 * record, deleting the checkpoint or starting a multipart upload anywhere else.
 */
sealed interface UploadAttemptPreparation {
    data class Accepted(val plan: UploadSourcePlan) : UploadAttemptPreparation

    data class Rejected(val field: UploadScopeField) : UploadAttemptPreparation
}

/**
 * Matches the metadata of the picked document against the snapshot persisted on the transfer.
 *
 * The snapshot exists to answer exactly one question: is the local document still the same revision
 * the SDK checkpoint was built from? It is intentionally weak (size plus provider last-modified), so
 * the rules are:
 *
 * - a value that was never learned is a sentinel (`0`) and never counts as a modification;
 * - once a reliable value has been learned it is frozen on the transfer and every later resume
 *   compares against it;
 * - a change that is accepted as the reason to discard the old resumable upload replaces the snapshot
 *   with the new revision, so the next pause/resume cycle of the new revision is not mistaken for
 *   another change.
 *
 * No full-file hashing and no cache copy is involved: the Aliyun resumable SDK owns the checkpoint,
 * and this snapshot only guards against continuing it with bytes of another revision.
 */
object UploadSourceSnapshot {

    /**
     * Validates the frozen OSS scope first, then builds the transfer for the next attempt.
     *
     * A ticket that left the frozen scope is rejected outright, so the caller has nothing to persist
     * and nothing to delete: the existing resumable task, its checkpoint and its transfer record are
     * all preserved for explicit user recovery or cancel.
     */
    fun prepare(
        current: UploadTransfer,
        stats: UploadSourceStats,
        ticket: OssStsToken,
        checkpointDirectory: String,
    ): UploadAttemptPreparation {
        val field = UploadScopeGuard.mismatch(UploadTargetScope.of(current), ticket)
        if (field != null) return UploadAttemptPreparation.Rejected(field)
        return UploadAttemptPreparation.Accepted(plan(current, stats, ticket, checkpointDirectory))
    }

    /**
     * True when the persisted snapshot describes a different revision than [stats].
     *
     * Unknown persisted values (`0`) cannot prove a change, and a provider that reports no
     * last-modified value is ignored rather than treated as a modification.
     */
    fun hasChanged(
        persistedSize: Long,
        persistedLastModified: Long,
        stats: UploadSourceStats,
    ): Boolean {
        if (persistedSize > 0L && stats.size != persistedSize) return true
        return persistedLastModified > 0L && stats.lastModified > 0L && stats.lastModified != persistedLastModified
    }

    /**
     * Builds the transfer for the next attempt.
     *
     * The OSS scope is frozen from [ticket] (never re-pointed), a previously unknown source snapshot is
     * filled in silently, and an accepted source change adopts the new snapshot while dropping the
     * multipart identity (`uploadId`, progress) of the discarded upload.
     */
    fun plan(
        current: UploadTransfer,
        stats: UploadSourceStats,
        ticket: OssStsToken,
        checkpointDirectory: String,
    ): UploadSourcePlan {
        val changed = hasChanged(current.expectedSize, current.lastModified, stats)
        val scope = UploadScopeGuard.freeze(UploadTargetScope.of(current), ticket)
        return UploadSourcePlan(
            sourceChanged = changed,
            transfer = current.copy(
                bucket = scope.bucket,
                region = scope.region,
                objectKey = scope.objectKey,
                checkpointDir = checkpointDirectory,
                // A learned value replaces an unknown one without counting as a modification, and an
                // accepted change always adopts the revision that is now being uploaded.
                expectedSize = if (changed || current.expectedSize <= 0L) stats.size else current.expectedSize,
                lastModified = if (changed || current.lastModified <= 0L) {
                    stats.lastModified
                } else {
                    current.lastModified
                },
                fileBytes = if (changed) stats.size.coerceAtLeast(1L) else current.fileBytes,
                uploadId = if (changed) "" else current.uploadId,
                transferredBytes = if (changed) 0L else current.transferredBytes,
                notice = if (changed) TransferNotice.SOURCE_CHANGED else current.notice,
            ),
        )
    }
}
