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
 * The worker is a step of the sequential WorkManager upload chain and runs as a long-running job
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
            // Work enqueued by an older app version; it has no resumable identity to work with.
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
            // Belongs to another signed-in account: never upload it here.
            transferStore.removeUpload(transferId)
            return Result.success(resultData(transfer, OUTCOME_CANCELED))
        }

        val accessToken = account.accessTokenOr(inputData.getString(KEY_INPUT_ACCESS_TOKEN).orEmpty())
        if (accessToken.isBlank()) {
            return Result.success(
                failureData(transfer, null, IllegalStateException("Missing upload session data"))
            )
        }

        return try {
            if (transfer.isMetadataPending) {
                finalizeMetadata(transfer, accessToken)
            } else {
                runTransfer(transfer, accessToken)
            }
        } catch (cancellation: CancellationException) {
            // Pause or cancel: the resumable state is already persisted by the repository.
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
    private suspend fun finalizeMetadata(transfer: UploadTransfer, accessToken: String): Result {
        val progress = TransferProgress(transfer).apply { markFinalizing() }
        val foregroundFailure = promoteToForeground(transfer, progress)
        if (foregroundFailure != null) {
            return Result.success(failureData(transfer, progress, UploadForegroundException(foregroundFailure)))
        }

        try {
            uploadMetadata(transfer, accessToken)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Log.w(TAG, "Metadata insert failed for ${transfer.displayName}", throwable)
            return Result.success(failureData(transfer, progress, throwable))
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

        val sourceChanged = sourceChanged(transfer, stats)
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

        // A ticket for a different object can never continue this transfer's multipart upload.
        val objectMoved = transfer.objectKey.isNotBlank() && ticket.objectKey != transfer.objectKey
        val checkpointDirectory = transfer.checkpointDir.ifBlank {
            transferRepository.checkpointDirectoryPath(transfer.transferId)
        }

        var working = transferStore.updateUpload(transfer.transferId) { current ->
            current.copy(
                bucket = ticket.bucket,
                region = ticket.region,
                objectKey = ticket.objectKey,
                checkpointDir = checkpointDirectory,
                uploadId = if (objectMoved) "" else current.uploadId,
                notice = when {
                    objectMoved -> current.notice
                    sourceChanged -> TransferNotice.SOURCE_CHANGED
                    else -> current.notice
                },
            )
        } ?: transfer.copy(
            bucket = ticket.bucket,
            region = ticket.region,
            objectKey = ticket.objectKey,
            checkpointDir = checkpointDirectory,
        )

        if (objectMoved) {
            transferRepository.deleteCheckpointDirectory(checkpointDirectory)
        }

        if (sourceChanged) {
            // The local file is not the one the checkpoint was built from: throw the old upload away
            // instead of continuing it with bytes from a different revision.
            Log.i(TAG, "Upload source changed for ${transfer.displayName}; restarting the upload")
            abortQuietly(working, ticket, accessToken)
            transferRepository.deleteCheckpointDirectory(checkpointDirectory)
            working = transferStore.updateUpload(transfer.transferId) { current ->
                current.copy(uploadId = "")
            } ?: working
        }

        val persisted = working
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

        // CompleteMultipartUpload returned: persist the metadata-pending phase before anything else,
        // so a retry after this point can only ever repeat the backend insert.
        persistMetadataPending(persisted, ticket)

        transferRepository.deleteCheckpointDirectory(checkpointDirectory)
        uploadMetadata(persisted, accessToken)
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

    private suspend fun persistMetadataPending(transfer: UploadTransfer, ticket: OssStsToken) {
        // NonCancellable: once completion was observed it must never be forgotten, not even when the
        // user cancels in this exact instant. The object is complete either way.
        withContext(NonCancellable) {
            transferStore.updateUpload(transfer.transferId) { current ->
                current.copy(
                    phase = UploadPhase.METADATA_PENDING,
                    uploadId = "",
                    bucket = ticket.bucket,
                    region = ticket.region,
                    objectKey = ticket.objectKey,
                    notice = TransferNotice.NONE,
                )
            }
            Unit
        }
        setProgress(TransferProgress(transfer).apply { markFinalizing() }.toWorkData())
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
            abortQuietly(transfer, ticket, accessToken)
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

    private fun sourceChanged(transfer: UploadTransfer, stats: UploadSourceStats): Boolean {
        if (transfer.expectedSize > 0L && stats.size != transfer.expectedSize) return true
        return transfer.lastModified > 0L && stats.lastModified > 0L && stats.lastModified != transfer.lastModified
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
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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
        const val ERROR_KIND_OTHER = "other"

        private const val TAG = "UploadWorker"
        private const val OSS_USAGE_SINGLE_FILE_UPLOAD = "SINGLE_FILE_UPLOAD"
        private const val MAX_ERROR_DETAIL_LENGTH = 200
        private const val PROGRESS_PUBLISH_INTERVAL_MILLIS = 500L
        private const val PROGRESS_PERSIST_INTERVAL_MILLIS = 2_000L
        private const val FOREGROUND_PROMOTION_TIMEOUT_MILLIS = 30_000L
        private const val NOTIFICATION_CHANNEL_ID = "notes-file-upload"
        private const val NOTIFICATION_ID = 1001
        private const val PROGRESS_MAX = 100

        /** Keeps the last percent reserved for the metadata commit. */
        private const val FINALIZING_FRACTION = 0.99f
    }
}
