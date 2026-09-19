package com.notes.notes.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.notes.notes.core.AppLanguage
import com.notes.notes.core.UploadCandidate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Identifies a batch that this app just enqueued: its [batchId] groups the work items for progress
 * accounting and [workIds] lets the UI handle exactly the completions of this batch.
 */
data class EnqueuedUploadBatch(
    val batchId: String,
    val workIds: Set<UUID>,
)

/**
 * Owns the upload queue in WorkManager so that transfers outlive the ViewModel (backgrounding, screen
 * lock, rotation) and are restored after a process restart.
 */
object UploadWork {

    const val TAG = "notes-file-upload"

    private const val UNIQUE_WORK_NAME = "notes-file-upload-queue"

    /** Every work item is tagged with the document it uploads, so orphaned permissions can be found. */
    private const val URI_TAG_PREFIX = "upload-uri:"

    /** The document uploaded by [info], or `null` for work items enqueued without a tagged uri. */
    fun sourceUri(info: WorkInfo): String? =
        info.tags.firstOrNull { it.startsWith(URI_TAG_PREFIX) }?.removePrefix(URI_TAG_PREFIX)

    /** Enqueues [candidates] as one sequential batch, or returns `null` when there is nothing to do. */
    fun enqueue(
        context: Context,
        accessToken: String,
        language: AppLanguage,
        parentId: Long,
        pathString: String,
        candidates: List<UploadCandidate>,
    ): EnqueuedUploadBatch? {
        if (candidates.isEmpty()) return null
        val workManager = WorkManager.getInstance(context)

        // Files are weighted by size, so the UI can report progress across the whole batch by bytes.
        val fileBytes = candidates.map { it.sizeBytes.coerceAtLeast(1L) }
        val batchTotalBytes = fileBytes.sum()
        // Distinguishes the files of this batch from the previous, already finished ones.
        val batchId = "batch-${System.currentTimeMillis()}"

        val requests = candidates.mapIndexed { index, candidate ->
            OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(
                    workDataOf(
                        UploadWorker.KEY_INPUT_ACCESS_TOKEN to accessToken,
                        UploadWorker.KEY_INPUT_LANGUAGE to language.code,
                        UploadWorker.KEY_INPUT_PARENT_ID to parentId,
                        UploadWorker.KEY_INPUT_PATH_STRING to pathString,
                        UploadWorker.KEY_INPUT_URI to candidate.uriString,
                        UploadWorker.KEY_INPUT_DISPLAY_NAME to candidate.displayName,
                        UploadWorker.KEY_INPUT_BATCH_ID to batchId,
                        UploadWorker.KEY_INPUT_FILE_BYTES to fileBytes[index],
                        UploadWorker.KEY_INPUT_BATCH_TOTAL_BYTES to batchTotalBytes,
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(TAG)
                .addTag("$URI_TAG_PREFIX${candidate.uriString}")
                .build()
        }

        // Chained requests upload one file after another, as the web client does.
        var continuation = workManager.beginUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            requests.first(),
        )
        requests.drop(1).forEach { request ->
            continuation = continuation.then(request)
        }
        continuation.enqueue()
        return EnqueuedUploadBatch(
            batchId = batchId,
            workIds = requests.mapTo(mutableSetOf()) { request -> request.id },
        )
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
