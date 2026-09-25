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
import com.notes.notes.core.UploadTransfer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Identifies a batch of upload work this app just enqueued: its [batchId] groups the work items for
 * progress accounting and [workIds] lets the UI watch exactly the completions of this batch.
 */
data class EnqueuedUploadBatch(
    val batchId: String,
    val workIds: Set<UUID>,
    val transferIds: Set<String>,
)

/**
 * Owns the upload queue in WorkManager so that transfers outlive the ViewModel (backgrounding, screen
 * lock, rotation) and are restored after a process restart.
 *
 * Every work item carries the id of the logical transfer it belongs to. That id is what Pause,
 * Resume and Cancel act on: cancelling by transfer tag stops exactly the intended chain and leaves
 * unrelated transfers alone.
 */
object UploadWork {

    const val TAG = "notes-file-upload"

    private const val UNIQUE_WORK_NAME = "notes-file-upload-queue"

    /** Every work item is tagged with the document it uploads, so orphaned permissions can be found. */
    private const val URI_TAG_PREFIX = "upload-uri:"

    /** Tag that ties one work item to one persistent transfer record. */
    private const val TRANSFER_TAG_PREFIX = "upload-transfer:"

    /** The document uploaded by [info], or `null` for work items enqueued without a tagged uri. */
    fun sourceUri(info: WorkInfo): String? =
        info.tags.firstOrNull { it.startsWith(URI_TAG_PREFIX) }?.removePrefix(URI_TAG_PREFIX)

    /** The logical transfer uploaded by [info], or `null` for pre-transfer work items. */
    fun transferId(info: WorkInfo): String? =
        info.tags.firstOrNull { it.startsWith(TRANSFER_TAG_PREFIX) }?.removePrefix(TRANSFER_TAG_PREFIX)

    fun transferWorkTag(transferId: String): String = "$TRANSFER_TAG_PREFIX$transferId"

    /**
     * Enqueues [transfers] as one sequential chain, or returns `null` when there is nothing to do.
     *
     * Files stay serialised exactly as before: a new file only starts after the previous one settled.
     */
    fun enqueue(
        context: Context,
        accessToken: String,
        transfers: List<UploadTransfer>,
    ): EnqueuedUploadBatch? {
        if (transfers.isEmpty()) return null
        val workManager = WorkManager.getInstance(context)
        val requests = transfers.map { transfer ->
            OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(
                    workDataOf(
                        UploadWorker.KEY_INPUT_TRANSFER_ID to transfer.transferId,
                        UploadWorker.KEY_INPUT_ACCESS_TOKEN to accessToken,
                        // Binds the fallback token to the owner that enqueued this work. No credentials
                        // beyond the session token are ever placed in WorkManager data.
                        UploadWorker.KEY_INPUT_ACCOUNT_KEY to transfer.accountKey,
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(TAG)
                .addTag("$URI_TAG_PREFIX${transfer.sourceUri}")
                .addTag(transferWorkTag(transfer.transferId))
                .build()
        }

        var continuation = workManager.beginUniqueWork(
            UNIQUE_WORK_NAME,
            // A cancelled (paused) chain is finished work, so enqueuing a resume creates a new chain.
            ExistingWorkPolicy.KEEP,
            requests.first(),
        )
        requests.drop(1).forEach { request ->
            continuation = continuation.then(request)
        }
        continuation.enqueue()

        return EnqueuedUploadBatch(
            batchId = transfers.first().batchId,
            workIds = requests.mapTo(mutableSetOf()) { request -> request.id },
            transferIds = transfers.mapTo(mutableSetOf()) { transfer -> transfer.transferId },
        )
    }

    /**
     * Stops the work of the given transfers. Pausing and cancelling both use this.
     *
     * Returns the cancellation operations so callers can wait for the stop to be persisted before,
     * for example, deleting a checkpoint the running worker still holds open.
     */
    fun cancelTransfers(context: Context, transferIds: Collection<String>): List<Operation> {
        if (transferIds.isEmpty()) return emptyList()
        val workManager = WorkManager.getInstance(context)
        return transferIds.map { transferId ->
            workManager.cancelAllWorkByTag(transferWorkTag(transferId))
        }
    }

    /** Blocks until the given cancellations are persisted, bounded by [timeoutMillis]. */
    fun awaitCancellations(operations: List<Operation>, timeoutMillis: Long) {
        operations.forEach { operation ->
            runCatching { operation.result.get(timeoutMillis, TimeUnit.MILLISECONDS) }
        }
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }

    fun workInfos(context: Context): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG)

    /** True while an upload is still queued or running, for example after a process restart. */
    suspend fun hasPendingUploads(context: Context): Boolean =
        workInfos(context).first().any { !it.state.isFinished }
}
