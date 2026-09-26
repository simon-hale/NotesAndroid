package com.notes.notes.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import com.notes.notes.core.DownloadTransfer
import com.notes.notes.core.TransferNotice
import com.notes.notes.core.stringsFor
import kotlinx.coroutines.CancellationException
import com.notes.notes.core.DownloadPhase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.math.roundToInt

/** Raised when the download cannot be promoted to a foreground service before the transfer starts. */
class DownloadForegroundException(cause: Throwable?) :
    Exception("Unable to run the download as a foreground service", cause)

/**
 * True when an attempt that ended with [throwable] has to be recorded as a persistent failure.
 *
 * A cancellation is a user pause, a WorkManager stop or a system shutdown — never a download failure —
 * so it keeps the transfer resumable instead of marking it failed.
 */
internal fun isPersistableDownloadFailure(throwable: Throwable): Boolean =
    throwable !is CancellationException

/**
 * Runs one logical download transfer as a long-running foreground worker.
 *
 * The partial file is a pending MediaStore item, so an incomplete download is never visible as a
 * finished one. Progress, ETag and destination are persisted in [TransferStore] which makes pause,
 * process death and resume work; the presigned URL is never persisted and every attempt asks the
 * backend for a new one.
 */
class DownloadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    private val backendService = NotesBackendService()
    private val transferRepository = FileTransferRepository(applicationContext)
    private val transferStore = TransferStore(applicationContext)

    override suspend fun doWork(): Result {
        val transferId = inputData.getString(KEY_INPUT_TRANSFER_ID).orEmpty()
        if (transferId.isBlank()) {
            Log.w(TAG, "Download work enqueued without a transfer id")
            return Result.success()
        }

        val transfer = transferStore.download(transferId)
        if (transfer == null) {
            Log.w(TAG, "Download work for an unknown transfer")
            return Result.success()
        }

        val account = TransferAccount.current(applicationContext)
        if (!account.owns(transfer.accountKey)) {
            Log.w(TAG, "Download work is owned by another account; leaving it untouched")
            return Result.success(resultData(transferId, OUTCOME_CANCELED))
        }

        // Same protection as upload: a pause is persisted before WorkManager cancellation, and a failed
        // attempt is terminal until the user retries it. If this work starts in either race window, do
        // not perform any network or file I/O.
        if (!transfer.phase.isTransferring) {
            return Result.success(resultData(transferId, OUTCOME_CANCELED))
        }

        val accessToken = account.accessTokenFor(
            fallback = inputData.getString(KEY_INPUT_ACCESS_TOKEN).orEmpty(),
            transferAccountKey = transfer.accountKey,
            workAccountKey = inputData.getString(KEY_INPUT_ACCOUNT_KEY).orEmpty(),
        )
        if (accessToken.isBlank()) {
            return finalizeFailure(
                transferId = transferId,
                throwable = IllegalStateException("Missing download session data"),
                run = null,
            )
        }

        val run = DownloadRun(transfer)

        return try {
            val foregroundFailure = promoteToForeground(transfer, run)
            if (foregroundFailure != null) {
                return finalizeFailure(
                    transferId = transferId,
                    throwable = DownloadForegroundException(foregroundFailure),
                    run = run,
                )
            }

            transferFile(transfer, accessToken, run)
        } catch (cancellation: CancellationException) {
            // A pause or a shutdown is not a failure: the attempt keeps its bytes and stays resumable.
            persistRun(run)
            throw cancellation
        } catch (throwable: Throwable) {
            /*
             * Only a real error is a failure. The cancellation clause above already keeps pauses out of
             * here, and this guard keeps that rule explicit even if the clauses are ever reordered.
             */
            if (!isPersistableDownloadFailure(throwable)) throw throwable
            finalizeFailure(transferId, throwable, run)
        }
    }

    /**
     * Records one failed attempt and reports it.
     *
     * The failure is persisted by the worker itself, next to the newest byte snapshot, because it is a
     * fact the download engine produced: whether a UI happened to be alive and watching must not decide
     * whether the attempt is remembered as failed or mistaken for a user pause later.
     *
     * The order matters and is what makes the record consistent: the latest progress is written first,
     * and the phase is then changed on top of the stored record without touching any of it.
     */
    private suspend fun finalizeFailure(
        transferId: String,
        throwable: Throwable,
        run: DownloadRun?,
    ): Result {
        if (run != null) {
            Log.w(TAG, "Download failed for ${run.fileName}", throwable)
            persistRun(run)
        } else {
            Log.w(TAG, "Download failed before the attempt started", throwable)
        }

        /*
         * The result is reported even when the record could not be updated: whoever reads the result
         * applies the same rule, so a store hiccup cannot silently turn the failure into a pause.
         *
         * The phase is written on top of the newest stored record and only while that record still claims
         * to be running: a pause the user asked for, or a destructive cancel that is already unwinding,
         * owns the state by then and must not be replaced by this attempt's ending.
         */
        runCatching {
            withContext(NonCancellable) {
                transferStore.updateDownload(transferId) { current -> current.failedAttemptIfRunning() }
            }
        }.onFailure { storeFailure ->
            Log.w(TAG, "Unable to record the terminal download failure", storeFailure)
        }

        return Result.success(failureData(transferId, throwable))
    }

    private suspend fun transferFile(
        transfer: DownloadTransfer,
        accessToken: String,
        run: DownloadRun,
    ): Result {
        prepareDestination(transfer, run)

        var attempt = 0
        while (true) {
            attempt++
            val descriptor = backendService.getFilePreview(
                accessToken = accessToken,
                fileId = transfer.fileId,
                language = AppLanguage.fromCode(transfer.language),
            )
            val response = try {
                backendService.openDownloadStream(
                    url = descriptor.url,
                    offset = run.downloadedBytes,
                    ifMatchETag = run.etag.takeIf { it.isNotBlank() },
                )
            } catch (http: NotesServiceException.Http) {
                // An expired presigned URL is retried once with a brand new one.
                if (http.statusCode == 403 && attempt <= MAX_URL_ATTEMPTS) continue
                throw http
            }

            var retry = false
            try {
                when (DownloadResumePolicy.planForResponse(response.statusCode, run.downloadedBytes)) {
                    DownloadPlan.CONTINUE_FROM_OFFSET -> {
                        if (response.statusCode == HTTP_PARTIAL_CONTENT) {
                            run.takeResumedTotal(response.statusCode, response.contentLength)
                        } else {
                            run.takeFreshTotal(response.contentLength)
                        }
                        run.takeETag(response.etag)
                    }

                    DownloadPlan.RESTART_FROM_ZERO -> {
                        if (response.statusCode == HTTP_OK && run.downloadedBytes > 0L) {
                            // The server ignored the Range header: never append, an old prefix must
                            // not be combined with a new object's suffix. The new representation also
                            // gets its own length and ETag.
                            Log.i(TAG, "Server ignored Range for ${transfer.fileName}; restarting")
                            run.resetForRestart(TransferNotice.RESTARTED_RANGE_IGNORED)
                            run.takeFreshTotal(response.contentLength)
                            run.takeETag(response.etag)
                        } else {
                            // The remote object changed or the range is unusable: discard the partial
                            // file and fetch the current object from byte zero.
                            if (attempt > MAX_RESTART_ATTEMPTS) {
                                throw NotesServiceException.Http(
                                    response.statusCode,
                                    "Download restart limit reached",
                                )
                            }
                            Log.i(TAG, "Restarting download of ${transfer.fileName} after ${response.statusCode}")
                            run.resetForRestart(TransferNotice.RESTARTED_REMOTE_CHANGED)
                            persistRun(run)
                            retry = true
                        }
                    }
                }

                if (retry) continue

                if (run.downloadedBytes == 0L) {
                    run.requestRestartFromZero()
                }
                val completed = streamIntoDestination(transfer, run, response)
                if (!completed) {
                    // The worker was stopped (pause) or the connection ended early: the MediaStore item
                    // stays pending and the transfer keeps the bytes it really has.
                    if (isStopped) throw CancellationException("Download paused")
                    throw IOException("Download of ${transfer.fileName} ended before it was complete")
                }
                transferRepository.markDownloadComplete(run.destinationUri)
                transferStore.removeDownload(transfer.transferId)
                return Result.success(resultData(transfer.transferId, OUTCOME_SUCCESS))
            } finally {
                response.close()
            }
        }
    }

    /**
     * Streams the response into the MediaStore destination with an explicit copy loop: progress and a
     * responsive pause both need byte counts, which `InputStream.copyTo` cannot provide.
     */
    private suspend fun streamIntoDestination(
        transfer: DownloadTransfer,
        run: DownloadRun,
        response: DownloadStreamResponse,
    ): Boolean {
        val sink = transferRepository.openDownloadSink(
            destinationUri = run.destinationUri,
            offset = run.downloadedBytes,
            restart = run.consumeRestartFromZero(),
        )
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var lastPersistedAt = 0L
        var lastWorkProgressAt = 0L
        var completed = false
        try {
            while (true) {
                if (isStopped) break
                val read = response.inputStream.read(buffer)
                if (read < 0) {
                    // A clean EOF only counts as completion when the expected byte count arrived. The
                    // expectation is always the length of the object currently being fetched, never
                    // the length of a representation that was already discarded.
                    completed = run.isCompleteAfterEndOfStream()
                    break
                }
                if (read == 0) continue
                sink.write(buffer, 0, read)
                run.recordBytesRead(read.toLong())

                val now = System.currentTimeMillis()
                if (now - lastWorkProgressAt >= WORK_PROGRESS_INTERVAL_MILLIS) {
                    lastWorkProgressAt = now
                    setProgress(run.toWorkData())
                    updateNotification(transfer, run)
                }
                if (now - lastPersistedAt >= PERSIST_INTERVAL_MILLIS) {
                    lastPersistedAt = now
                    persistRun(run)
                }
            }
            sink.flush()
        } finally {
            runCatching { sink.close() }
        }
        persistRun(run)
        return completed
    }

    /**
     * Makes sure the pending MediaStore destination is still there and still holds the bytes the
     * transfer recorded. Pending items are not permanent, so a missing or shortened destination
     * restarts cleanly instead of failing or appending at a wrong offset.
     */
    private suspend fun prepareDestination(
        transfer: DownloadTransfer,
        run: DownloadRun,
    ) {
        val destinationSize = transferRepository.downloadDestinationSize(run.destinationUri)

        val plan = DownloadResumePolicy.planForDestination(
            destinationExists = destinationSize != null,
            destinationSize = destinationSize ?: 0L,
            recordedBytes = run.downloadedBytes,
        )

        if (plan == DownloadPlan.CONTINUE_FROM_OFFSET) {
            return
        }

        val hadPartialFile =
            run.downloadedBytes > 0L ||
                    destinationSize != null

        if (hadPartialFile) {
            Log.i(TAG, "Partial download of ${run.fileName} is unusable; starting over")
        }

        // If the old MediaStore row still exists but is inconsistent with our persisted byte count,
        // remove it before creating the replacement. A missing row simply needs no deletion.
        if (destinationSize != null) {
            transferRepository.deleteDownloadDestination(run.destinationUri)
        }

        val reservedNames = transferStore.downloadsOnce()
            .asSequence()
            .filterNot { it.transferId == transfer.transferId }
            .map { it.fileName }
            .toSet()

        val destination = transferRepository.createPendingDownloadDestination(
            fileName = run.fileName,
            reservedNames = reservedNames,
        )

        run.destinationUri = destination.uri.toString()
        run.fileName = destination.displayName

        run.resetForRestart(
            if (hadPartialFile) {
                TransferNotice.RESTARTED_PARTIAL_MISSING
            } else {
                TransferNotice.NONE
            }
        )

        persistRun(run)
    }

    /**
     * Writes the live transfer state back, but only while the record still exists.
     *
     * The phase is not part of what an attempt publishes, so a progress write can never overwrite a
     * pause the user asked for, nor the terminal failure recorded next to it.
     */
    private suspend fun persistRun(run: DownloadRun) {
        withContext(NonCancellable) {
            transferStore.updateDownload(run.transferId) { current ->
                current.withAttemptProgress(
                    fileName = run.fileName,
                    destinationUri = run.destinationUri,
                    downloadedBytes = run.downloadedBytes,
                    totalBytes = run.totalBytes,
                    etag = run.etag,
                    notice = run.consumeNotice(current.notice),
                )
            }
            Unit
        }
    }

    private suspend fun promoteToForeground(transfer: DownloadTransfer, run: DownloadRun): Throwable? = try {
        withTimeout(FOREGROUND_PROMOTION_TIMEOUT_MILLIS) {
            setForeground(createForegroundInfo(transfer, run))
        }
        null
    } catch (timeout: TimeoutCancellationException) {
        timeout
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        throwable
    }

    private suspend fun updateNotification(transfer: DownloadTransfer, run: DownloadRun) {
        try {
            setForeground(createForegroundInfo(transfer, run))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Log.w(TAG, "Unable to refresh the download notification", throwable)
        }
    }

    private fun createForegroundInfo(
        transfer: DownloadTransfer,
        run: DownloadRun,
    ): ForegroundInfo {
        val context = applicationContext
        val strings = stringsFor(AppLanguage.fromCode(transfer.language))
        val title = strings.common.download

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (
            notificationManager != null &&
            notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null
        ) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    title,
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }

        val fraction = run.fraction
        val percent = (fraction * PROGRESS_MAX).roundToInt()

        val text = if (run.totalBytes > 0L) {
            "${run.fileName} · $percent% · ${strings.transfers.downloading}"
        } else {
            "${run.fileName} · ${strings.transfers.downloading}"
        }

        val notification: Notification =
            NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download_notification)
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
                .setProgress(
                    PROGRESS_MAX,
                    if (run.totalBytes > 0L) percent else 0,
                    run.totalBytes <= 0L,
                )
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

    /**
     * Mutable state of one download attempt; only non-secret fields reach the store.
     *
     * The byte/ETag/total accounting lives in [DownloadProgressState] so the restart rules stay pure
     * and testable: a restart always discards the length of the previous representation.
     */
    private class DownloadRun(transfer: DownloadTransfer) {
        val transferId: String = transfer.transferId

        var fileName: String = transfer.fileName
        var destinationUri: String = transfer.destinationUri

        private val state = DownloadProgressState(
            downloadedBytes = transfer.downloadedBytes,
            totalBytes = transfer.totalBytes,
            etag = transfer.etag,
        )

        private var pendingNotice: TransferNotice = TransferNotice.NONE

        val downloadedBytes: Long
            get() = state.downloadedBytes

        val totalBytes: Long
            get() = state.totalBytes

        val etag: String
            get() = state.etag

        val fraction: Float
            get() = state.fraction

        fun recordBytesRead(count: Long) =
            state.recordBytesRead(count)

        fun takeResumedTotal(
            statusCode: Int,
            contentLength: Long,
        ) = state.takeResumedTotal(
            statusCode,
            contentLength,
            requestedOffset = state.downloadedBytes,
        )

        fun takeFreshTotal(contentLength: Long) =
            state.takeFreshTotal(contentLength)

        fun takeETag(value: String) =
            state.takeETag(value)

        fun isCompleteAfterEndOfStream(): Boolean =
            state.isCompleteAfterEndOfStream()

        fun consumeRestartFromZero(): Boolean =
            state.consumeRestartFromZero()

        fun requestRestartFromZero() =
            state.requestRestartFromZero()

        fun resetForRestart(notice: TransferNotice) {
            state.requestRestartFromZero()

            if (notice != TransferNotice.NONE) {
                pendingNotice = notice
            }
        }

        fun consumeNotice(current: TransferNotice): TransferNotice {
            val notice = pendingNotice
            pendingNotice = TransferNotice.NONE
            return notice.takeIf { it != TransferNotice.NONE } ?: current
        }

        fun toWorkData(): Data =
            workDataOf(
                KEY_PROGRESS_DOWNLOADED_BYTES to downloadedBytes,
                KEY_PROGRESS_TOTAL_BYTES to totalBytes,
                KEY_PROGRESS_FRACTION to fraction,
            )
    }

    private fun resultData(transferId: String, outcome: String): Data = workDataOf(
        KEY_RESULT_TRANSFER_ID to transferId,
        KEY_RESULT_OUTCOME to outcome,
    )

    private fun failureData(transferId: String, throwable: Throwable): Data = workDataOf(
        KEY_RESULT_TRANSFER_ID to transferId,
        KEY_RESULT_OUTCOME to OUTCOME_FAILED,
        KEY_RESULT_ERROR_KIND to when (throwable) {
            is DownloadForegroundException -> ERROR_KIND_FOREGROUND
            is NotesServiceException.Business -> ERROR_KIND_BUSINESS
            is NotesServiceException.Http -> ERROR_KIND_HTTP
            is NotesServiceException.MissingBaseUrl -> ERROR_KIND_MISSING_BASE_URL
            else -> ERROR_KIND_OTHER
        },
        KEY_RESULT_ERROR_STATUS to ((throwable as? NotesServiceException.Http)?.statusCode ?: 0),
        KEY_RESULT_ERROR_DETAIL to throwable.message.orEmpty().take(MAX_ERROR_DETAIL_LENGTH),
    )

    companion object {
        const val KEY_INPUT_TRANSFER_ID = "input_transfer_id"
        const val KEY_INPUT_ACCESS_TOKEN = "input_access_token"

        /**
         * Non-secret owner of the transfer this work was enqueued for. It binds the fallback session
         * token to the account that created the work, so credentials of another signed-in account can
         * never be used for it. No credentials are stored here.
         */
        const val KEY_INPUT_ACCOUNT_KEY = "input_account_key"

        const val KEY_PROGRESS_DOWNLOADED_BYTES = "progress_downloaded_bytes"
        const val KEY_PROGRESS_TOTAL_BYTES = "progress_total_bytes"
        const val KEY_PROGRESS_FRACTION = "progress_fraction"

        const val KEY_RESULT_TRANSFER_ID = "result_transfer_id"
        const val KEY_RESULT_OUTCOME = "result_outcome"
        const val KEY_RESULT_ERROR_KIND = "result_error_kind"
        const val KEY_RESULT_ERROR_STATUS = "result_error_status"
        const val KEY_RESULT_ERROR_DETAIL = "result_error_detail"

        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_CANCELED = "canceled"

        const val ERROR_KIND_FOREGROUND = "foreground"
        const val ERROR_KIND_BUSINESS = "business"
        const val ERROR_KIND_HTTP = "http"
        const val ERROR_KIND_MISSING_BASE_URL = "missingBaseUrl"
        const val ERROR_KIND_OTHER = "other"

        private const val TAG = "DownloadWorker"
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val MAX_URL_ATTEMPTS = 2
        private const val MAX_RESTART_ATTEMPTS = 3
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val PERSIST_INTERVAL_MILLIS = 1_000L
        private const val WORK_PROGRESS_INTERVAL_MILLIS = 500L
        private const val FOREGROUND_PROMOTION_TIMEOUT_MILLIS = 30_000L
        private const val NOTIFICATION_CHANNEL_ID = "notes-file-download"

        private fun notificationId(
            transferId: String,
        ): Int =
            DOWNLOAD_NOTIFICATION_NAMESPACE or
                    (
                            transferId.hashCode() and
                                    NOTIFICATION_ID_MASK
                            )
        private const val DOWNLOAD_NOTIFICATION_NAMESPACE = 0x20000000
        private const val NOTIFICATION_ID_MASK = 0x0FFFFFFF
        private const val PROGRESS_MAX = 100
        private const val MAX_ERROR_DETAIL_LENGTH = 200
    }
}
