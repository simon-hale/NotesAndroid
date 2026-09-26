package com.notes.notes.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.notes.notes.core.DownloadPhase
import com.notes.notes.core.DownloadTransfer
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Identifies the download work this app just enqueued. */
data class EnqueuedDownloadBatch(
    val workIds: Set<UUID>,
    val transferIds: Set<String>,
)

/**
 * The business result a finished download work item reports.
 *
 * The result is part of the persisted work item, which is what makes it recoverable: an app that starts
 * after the fact still sees that a download failed instead of having to guess from a missing worker.
 */
enum class DownloadWorkOutcome {
    /** The item carried no result at all, for example a work item that was cancelled before it ran. */
    NONE,
    SUCCEEDED,
    FAILED,

    /** A pause, a cancel or a refused start: never a failure. */
    CANCELED,
}

fun downloadWorkOutcome(rawOutcome: String?): DownloadWorkOutcome = when (rawOutcome) {
    DownloadWorker.OUTCOME_SUCCESS -> DownloadWorkOutcome.SUCCEEDED
    DownloadWorker.OUTCOME_FAILED -> DownloadWorkOutcome.FAILED
    DownloadWorker.OUTCOME_CANCELED -> DownloadWorkOutcome.CANCELED
    else -> DownloadWorkOutcome.NONE
}

/**
 * Decides whether a failed work item still has to be written to [record].
 *
 * The record's own phase owns the decision: only a transfer that still claims to be running is the
 * attempt this result belongs to. A record the user paused, a destructive cancel in flight and an
 * already failed transfer are never turned into a failure — whether or not this process watched the
 * attempt run — and a newer attempt that is still running owns the state as well.
 */
fun shouldRecordDownloadFailure(
    record: DownloadTransfer?,
    supersededByLiveWork: Boolean,
): Boolean = !supersededByLiveWork && record?.phase?.isTransferring == true

/**
 * The transfers whose destructive cancel was interrupted by process death.
 *
 * They are not resumable and must not be shown as paused; the next start finishes what the cancel began,
 * so no transfer can stay in that state forever.
 */
fun interruptedCancels(transfers: List<DownloadTransfer>): List<DownloadTransfer> =
    transfers.filter { it.phase == DownloadPhase.CANCELING }

/**
 * Owns the download queue in WorkManager.
 *
 * Every logical download gets its own unique work name derived from its transfer id, so Pause,
 * Resume and Cancel always act on exactly one transfer and never on an unrelated one.
 */
object DownloadWork {

    const val TAG = "notes-file-download"

    private const val TRANSFER_TAG_PREFIX = "download-transfer:"

    private fun uniqueWorkName(transferId: String) = "notes-file-download-$transferId"

    fun transferId(info: WorkInfo): String? =
        info.tags.firstOrNull { it.startsWith(TRANSFER_TAG_PREFIX) }?.removePrefix(TRANSFER_TAG_PREFIX)

    fun transferWorkTag(transferId: String): String = "$TRANSFER_TAG_PREFIX$transferId"

    /** Enqueues one independent download per transfer, each with its own unique identity. */
    fun enqueue(context: Context, accessToken: String, transfers: List<DownloadTransfer>): EnqueuedDownloadBatch? {
        if (transfers.isEmpty()) return null
        val workManager = WorkManager.getInstance(context)
        val workIds = mutableSetOf<UUID>()
        transfers.forEach { transfer ->
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(
                    workDataOf(
                        DownloadWorker.KEY_INPUT_TRANSFER_ID to transfer.transferId,
                        DownloadWorker.KEY_INPUT_ACCESS_TOKEN to accessToken,
                        // Binds the fallback token to the owner that enqueued this work. No credentials
                        // beyond the session token are ever placed in WorkManager data.
                        DownloadWorker.KEY_INPUT_ACCOUNT_KEY to transfer.accountKey,
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(TAG)
                .addTag(transferWorkTag(transfer.transferId))
                .build()
            workIds += request.id
            workManager.enqueueUniqueWork(
                uniqueWorkName(transfer.transferId),
                // New transfers have unique ids. Resume explicitly replaces any stale WorkManager
                // instance left by the preceding pause/cancellation.
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
        return EnqueuedDownloadBatch(
            workIds = workIds,
            transferIds = transfers.mapTo(mutableSetOf()) { it.transferId },
        )
    }

    /** Stops the work of one download. Pausing and cancelling both use this. */
    fun cancelTransfer(context: Context, transferId: String): Operation =
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(transferId))

    /** Blocks until the cancellation is persisted, bounded by [timeoutMillis]. */
    fun awaitCancellation(operation: Operation, timeoutMillis: Long) {
        runCatching { operation.result.get(timeoutMillis, TimeUnit.MILLISECONDS) }
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }

    fun workInfos(context: Context): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG)
}
