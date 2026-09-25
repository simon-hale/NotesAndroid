package com.notes.notes.data

import android.content.Context
import androidx.work.BackoffPolicy
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
 * Identifies a group of upload work items created together.
 *
 * batchId is retained only for progress/result bookkeeping. Every logical file is scheduled as an
 * independent unique WorkManager work item, so pause/resume/delete of one file can never cancel the
 * work of another file in the same batch.
 */
data class EnqueuedUploadBatch(
    val batchId: String,
    val workIds: Set<UUID>,
    val transferIds: Set<String>,
)

/**
 * Owns persistent upload work.
 *
 * Every logical transfer gets its own unique work name derived from transferId. Files selected in one
 * picker operation still belong to the same logical batch and target directory, but their WorkManager
 * lifecycles are independent.
 */
object UploadWork {

    const val TAG = "notes-file-upload"

    private const val URI_TAG_PREFIX = "upload-uri:"
    private const val TRANSFER_TAG_PREFIX = "upload-transfer:"

    private fun uniqueWorkName(transferId: String): String =
        "notes-file-upload-$transferId"

    /** The source document associated with this work item. */
    fun sourceUri(info: WorkInfo): String? =
        info.tags
            .firstOrNull { it.startsWith(URI_TAG_PREFIX) }
            ?.removePrefix(URI_TAG_PREFIX)

    /** The persistent transfer record associated with this work item. */
    fun transferId(info: WorkInfo): String? =
        info.tags
            .firstOrNull { it.startsWith(TRANSFER_TAG_PREFIX) }
            ?.removePrefix(TRANSFER_TAG_PREFIX)

    fun transferWorkTag(transferId: String): String =
        "$TRANSFER_TAG_PREFIX$transferId"

    /**
     * Enqueues every transfer independently.
     *
     * ExistingWorkPolicy.KEEP protects a currently running instance from accidental duplicate enqueue.
     * Pause waits for cancellation before resume enqueues another instance with the same unique name.
     */
    fun enqueue(
        context: Context,
        accessToken: String,
        transfers: List<UploadTransfer>,
    ): EnqueuedUploadBatch? {
        if (transfers.isEmpty()) return null

        val workManager = WorkManager.getInstance(context)
        val workIds = mutableSetOf<UUID>()

        transfers.forEach { transfer ->
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(
                    workDataOf(
                        UploadWorker.KEY_INPUT_TRANSFER_ID to transfer.transferId,
                        UploadWorker.KEY_INPUT_ACCESS_TOKEN to accessToken,
                        UploadWorker.KEY_INPUT_ACCOUNT_KEY to transfer.accountKey,
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                // Used by METADATA_PENDING automatic retries. The real OSS transfer does not normally
                // depend on this backoff because the OSS SDK performs its own request-level retries.
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30L,
                    TimeUnit.SECONDS,
                )
                .addTag(TAG)
                .addTag("$URI_TAG_PREFIX${transfer.sourceUri}")
                .addTag(transferWorkTag(transfer.transferId))
                .build()

            workIds += request.id

            workManager.enqueueUniqueWork(
                uniqueWorkName(transfer.transferId),
                // A transfer is enqueued only when it is newly created or explicitly resumed.
                // REPLACE removes a stale/cancelling instance left by the previous pause so one
                // tap on Resume is always sufficient.
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        return EnqueuedUploadBatch(
            batchId = transfers.first().batchId,
            workIds = workIds,
            transferIds = transfers.mapTo(mutableSetOf()) { it.transferId },
        )
    }

    /**
     * Stops only the requested logical transfers.
     *
     * There are no dependency chains, therefore cancelling transfer A cannot affect B/C/D.
     */
    fun cancelTransfers(
        context: Context,
        transferIds: Collection<String>,
    ): List<Operation> {
        if (transferIds.isEmpty()) return emptyList()

        val workManager = WorkManager.getInstance(context)
        return transferIds.map { transferId ->
            workManager.cancelUniqueWork(uniqueWorkName(transferId))
        }
    }

    /** Waits until cancellation requests have been persisted, with a bounded wait per transfer. */
    fun awaitCancellations(
        operations: List<Operation>,
        timeoutMillis: Long,
    ) {
        operations.forEach { operation ->
            runCatching {
                operation.result.get(timeoutMillis, TimeUnit.MILLISECONDS)
            }
        }
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }

    fun workInfos(context: Context): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG)

    suspend fun hasPendingUploads(context: Context): Boolean =
        workInfos(context).first().any { !it.state.isFinished }
}