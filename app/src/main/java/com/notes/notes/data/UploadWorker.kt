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
import com.notes.notes.core.stringsFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToInt

/** Raised when the upload cannot be promoted to a foreground service before the transfer starts. */
class UploadForegroundException(cause: Throwable?) :
    Exception("Unable to run the upload as a foreground service", cause)

/**
 * Uploads one picked document: OSS STS token, OSS multipart upload, backend bookkeeping.
 *
 * The worker is part of a sequential WorkManager chain and runs as a long-running job promoted to a
 * `dataSync` foreground service, so an upload started by the user keeps running while the app is
 * backgrounded or the screen is locked.
 *
 * A file that fails for a normal reason is reported as [OUTCOME_FAILED] in the result data instead of
 * failing the work, because WorkManager would cancel every later file of the chain otherwise.
 */
class UploadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    private val backendService = NotesBackendService()
    private val transferRepository = FileTransferRepository(applicationContext)

    override suspend fun doWork(): Result {
        val accessToken = inputData.getString(KEY_INPUT_ACCESS_TOKEN).orEmpty()
        val pathString = inputData.getString(KEY_INPUT_PATH_STRING).orEmpty()
        val language = AppLanguage.fromCode(inputData.getString(KEY_INPUT_LANGUAGE))
        val parentId = inputData.getLong(KEY_INPUT_PARENT_ID, 0L)
        if (accessToken.isBlank() || pathString.isBlank()) {
            // Batch-wide inputs are unusable, so no later file of this chain can succeed either.
            Log.w(TAG, "Upload work enqueued without usable session data")
            return Result.failure(
                failureData(
                    uriString = "",
                    displayName = "",
                    progress = null,
                    throwable = IllegalStateException("Missing upload session data"),
                )
            )
        }

        val displayName = inputData.getString(KEY_INPUT_DISPLAY_NAME).orEmpty()
        val sourceUri = inputData.getString(KEY_INPUT_URI)?.let(Uri::parse)
        if (sourceUri == null || displayName.isBlank()) {
            Log.w(TAG, "Upload work enqueued without a usable source file")
            return Result.success(
                failureData(
                    uriString = inputData.getString(KEY_INPUT_URI).orEmpty(),
                    displayName = displayName,
                    progress = null,
                    throwable = UploadSourceException("Invalid upload source"),
                )
            )
        }

        val progress = UploadProgress(
            batchId = inputData.getString(KEY_INPUT_BATCH_ID).orEmpty(),
            fileBytes = inputData.getLong(KEY_INPUT_FILE_BYTES, 0L),
            batchTotalBytes = inputData.getLong(KEY_INPUT_BATCH_TOTAL_BYTES, 0L),
        )

        // Promotion must succeed before the long transfer, otherwise the upload could be killed halfway.
        val foregroundFailure = promoteToForeground(displayName, language, progress)
        if (foregroundFailure != null) {
            Log.w(TAG, "Unable to run the upload as a foreground service", foregroundFailure)
            return Result.success(
                failureData(
                    uriString = sourceUri.toString(),
                    displayName = displayName,
                    progress = progress,
                    throwable = UploadForegroundException(foregroundFailure),
                )
            )
        }
        setProgress(progress.toWorkData())

        return try {
            val sts = backendService.requestOssSts(
                accessToken = accessToken,
                pathString = pathString,
                filename = displayName,
                parentId = parentId,
                language = language,
                usage = OSS_USAGE_SINGLE_FILE_UPLOAD,
            )
            if (sts.overwriteSameName) {
                progress.markOverwriteSameName()
                setProgress(progress.toWorkData())
            }

            uploadSource(
                sts = sts,
                sourceUri = sourceUri,
                displayName = displayName,
                language = language,
                progress = progress,
            )

            // The object must be in OSS before the backend row is created.
            backendService.insertFileInfo(
                accessToken = accessToken,
                pathString = pathString,
                filename = displayName,
                parentId = parentId,
                language = language,
            )
            Result.success(resultData(sourceUri, displayName, progress, OUTCOME_SUCCESS))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Log.w(TAG, "Upload failed for $displayName", throwable)
            Result.success(failureData(sourceUri.toString(), displayName, progress, throwable))
        }
    }

    private suspend fun uploadSource(
        sts: OssStsToken,
        sourceUri: Uri,
        displayName: String,
        language: AppLanguage,
        progress: UploadProgress,
    ) = coroutineScope {
        // The OSS progress callback runs on the SDK upload threads, so progress is published from here.
        val publisher = launch {
            while (isActive) {
                delay(PROGRESS_PUBLISH_INTERVAL_MILLIS)
                if (progress.hasNewProgress()) {
                    setProgress(progress.toWorkData())
                    updateNotification(displayName, language, progress)
                }
            }
        }
        try {
            transferRepository.uploadToOss(sts, sourceUri) { currentBytes, totalBytes ->
                progress.update(currentBytes, totalBytes)
            }
            progress.markCompleted()
            setProgress(progress.toWorkData())
        } finally {
            publisher.cancel()
        }
    }

    /** Returns the failure when the worker could not be promoted, or `null` when it is safe to upload. */
    private suspend fun promoteToForeground(
        displayName: String,
        language: AppLanguage,
        progress: UploadProgress,
    ): Throwable? = try {
        withTimeout(FOREGROUND_PROMOTION_TIMEOUT_MILLIS) {
            setForeground(createForegroundInfo(displayName, language, progress))
        }
        null
    } catch (timeout: TimeoutCancellationException) {
        timeout
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        throwable
    }

    private suspend fun updateNotification(
        displayName: String,
        language: AppLanguage,
        progress: UploadProgress,
    ) {
        try {
            setForeground(createForegroundInfo(displayName, language, progress))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            // The transfer is already protected by the promotion done before it started.
            Log.w(TAG, "Unable to refresh the upload notification", throwable)
        }
    }

    private fun createForegroundInfo(
        displayName: String,
        language: AppLanguage,
        progress: UploadProgress,
    ): ForegroundInfo {
        val context = applicationContext
        val title = stringsFor(language).common.upload
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
        val text = if (progress.fraction > 0f) "$displayName · $percent%" else displayName
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

    private fun resultData(
        sourceUri: Uri,
        displayName: String,
        progress: UploadProgress,
        outcome: String,
    ): Data = workDataOf(
        KEY_RESULT_URI to sourceUri.toString(),
        KEY_RESULT_DISPLAY_NAME to displayName,
        KEY_RESULT_OUTCOME to outcome,
        KEY_RESULT_BATCH_ID to progress.batchId,
        KEY_RESULT_FILE_BYTES to progress.fileBytes,
        KEY_RESULT_BATCH_TOTAL_BYTES to progress.batchTotalBytes,
    )

    private fun failureData(
        uriString: String,
        displayName: String,
        progress: UploadProgress?,
        throwable: Throwable,
    ): Data {
        val pairs = mutableListOf<Pair<String, Any?>>(
            KEY_RESULT_URI to uriString,
            KEY_RESULT_DISPLAY_NAME to displayName,
            KEY_RESULT_OUTCOME to OUTCOME_FAILED,
            KEY_RESULT_ERROR_KIND to throwable.errorKind(),
            KEY_RESULT_ERROR_STATUS to throwable.httpStatus(),
            KEY_RESULT_ERROR_DETAIL to throwable.message.orEmpty().take(MAX_ERROR_DETAIL_LENGTH),
        )
        if (progress != null) {
            pairs += KEY_RESULT_BATCH_ID to progress.batchId
            pairs += KEY_RESULT_FILE_BYTES to progress.fileBytes
            pairs += KEY_RESULT_BATCH_TOTAL_BYTES to progress.batchTotalBytes
        }
        return workDataOf(*pairs.toTypedArray())
    }

    private fun Throwable.errorKind(): String = when (this) {
        is UploadForegroundException -> ERROR_KIND_FOREGROUND
        is NotesServiceException.Business -> ERROR_KIND_BUSINESS
        is NotesServiceException.Http -> ERROR_KIND_HTTP
        is NotesServiceException.MissingBaseUrl -> ERROR_KIND_MISSING_BASE_URL
        else -> ERROR_KIND_OTHER
    }

    private fun Throwable.httpStatus(): Int = (this as? NotesServiceException.Http)?.statusCode ?: 0

    /** Progress of the current file inside its upload batch. */
    private class UploadProgress(
        val batchId: String,
        val fileBytes: Long,
        val batchTotalBytes: Long,
    ) {
        @Volatile
        private var transferredBytes = 0L

        @Volatile
        private var sourceBytes = 0L

        @Volatile
        private var overwriteSameName = false

        @Volatile
        private var isCompleted = false

        private var publishedTransferredBytes = -1L

        val fraction: Float
            get() = when {
                isCompleted -> 1f
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

        fun markCompleted() {
            isCompleted = true
        }

        fun hasNewProgress(): Boolean {
            val bytes = transferredBytes
            if (bytes == publishedTransferredBytes) return false
            publishedTransferredBytes = bytes
            return true
        }

        fun percent(): Int = (fraction * PROGRESS_MAX).roundToInt()

        fun toWorkData(): Data = workDataOf(
            KEY_PROGRESS_BATCH_ID to batchId,
            KEY_PROGRESS_FILE_BYTES to fileBytes,
            KEY_PROGRESS_BATCH_TOTAL_BYTES to batchTotalBytes,
            KEY_PROGRESS_FILE_PROGRESS to fraction,
            KEY_PROGRESS_OVERWRITE_SAME_NAME to overwriteSameName,
        )
    }

    companion object {
        // WorkManager merges a finished worker's output Data into the input Data of the next worker in
        // the chain, and the merge order is not guaranteed. Input, progress and result keys therefore
        // never share a name, so a worker always reads its own file instead of the previous result.
        const val KEY_INPUT_ACCESS_TOKEN = "input_access_token"
        const val KEY_INPUT_LANGUAGE = "input_language"
        const val KEY_INPUT_PARENT_ID = "input_parent_id"
        const val KEY_INPUT_PATH_STRING = "input_path_string"
        const val KEY_INPUT_URI = "input_uri"
        const val KEY_INPUT_DISPLAY_NAME = "input_display_name"
        const val KEY_INPUT_BATCH_ID = "input_batch_id"
        const val KEY_INPUT_FILE_BYTES = "input_file_bytes"
        const val KEY_INPUT_BATCH_TOTAL_BYTES = "input_batch_total_bytes"

        const val KEY_PROGRESS_BATCH_ID = "progress_batch_id"
        const val KEY_PROGRESS_FILE_BYTES = "progress_file_bytes"
        const val KEY_PROGRESS_BATCH_TOTAL_BYTES = "progress_batch_total_bytes"
        const val KEY_PROGRESS_FILE_PROGRESS = "progress_file_progress"
        const val KEY_PROGRESS_OVERWRITE_SAME_NAME = "progress_overwrite_same_name"

        const val KEY_RESULT_URI = "result_uri"
        const val KEY_RESULT_DISPLAY_NAME = "result_display_name"
        const val KEY_RESULT_OUTCOME = "result_outcome"
        const val KEY_RESULT_BATCH_ID = "result_batch_id"
        const val KEY_RESULT_FILE_BYTES = "result_file_bytes"
        const val KEY_RESULT_BATCH_TOTAL_BYTES = "result_batch_total_bytes"
        const val KEY_RESULT_ERROR_KIND = "result_error_kind"
        const val KEY_RESULT_ERROR_STATUS = "result_error_status"
        const val KEY_RESULT_ERROR_DETAIL = "result_error_detail"

        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_FAILED = "failed"

        const val ERROR_KIND_FOREGROUND = "foreground"
        const val ERROR_KIND_BUSINESS = "business"
        const val ERROR_KIND_HTTP = "http"
        const val ERROR_KIND_MISSING_BASE_URL = "missingBaseUrl"
        const val ERROR_KIND_OTHER = "other"

        private const val TAG = "UploadWorker"
        private const val OSS_USAGE_SINGLE_FILE_UPLOAD = "SINGLE_FILE_UPLOAD"
        private const val MAX_ERROR_DETAIL_LENGTH = 200
        private const val PROGRESS_PUBLISH_INTERVAL_MILLIS = 500L
        private const val FOREGROUND_PROMOTION_TIMEOUT_MILLIS = 30_000L
        private const val NOTIFICATION_CHANNEL_ID = "notes-file-upload"
        private const val NOTIFICATION_ID = 1001
        private const val PROGRESS_MAX = 100
    }
}
