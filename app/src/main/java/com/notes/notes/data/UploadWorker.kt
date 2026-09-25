package com.notes.notes.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.notes.notes.MainActivity
import com.notes.notes.R
import com.notes.notes.core.AppLanguage
import com.notes.notes.core.TransferNotice
import com.notes.notes.core.UploadPhase
import com.notes.notes.core.UploadTransfer
import com.notes.notes.core.stringsFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToInt

/** Raised when the upload cannot be promoted to a foreground service before the transfer starts. */
class UploadForegroundException(cause: Throwable?) :
    Exception("Unable to run the upload as a foreground service", cause)

/**
 * Runs one logical upload transfer.
 *
 * The worker owns one independent logical upload scheduled through WorkManager and runs as a long-running job
 * promoted to a `dataSync` foreground service, so an upload started by the user keeps running while
 * the app is backgrounded or the screen is locked.
 *
 * All state that outlives the process lives in [TransferStore]; the work input only carries the
 * transfer id plus a fallback session token. Credentials are never persisted, and the upload target
 * is always the frozen one recorded in the transfer, never the directory the UI currently shows.
 */
class UploadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    private val backendService = NotesBackendService()
    private val transferRepository = FileTransferRepository(applicationContext)
    private val transferStore = TransferStore(applicationContext)

    override suspend fun doWork(): Result {
        val transferId = inputData.getString(KEY_INPUT_TRANSFER_ID).orEmpty()
        if (transferId.isBlank()) {
            Log.w(TAG, "Upload work enqueued without a transfer id")
            return Result.success()
        }

        val transfer = transferStore.upload(transferId)
        if (transfer == null) {
            Log.w(TAG, "Upload work for an unknown transfer")
            return Result.success(resultData(transferId, "", "", OUTCOME_CANCELED))
        }

        val account = TransferAccount.current(applicationContext)
        if (!account.owns(transfer.accountKey)) {
            Log.w(TAG, "Upload work is owned by another account; leaving it untouched")
            return Result.success(resultData(transfer, OUTCOME_CANCELED))
        }

        val accessToken = account.accessTokenFor(
            fallback = inputData.getString(KEY_INPUT_ACCESS_TOKEN).orEmpty(),
            transferAccountKey = transfer.accountKey,
            workAccountKey = inputData.getString(KEY_INPUT_ACCOUNT_KEY).orEmpty(),
        )
        if (accessToken.isBlank()) {
            return Result.success(
                failureData(
                    transfer,
                    null,
                    IllegalStateException("Missing upload session data"),
                )
            )
        }

        // A pause is persisted before WorkManager cancellation is requested. If the worker happens to
        // start inside that very small race window, fail closed instead of starting OSS traffic.
        if (transfer.phase == UploadPhase.PAUSED) {
            return Result.success(resultData(transfer, OUTCOME_CANCELED))
        }

        return try {
            if (transfer.isMetadataPending) {
                finalizeMetadata(transfer, accessToken)
            } else {
                runTransfer(transfer, accessToken)
            }
        } catch (cancellation: CancellationException) {
            // Pause/delete: resumable state has already been handled by the owner of the action.
            throw cancellation
        } catch (throwable: Throwable) {
            Log.w(TAG, "Upload failed for ${transfer.displayName}", throwable)
            Result.success(failureData(transfer, null, throwable))
        }
    }

    /**
     * `METADATA_PENDING`: the object is already complete in OSS, only the backend row is missing.
     * The file must never be uploaded again from this state.
     */
    private suspend fun finalizeMetadata(
        transfer: UploadTransfer,
        accessToken: String,
    ): Result {
        // The OSS object already exists. This step is only a short backend metadata request, so there is
        // no need to promote it to another long-running foreground transfer.
        try {
            uploadMetadata(transfer, accessToken)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Log.w(TAG, "Metadata insert failed for ${transfer.displayName}; retrying", throwable)

            // The backend insert path is idempotent/convergent. Keep METADATA_PENDING persisted and let
            // WorkManager retry with backoff rather than exposing another UI action.
            return Result.retry()
        }

        completeTransfer(transfer)
        return Result.success(resultData(transfer, OUTCOME_SUCCESS))
    }

    /** `TRANSFERRING` / `PAUSED`: run (or resume) the OSS resumable upload, then commit metadata. */
    private suspend fun runTransfer(transfer: UploadTransfer, accessToken: String): Result {
        val sourceUri = Uri.parse(transfer.sourceUri)
        val stats = try {
            transferRepository.readSourceStats(sourceUri)
        } catch (unavailable: UploadSourceUnavailableException) {
            // The document is gone for good: the resumable task is no longer usable.
            Log.w(TAG, "Upload source is unavailable for ${transfer.displayName}", unavailable)
            discardTransfer(transfer, accessToken)
            return Result.success(failureData(transfer, null, unavailable))
        } catch (unknownSize: UploadSourceException) {
            return Result.success(failureData(transfer, null, unknownSize))
        }

        val progress = TransferProgress(transfer)
        val foregroundFailure = promoteToForeground(transfer, progress)
        if (foregroundFailure != null) {
            Log.w(TAG, "Unable to run the upload as a foreground service", foregroundFailure)
            return Result.success(failureData(transfer, progress, UploadForegroundException(foregroundFailure)))
        }
        setProgress(progress.toWorkData())

        val ticket = backendService.requestOssSts(
            accessToken = accessToken,
            pathString = transfer.pathString,
            filename = transfer.displayName,
            parentId = transfer.parentId,
            language = AppLanguage.fromCode(transfer.language),
            usage = OSS_USAGE_SINGLE_FILE_UPLOAD,
        )
        if (ticket.overwriteSameName) {
            progress.markOverwriteSameName()
        }

        // The OSS identity is frozen once and validated on every later attempt. A ticket that points
        // somewhere else fails closed: the resumable task is preserved for explicit recovery or
        // cancel, and nothing is deleted or re-uploaded under the new scope.
        val checkpointDirectory = transfer.checkpointDir.ifBlank {
            transferRepository.checkpointDirectoryPath(transfer.transferId)
        }
        val preparation = UploadSourceSnapshot.prepare(
            current = transfer,
            stats = stats,
            ticket = ticket,
            checkpointDirectory = checkpointDirectory,
        )
        val plan = when (preparation) {
            is UploadAttemptPreparation.Rejected -> {
                Log.w(TAG, "OSS target changed for ${transfer.displayName}: ${preparation.field}")
                return Result.success(
                    failureData(
                        transfer,
                        progress,
                        UploadTargetChangedException(
                            preparation.field,
                            "OSS target changed: ${preparation.field}",
                        ),
                    )
                )
            }

            is UploadAttemptPreparation.Accepted -> preparation.plan
        }
        val persisted = transferStore.updateUpload(transfer.transferId) { current ->
            // The plan was derived from this worker's own snapshot of the record, which is the only
            // writer for this transfer while it runs; the phase stays whatever the store says so a
            // queued pause is never reverted by a persistence step.
            plan.transfer.copy(phase = current.phase)
        } ?: plan.transfer

        if (plan.sourceChanged) {
            // The local file is not the one the checkpoint was built from: throw the old upload away
            // instead of continuing it with bytes from a different revision, and adopt the revision
            // that is being uploaded now so the next resume of it is not invalidated again.
            Log.i(TAG, "Upload source changed for ${transfer.displayName}; restarting the upload")
            abortQuietly(transfer, ticket, accessToken)
            transferRepository.deleteCheckpointDirectory(checkpointDirectory)
        }

        val credentialProvider = TransferStsCredentialProvider(
            transfer = persisted,
            accessToken = accessToken,
            backendService = backendService,
            // The provider re-issues tickets for the same frozen target and rejects a ticket that
            // moved to another bucket, region or object key, so the upload identity never changes.
            firstTicket = ticket,
        )

        uploadWithProgress(
            transfer = persisted,
            sourceUri = sourceUri,
            sizeBytes = stats.size,
            ticket = ticket,
            checkpointDirectory = checkpointDirectory,
            credentialProvider = credentialProvider,
            progress = progress,
        )

        // CompleteMultipartUpload returned successfully. Persist this transition before performing any
// backend bookkeeping so this object can never be uploaded a second time.
        persistMetadataPending(persisted)

// Multipart is complete, therefore its local checkpoint is no longer useful.
        transferRepository.deleteCheckpointDirectory(checkpointDirectory)

        try {
            uploadMetadata(persisted, accessToken)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Log.w(
                TAG,
                "Metadata insert failed for ${persisted.displayName}; retrying metadata only",
                throwable,
            )

            // The object is already complete. Never restart OSS upload from here.
            return Result.retry()
        }

        completeTransfer(persisted)
        return Result.success(resultData(persisted, OUTCOME_SUCCESS))
    }

    private suspend fun uploadWithProgress(
        transfer: UploadTransfer,
        sourceUri: Uri,
        sizeBytes: Long,
        ticket: OssStsToken,
        checkpointDirectory: String,
        credentialProvider: TransferStsCredentialProvider,
        progress: TransferProgress,
    ) = coroutineScope {
        val publisher = launch {
            var lastPersistedAt = 0L
            while (isActive) {
                delay(PROGRESS_PUBLISH_INTERVAL_MILLIS)
                if (progress.hasNewProgress()) {
                    setProgress(progress.toWorkData())
                    updateNotification(transfer, progress)
                }
                val now = System.currentTimeMillis()
                if (now - lastPersistedAt >= PROGRESS_PERSIST_INTERVAL_MILLIS) {
                    lastPersistedAt = now
                    val bytes = progress.transferredBytes()
                    if (bytes > 0L) {
                        withContext(NonCancellable) {
                            transferStore.updateUpload(transfer.transferId) { current ->
                                if (current.transferredBytes == bytes) {
                                    current
                                } else {
                                    current.copy(transferredBytes = bytes)
                                }
                            }
                        }
                    }
                }
            }
        }
        try {
            transferRepository.uploadToOss(
                ticket = ticket,
                objectKey = ticket.objectKey,
                sourceUri = sourceUri,
                sizeBytes = sizeBytes,
                checkpointDirectory = checkpointDirectory,
                credentialProvider = credentialProvider,
                onProgress = { currentBytes, totalBytes ->
                    progress.update(currentBytes, totalBytes)
                },
                onUploadId = { uploadId ->
                    // Captured while the SDK task runs, so a destructive cancel can abort the
                    // multipart upload without reading the SDK's private checkpoint file.
                    transferStore.updateUpload(transfer.transferId) { current ->
                        if (current.uploadId == uploadId) current else current.copy(uploadId = uploadId)
                    }
                    Unit
                },
            )
        } finally {
            publisher.cancel()
        }
    }

    private suspend fun persistMetadataPending(
        transfer: UploadTransfer,
    ) {
        /*
         * CompleteMultipartUpload has already succeeded at this point.
         *
         * From now on the metadata recovery record is mandatory. Even if a delete/logout removed the old
         * TRANSFERRING record in the tiny completion race, TransferStore recreates a METADATA_PENDING
         * record rather than allowing a complete OSS object to become orphaned.
         */
        val metadataPending =
            withContext(NonCancellable) {
                transferStore
                    .markMetadataPendingOrRestore(
                        transfer
                    )
            }

        setProgress(
            TransferProgress(
                metadataPending
            ).apply {
                markFinalizing()
            }.toWorkData()
        )
    }

    private suspend fun uploadMetadata(transfer: UploadTransfer, accessToken: String) {
        backendService.insertFileInfo(
            accessToken = accessToken,
            pathString = transfer.pathString,
            filename = transfer.displayName,
            parentId = transfer.parentId,
            language = AppLanguage.fromCode(transfer.language),
        )
    }

    private suspend fun completeTransfer(transfer: UploadTransfer) {
        transferStore.removeUpload(transfer.transferId)
    }

    /** Best-effort invalidation of a transfer whose resumable upload can no longer be used. */
    private suspend fun discardTransfer(transfer: UploadTransfer, accessToken: String) {
        try {
            val ticket = backendService.requestOssSts(
                accessToken = accessToken,
                pathString = transfer.pathString,
                filename = transfer.displayName,
                parentId = transfer.parentId,
                language = AppLanguage.fromCode(transfer.language),
                usage = OSS_USAGE_SINGLE_FILE_UPLOAD,
            )
            // Only abort inside the frozen scope: credentials for another object cannot abort this
            // multipart upload, and issuing them would misreport the failure as "already gone".
            if (UploadScopeGuard.mismatch(UploadTargetScope.of(transfer), ticket) == null) {
                abortQuietly(transfer, ticket, accessToken)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            // The bucket lifecycle policy removes abandoned parts later.
            Log.w(TAG, "Unable to abort the abandoned upload of ${transfer.displayName}", throwable)
        }
        transferRepository.deleteCheckpointDirectory(transfer.checkpointDir)
        transferStore.removeUpload(transfer.transferId)
    }

    private suspend fun abortQuietly(transfer: UploadTransfer, ticket: OssStsToken, accessToken: String) {
        if (transfer.uploadId.isBlank() || transfer.objectKey.isBlank()) return
        val provider = TransferStsCredentialProvider(
            transfer = transfer,
            accessToken = accessToken,
            backendService = backendService,
            firstTicket = ticket,
        )
        try {
            transferRepository.abortMultipartUpload(
                ticket = ticket,
                objectKey = transfer.objectKey,
                uploadId = transfer.uploadId,
                credentialProvider = provider,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Log.w(TAG, "Unable to abort the multipart upload of ${transfer.displayName}", throwable)
        }
    }

    /** Returns the failure when the worker could not be promoted, or `null` when it is safe to transfer. */
    private suspend fun promoteToForeground(
        transfer: UploadTransfer,
        progress: TransferProgress,
    ): Throwable? = try {
        withTimeout(FOREGROUND_PROMOTION_TIMEOUT_MILLIS) {
            setForeground(createForegroundInfo(transfer, progress))
        }
        null
    } catch (timeout: TimeoutCancellationException) {
        timeout
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        throwable
    }

    private suspend fun updateNotification(transfer: UploadTransfer, progress: TransferProgress) {
        try {
            setForeground(createForegroundInfo(transfer, progress))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            // The transfer is already protected by the promotion done before it started.
            Log.w(TAG, "Unable to refresh the upload notification", throwable)
        }
    }

    private fun createForegroundInfo(
        transfer: UploadTransfer,
        progress: TransferProgress,
    ): ForegroundInfo {
        val context = applicationContext
        val strings = stringsFor(AppLanguage.fromCode(transfer.language))
        val title = strings.common.upload
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (notificationManager != null && notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    title,
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val percent = progress.percent()
        val stateLabel = when {
            progress.isFinalizing -> strings.transfers.finalizingMetadata
            else -> strings.transfers.uploading
        }
        val text = if (progress.fraction > 0f) {
            "${transfer.displayName} · $percent% · $stateLabel"
        } else {
            "${transfer.displayName} · $stateLabel"
        }
        val notification: Notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_upload_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .setProgress(PROGRESS_MAX, if (progress.fraction > 0f) percent else 0, progress.fraction <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(
            notificationId(
                transfer.transferId
            ),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun resultData(transfer: UploadTransfer, outcome: String): Data = resultData(
        transferId = transfer.transferId,
        uriString = transfer.sourceUri,
        displayName = transfer.displayName,
        outcome = outcome,
        batchId = transfer.batchId,
        fileBytes = transfer.fileBytes,
        batchTotalBytes = transfer.batchTotalBytes,
    )

    private fun resultData(
        transferId: String,
        uriString: String,
        displayName: String,
        outcome: String,
        batchId: String = "",
        fileBytes: Long = 0L,
        batchTotalBytes: Long = 0L,
    ): Data = workDataOf(
        KEY_RESULT_TRANSFER_ID to transferId,
        KEY_RESULT_URI to uriString,
        KEY_RESULT_DISPLAY_NAME to displayName,
        KEY_RESULT_OUTCOME to outcome,
        KEY_RESULT_BATCH_ID to batchId,
        KEY_RESULT_FILE_BYTES to fileBytes,
        KEY_RESULT_BATCH_TOTAL_BYTES to batchTotalBytes,
    )

    private fun failureData(
        transfer: UploadTransfer,
        progress: TransferProgress?,
        throwable: Throwable,
    ): Data = workDataOf(
        KEY_RESULT_TRANSFER_ID to transfer.transferId,
        KEY_RESULT_URI to transfer.sourceUri,
        KEY_RESULT_DISPLAY_NAME to transfer.displayName,
        KEY_RESULT_OUTCOME to OUTCOME_FAILED,
        KEY_RESULT_BATCH_ID to transfer.batchId,
        KEY_RESULT_FILE_BYTES to transfer.fileBytes,
        KEY_RESULT_BATCH_TOTAL_BYTES to transfer.batchTotalBytes,
        KEY_RESULT_ERROR_KIND to throwable.errorKind(),
        KEY_RESULT_ERROR_STATUS to throwable.httpStatus(),
        KEY_RESULT_ERROR_DETAIL to throwable.message.orEmpty().take(MAX_ERROR_DETAIL_LENGTH),
        KEY_RESULT_METADATA_PENDING to (progress?.isFinalizing ?: transfer.isMetadataPending),
    )

    private fun Throwable.errorKind(): String = when (this) {
        is UploadForegroundException -> ERROR_KIND_FOREGROUND
        is UploadTargetChangedException -> ERROR_KIND_TARGET_CHANGED
        is UploadSourceUnavailableException -> ERROR_KIND_SOURCE_UNAVAILABLE
        is NotesServiceException.Business -> ERROR_KIND_BUSINESS
        is NotesServiceException.Http -> ERROR_KIND_HTTP
        is NotesServiceException.MissingBaseUrl -> ERROR_KIND_MISSING_BASE_URL
        else -> ERROR_KIND_OTHER
    }

    private fun Throwable.httpStatus(): Int = (this as? NotesServiceException.Http)?.statusCode ?: 0

    /** Progress of the current file inside its upload batch. */
    private class TransferProgress(transfer: UploadTransfer) {
        val batchId: String = transfer.batchId
        val fileBytes: Long = transfer.fileBytes
        val batchTotalBytes: Long = transfer.batchTotalBytes

        @Volatile
        private var transferredBytes = 0L

        @Volatile
        private var sourceBytes = 0L

        @Volatile
        private var overwriteSameName = false

        @Volatile
        var isFinalizing = transfer.isMetadataPending
            private set

        private var publishedTransferredBytes = -1L

        val fraction: Float
            get() = when {
                isFinalizing -> FINALIZING_FRACTION
                sourceBytes > 0L -> (transferredBytes.toFloat() / sourceBytes.toFloat()).coerceIn(0f, 1f)
                else -> 0f
            }

        fun update(currentBytes: Long, totalBytes: Long) {
            this.transferredBytes = currentBytes
            this.sourceBytes = totalBytes
        }

        fun markOverwriteSameName() {
            overwriteSameName = true
        }

        fun markFinalizing() {
            isFinalizing = true
        }

        fun hasNewProgress(): Boolean {
            val bytes = transferredBytes
            if (bytes == publishedTransferredBytes) return false
            publishedTransferredBytes = bytes
            return true
        }

        fun transferredBytes(): Long = transferredBytes

        fun percent(): Int = (fraction * PROGRESS_MAX).roundToInt()

        fun toWorkData(): Data = workDataOf(
            KEY_PROGRESS_BATCH_ID to batchId,
            KEY_PROGRESS_FILE_BYTES to fileBytes,
            KEY_PROGRESS_BATCH_TOTAL_BYTES to batchTotalBytes,
            KEY_PROGRESS_FILE_PROGRESS to fraction,
            KEY_PROGRESS_OVERWRITE_SAME_NAME to overwriteSameName,
            KEY_PROGRESS_FINALIZING to isFinalizing,
        )
    }

    companion object {
        // WorkManager merges a finished worker's output Data into the input Data of the next worker in
        // the chain, and the merge order is not guaranteed. Input, progress and result keys therefore
        // never share a name, so a worker always reads its own file instead of the previous result.
        const val KEY_INPUT_TRANSFER_ID = "input_transfer_id"
        const val KEY_INPUT_ACCESS_TOKEN = "input_access_token"

        /**
         * Non-secret owner of the transfer this work was enqueued for. It binds the fallback session
         * token to the account that created the work, so credentials of another signed-in account can
         * never be used for it. No credentials are stored here.
         */
        const val KEY_INPUT_ACCOUNT_KEY = "input_account_key"

        const val KEY_PROGRESS_BATCH_ID = "progress_batch_id"
        const val KEY_PROGRESS_FILE_BYTES = "progress_file_bytes"
        const val KEY_PROGRESS_BATCH_TOTAL_BYTES = "progress_batch_total_bytes"
        const val KEY_PROGRESS_FILE_PROGRESS = "progress_file_progress"
        const val KEY_PROGRESS_OVERWRITE_SAME_NAME = "progress_overwrite_same_name"
        const val KEY_PROGRESS_FINALIZING = "progress_finalizing"

        const val KEY_RESULT_TRANSFER_ID = "result_transfer_id"
        const val KEY_RESULT_URI = "result_uri"
        const val KEY_RESULT_DISPLAY_NAME = "result_display_name"
        const val KEY_RESULT_OUTCOME = "result_outcome"
        const val KEY_RESULT_BATCH_ID = "result_batch_id"
        const val KEY_RESULT_FILE_BYTES = "result_file_bytes"
        const val KEY_RESULT_BATCH_TOTAL_BYTES = "result_batch_total_bytes"
        const val KEY_RESULT_ERROR_KIND = "result_error_kind"
        const val KEY_RESULT_ERROR_STATUS = "result_error_status"
        const val KEY_RESULT_ERROR_DETAIL = "result_error_detail"
        const val KEY_RESULT_METADATA_PENDING = "result_metadata_pending"

        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_CANCELED = "canceled"

        const val ERROR_KIND_FOREGROUND = "foreground"
        const val ERROR_KIND_BUSINESS = "business"
        const val ERROR_KIND_HTTP = "http"
        const val ERROR_KIND_MISSING_BASE_URL = "missingBaseUrl"
        const val ERROR_KIND_TARGET_CHANGED = "targetChanged"
        const val ERROR_KIND_OTHER = "other"

        const val ERROR_KIND_SOURCE_UNAVAILABLE = "sourceUnavailable"

        private const val TAG = "UploadWorker"
        private const val OSS_USAGE_SINGLE_FILE_UPLOAD = "SINGLE_FILE_UPLOAD"
        private const val MAX_ERROR_DETAIL_LENGTH = 200
        private const val PROGRESS_PUBLISH_INTERVAL_MILLIS = 500L
        private const val PROGRESS_PERSIST_INTERVAL_MILLIS = 2_000L
        private const val FOREGROUND_PROMOTION_TIMEOUT_MILLIS = 30_000L
        private const val NOTIFICATION_CHANNEL_ID = "notes-file-upload"

        private fun notificationId(
            transferId: String,
        ): Int =
            UPLOAD_NOTIFICATION_NAMESPACE or
                    (
                            transferId.hashCode() and
                                    NOTIFICATION_ID_MASK
                            )
        private const val UPLOAD_NOTIFICATION_NAMESPACE = 0x10000000
        private const val NOTIFICATION_ID_MASK = 0x0FFFFFFF
        private const val PROGRESS_MAX = 100

        /** Keeps the last percent reserved for the metadata commit. */
        private const val FINALIZING_FRACTION = 0.99f
    }
}
