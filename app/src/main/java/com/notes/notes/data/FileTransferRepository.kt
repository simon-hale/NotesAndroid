package com.notes.notes.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.alibaba.sdk.android.oss.ClientConfiguration
import com.alibaba.sdk.android.oss.OSS
import com.alibaba.sdk.android.oss.OSSClient
import com.alibaba.sdk.android.oss.callback.OSSProgressCallback
import com.alibaba.sdk.android.oss.common.auth.OSSCredentialProvider
import com.alibaba.sdk.android.oss.model.AbortMultipartUploadRequest
import com.alibaba.sdk.android.oss.model.MultipartUploadRequest
import com.alibaba.sdk.android.oss.model.PutObjectRequest
import com.alibaba.sdk.android.oss.model.ResumableUploadRequest
import com.notes.notes.core.DownloadedFileEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel

/** Raised when the picked document cannot be uploaded as-is, for example when its size is unknown. */
class UploadSourceException(message: String) : Exception(message)

/** Raised when the picked document can no longer be opened, for example after a revoked permission. */
class UploadSourceUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Size and last-modified values of a picked document, used to detect a changed local source. */
data class UploadSourceStats(
    val size: Long,
    val lastModified: Long,
)

/** A downloadable destination that no longer exists, for example an expired pending MediaStore item. */
class DownloadDestinationMissingException(message: String) : Exception(message)

/**
 * Seekable write handle on a MediaStore download destination.
 *
 * `openOutputStream(..., append)` is deliberately avoided: resumed downloads must be positioned at an
 * exact byte offset (or truncated back to zero) on the very file descriptor MediaStore handed out.
 */
class DownloadSink internal constructor(
    private val descriptor: ParcelFileDescriptor,
    private val stream: FileOutputStream,
    private val channel: FileChannel,
) : Closeable {

    fun write(buffer: ByteArray, offset: Int, length: Int) {
        stream.write(buffer, offset, length)
    }

    fun truncate() {
        channel.truncate(0L)
        channel.position(0L)
    }

    fun flush() {
        stream.flush()
    }

    override fun close() {
        runCatching { stream.flush() }
        runCatching { stream.close() }
        runCatching { descriptor.close() }
    }
}

/**
 * Uploads and downloads owned by the app.
 *
 * Uploads use the OSS SDK's resumable multipart implementation, which owns its own checkpoint file
 * underneath the per-transfer directory this class creates. Downloads write straight into the
 * `Downloads/Notes` MediaStore collection and resume with ranged GETs.
 */
class FileTransferRepository(
    private val context: Context,
    private val backendService: NotesBackendService = NotesBackendService(),
) {

    private val downloadRelativePath = "${Environment.DIRECTORY_DOWNLOADS}/Notes"

    /** Every logical transfer gets its own checkpoint directory, never shared with an unrelated one. */
    fun checkpointDirectoryPath(transferId: String): String {
        val root = File(context.filesDir, OSS_RESUME_ROOT)
        val directory = File(root, transferId)
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return directory.absolutePath
    }

    fun deleteCheckpointDirectory(path: String) {
        if (path.isBlank()) return
        val rootPath = File(context.filesDir, OSS_RESUME_ROOT).absolutePath
        val normalized = File(path).absolutePath
        if (normalized == rootPath || !normalized.startsWith("$rootPath${File.separator}")) return
        runCatching { File(normalized).deleteRecursively() }
    }

    /** Checkpoint directories left behind by transfers that no longer exist. */
    fun orphanCheckpointDirectories(knownTransferIds: Set<String>): List<File> {
        val root = File(context.filesDir, OSS_RESUME_ROOT)
        val children = root.listFiles() ?: return emptyList()
        return children.filter { it.isDirectory && it.name !in knownTransferIds }
    }

    /**
     * Uploads [sourceUri] with the OSS resumable API.
     *
     * Zero-byte documents keep using `PutObject`, because multipart upload does not apply to an empty
     * source. Non-empty documents are resumed from [checkpointDirectory] whenever a checkpoint from an
     * earlier attempt still exists there.
     *
     * [onUploadId] is invoked as soon as the SDK has assigned the multipart upload id, so an explicit
     * cancel can abort the upload without parsing the SDK's private checkpoint file. It is also
     * invoked once more when this call is cancelled, because a pause must keep the upload id.
     */
    suspend fun uploadToOss(
        ticket: OssStsToken,
        objectKey: String,
        sourceUri: Uri,
        sizeBytes: Long,
        checkpointDirectory: String,
        credentialProvider: OSSCredentialProvider,
        onProgress: (currentBytes: Long, totalBytes: Long) -> Unit,
        onUploadId: suspend (String) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val oss = createOssClient(ticket, credentialProvider)
        var lastReportedUploadId = ""

        suspend fun reportUploadId(uploadId: String?) {
            val id = uploadId.orEmpty()
            if (id.isBlank() || id == lastReportedUploadId) return
            lastReportedUploadId = id
            withContext(NonCancellable) { onUploadId(id) }
        }

        if (sizeBytes == 0L) {
            val task = oss.asyncPutObject(PutObjectRequest(ticket.bucket, objectKey, sourceUri), null)
            try {
                while (!task.isCompleted) {
                    delay(UPLOAD_POLL_INTERVAL_MILLIS)
                }
                task.getResult()
                onProgress(0L, 0L)
            } catch (cancellation: CancellationException) {
                task.cancel()
                throw cancellation
            } catch (throwable: Throwable) {
                task.cancel()
                throw throwable
            }
            return@withContext
        }

        val request = ResumableUploadRequest(ticket.bucket, objectKey, sourceUri, checkpointDirectory).apply {
            partSize = PART_SIZE_BYTES
            threadNum = PARALLEL_PART_COUNT
            // A pause (coroutine cancellation) must keep the SDK checkpoint and must not abort the
            // multipart upload: aborting is reserved for an explicit destructive cancel.
            setDeleteUploadOnCancelling(false)
            // ResumableUploadRequest extends the raw MultipartUploadRequest, so the callback's type
            // argument has to be spelled out for Kotlin.
            progressCallback = OSSProgressCallback<MultipartUploadRequest<*>> { _, currentBytes, totalBytes ->
                onProgress(currentBytes, totalBytes)
            }
        }

        val task = oss.asyncResumableUpload(request, null)
        try {
            // Poll instead of waiting forever so that cancellation (pause, cancel, logout) stays
            // responsive and the multipart upload id can be captured as soon as the SDK assigns it.
            while (!task.isCompleted) {
                reportUploadId(request.uploadId)
                delay(UPLOAD_POLL_INTERVAL_MILLIS)
            }
            reportUploadId(request.uploadId)
            task.getResult()
        } catch (cancellation: CancellationException) {
            task.cancel()
            reportUploadId(request.uploadId)
            throw cancellation
        } catch (throwable: Throwable) {
            task.cancel()
            reportUploadId(request.uploadId)
            throw throwable
        }
    }

    /**
     * Best-effort `AbortMultipartUpload` for a transfer the user explicitly cancelled.
     *
     * The bucket lifecycle policy removes abandoned parts eventually, so a failure here is not fatal.
     */
    suspend fun abortMultipartUpload(
        ticket: OssStsToken,
        objectKey: String,
        uploadId: String,
        credentialProvider: OSSCredentialProvider,
    ): Boolean = withContext(Dispatchers.IO) {
        if (uploadId.isBlank() || objectKey.isBlank()) return@withContext false
        runCatching {
            createOssClient(ticket, credentialProvider)
                .abortMultipartUpload(AbortMultipartUploadRequest(ticket.bucket, objectKey, uploadId))
        }.isSuccess
    }

    /**
     * Reads the size and last-modified metadata of [sourceUri] without copying it anywhere.
     *
     * Throws [UploadSourceUnavailableException] when the document can no longer be opened, and
     * [UploadSourceException] when its size cannot be determined reliably.
     */
    fun readSourceStats(sourceUri: Uri): UploadSourceStats {
        val descriptor = try {
            context.contentResolver.openFileDescriptor(sourceUri, "r")
        } catch (throwable: Throwable) {
            throw UploadSourceUnavailableException("Unable to open $sourceUri", throwable)
        } ?: throw UploadSourceUnavailableException("Unable to open $sourceUri")

        val statSize = descriptor.use { it.statSize }
        val lastModified = resolveSourceLastModified(sourceUri)
        if (statSize > 0L) return UploadSourceStats(statSize, lastModified)

        val hasContent = runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { it.read() >= 0 } ?: false
        }.getOrDefault(false)
        if (hasContent) {
            throw UploadSourceException("Unable to read the size of $sourceUri")
        }
        return UploadSourceStats(0L, lastModified)
    }

    /**
     * Provider last-modified value in milliseconds, or 0 when the document does not report one.
     * A changed value means the paused source is not the file the checkpoint was built from.
     */
    fun resolveSourceLastModified(sourceUri: Uri): Long {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        return runCatching {
            context.contentResolver.query(sourceUri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use 0L
                val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (index < 0 || cursor.isNull(index)) 0L else cursor.getLong(index)
            } ?: 0L
        }.getOrDefault(0L)
    }

    // ---------------------------------------------------------------------------------------------
    // Downloads
    // ---------------------------------------------------------------------------------------------

    /** Creates the pending MediaStore entry a download writes into, hidden until it completes. */
    fun createPendingDownloadDestination(fileName: String): Uri {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, downloadRelativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create download destination")
    }

    /**
     * Size of the partial download destination, or `null` when the MediaStore item is gone.
     *
     * Pending items are not guaranteed to survive forever, so a missing destination means the
     * download has to restart with a fresh MediaStore entry rather than failing.
     */
    fun downloadDestinationSize(destinationUri: String): Long? {
        val uri = runCatching { Uri.parse(destinationUri) }.getOrNull() ?: return null
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { it.statSize }
        }.getOrNull()
    }

    /**
     * Opens the destination for a resumed write, positioned exactly at [offset] (or truncated to the
     * start when [restart] is set).
     */
    fun openDownloadSink(destinationUri: String, offset: Long, restart: Boolean): DownloadSink {
        val uri = runCatching { Uri.parse(destinationUri) }.getOrNull()
            ?: throw DownloadDestinationMissingException("Invalid download destination")
        val descriptor = runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")
        }.getOrNull() ?: throw DownloadDestinationMissingException("Download destination is missing")

        val stream = FileOutputStream(descriptor.fileDescriptor)
        return try {
            val channel = stream.channel
            if (restart) {
                channel.truncate(0L)
                channel.position(0L)
            } else {
                channel.position(offset)
            }
            DownloadSink(descriptor, stream, channel)
        } catch (throwable: Throwable) {
            runCatching { stream.close() }
            runCatching { descriptor.close() }
            throw throwable
        }
    }

    /** Publishes a finished download by clearing `IS_PENDING`. */
    fun markDownloadComplete(destinationUri: String) {
        val uri = runCatching { Uri.parse(destinationUri) }.getOrNull() ?: return
        val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }

    fun deleteDownloadDestination(destinationUri: String) {
        val uri = runCatching { Uri.parse(destinationUri) }.getOrNull() ?: return
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    suspend fun listDownloadedFiles(): List<DownloadedFileEntry> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.SIZE,
        )
        // Pending items stay invisible here: an incomplete transfer must never look like a download.
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.IS_PENDING} = 0"
        val selectionArgs = arrayOf("$downloadRelativePath%")
        val sortOrder = "${MediaStore.Downloads.DATE_MODIFIED} DESC, ${MediaStore.Downloads.DISPLAY_NAME} ASC"
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val mimeTypeIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val name = cursor.getString(nameIndex).orEmpty()
                    if (name.isBlank()) continue
                    val mimeType = cursor.getString(mimeTypeIndex).orEmpty()
                    val sizeBytes = cursor.getLong(sizeIndex)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        id,
                    )
                    add(
                        DownloadedFileEntry(
                            id = id,
                            name = name,
                            mimeType = mimeType,
                            sizeBytes = sizeBytes,
                            contentUri = contentUri.toString(),
                        )
                    )
                }
            }
        }.orEmpty()
    }

    suspend fun deleteDownloadedFile(file: DownloadedFileEntry) = withContext(Dispatchers.IO) {
        val deleted = context.contentResolver.delete(Uri.parse(file.contentUri), null, null)
        if (deleted <= 0) {
            error("Unable to delete downloaded file: ${file.name}")
        }
    }

    private fun createOssClient(ticket: OssStsToken, credentialProvider: OSSCredentialProvider): OSS {
        val endpoint = if (ticket.region.startsWith("http")) {
            ticket.region
        } else {
            "https://${ticket.region}.aliyuncs.com"
        }
        val configuration = ClientConfiguration().apply {
            connectionTimeout = REQUEST_TIMEOUT_MILLIS
            socketTimeout = REQUEST_TIMEOUT_MILLIS
            // The SDK retries transient failures (network errors and 5xx) per request.
            maxErrorRetry = MAX_ERROR_RETRY
            maxConcurrentRequest = PARALLEL_PART_COUNT
            maxConcurrentRequestsPerHost = PARALLEL_PART_COUNT
        }
        return OSSClient(context, endpoint, credentialProvider, configuration)
    }

    companion object {
        /** 5 MiB parts, the same part size the web client uses. */
        const val PART_SIZE_BYTES = 5L * 1024L * 1024L

        /** Parallel part uploads, matching the web client's `parallel: 3`. */
        const val PARALLEL_PART_COUNT = 3

        /** Longest single OSS request, matching the web client's 180 s timeout. */
        const val REQUEST_TIMEOUT_MILLIS = 180_000

        /** Request-level retries performed by the OSS SDK, matching the web client's retry budget. */
        const val MAX_ERROR_RETRY = 2

        const val UPLOAD_POLL_INTERVAL_MILLIS = 250L

        private const val OSS_RESUME_ROOT = "oss-resume"
    }
}
