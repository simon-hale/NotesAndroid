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
