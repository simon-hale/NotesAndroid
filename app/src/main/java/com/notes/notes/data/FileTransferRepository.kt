package com.notes.notes.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.alibaba.sdk.android.oss.ClientConfiguration
import com.alibaba.sdk.android.oss.OSS
import com.alibaba.sdk.android.oss.OSSClient
import com.alibaba.sdk.android.oss.callback.OSSProgressCallback
import com.alibaba.sdk.android.oss.common.auth.OSSStsTokenCredentialProvider
import com.alibaba.sdk.android.oss.model.AbortMultipartUploadRequest
import com.alibaba.sdk.android.oss.model.MultipartUploadRequest
import com.alibaba.sdk.android.oss.model.PutObjectRequest
import com.notes.notes.core.DownloadedFileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Raised when the picked document cannot be uploaded as-is, for example when its size is unknown. */
class UploadSourceException(message: String) : Exception(message)

class FileTransferRepository(
    private val context: Context,
    private val backendService: NotesBackendService = NotesBackendService(),
) {

    private val downloadRelativePath = "${Environment.DIRECTORY_DOWNLOADS}/Notes"

    /**
     * Uploads [sourceUri] to the STS-scoped object key with the OSS multipart API.
     *
     * The OSS SDK reads the picked document through the ContentResolver, so the file never has to be
     * copied into the app cache first. Non-empty files are split into [PART_SIZE_BYTES] parts that are
     * uploaded [PARALLEL_PART_COUNT] at a time and retried by the SDK itself.
     */
    suspend fun uploadToOss(
        sts: OssStsToken,
        sourceUri: Uri,
        onProgress: (currentBytes: Long, totalBytes: Long) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val oss = createOssClient(sts)
        if (resolveUploadSize(sourceUri) == 0L) {
            // Multipart upload rejects empty sources; a single PUT is enough for them.
            oss.putObject(PutObjectRequest(sts.bucket, sts.objectKey, sourceUri))
            onProgress(0L, 0L)
            return@withContext
        }

        val request = MultipartUploadRequest<MultipartUploadRequest<*>>(
            sts.bucket,
            sts.objectKey,
            sourceUri,
        ).apply {
            partSize = PART_SIZE_BYTES
            threadNum = PARALLEL_PART_COUNT
            progressCallback = OSSProgressCallback { _, currentBytes, totalBytes ->
                onProgress(currentBytes, totalBytes)
            }
        }

        val task = oss.asyncMultipartUpload(request, null)
        try {
            // Poll instead of waiting forever so that cancellation (worker stop, logout) stays responsive.
            while (!task.isCompleted) {
                delay(UPLOAD_POLL_INTERVAL_MILLIS)
            }
            task.getResult()
        } catch (throwable: Throwable) {
            task.cancel()
            abortMultipartUploadQuietly(oss, sts, request.uploadId)
            throw throwable
        }
    }

    suspend fun downloadToDownloads(url: String, fileName: String) = withContext(Dispatchers.IO) {
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

        val resolver = context.contentResolver
        val destinationUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create download destination")

        try {
            resolver.openOutputStream(destinationUri)?.use { output ->
                backendService.downloadToOutput(url, output)
            } ?: error("Unable to open download destination")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(destinationUri, values, null, null)
        } catch (throwable: Throwable) {
            resolver.delete(destinationUri, null, null)
            throw throwable
        }
    }

    suspend fun listDownloadedFiles(): List<DownloadedFileEntry> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.SIZE,
        )
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

    private fun createOssClient(sts: OssStsToken): OSS {
        val credentialProvider = OSSStsTokenCredentialProvider(
            sts.accessKeyId,
            sts.accessKeySecret,
            sts.securityToken,
        )
        val endpoint = if (sts.region.startsWith("http")) {
            sts.region
        } else {
            "https://${sts.region}.aliyuncs.com"
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

    /**
     * Returns the size the OSS SDK will use for [sourceUri], or 0 for an empty document.
     *
     * The SDK derives the content length from the same file descriptor, so an unreadable size cannot
     * be uploaded reliably and is rejected instead of being sent as an empty object.
     */
    private fun resolveUploadSize(sourceUri: Uri): Long {
        val statSize = context.contentResolver.openFileDescriptor(sourceUri, "r")?.use { it.statSize } ?: -1L
        if (statSize > 0L) return statSize

        val hasContent = context.contentResolver.openInputStream(sourceUri)?.use { it.read() >= 0 } ?: false
        if (hasContent) {
            throw UploadSourceException("Unable to read the size of $sourceUri")
        }
        return 0L
    }

    private fun abortMultipartUploadQuietly(oss: OSS, sts: OssStsToken, uploadId: String?) {
        if (uploadId.isNullOrBlank()) return
        runCatching {
            oss.abortMultipartUpload(AbortMultipartUploadRequest(sts.bucket, sts.objectKey, uploadId))
        }
    }

    private companion object {
        /** 5 MiB parts, the same part size the web client uses. */
        const val PART_SIZE_BYTES = 5L * 1024L * 1024L

        /** Parallel part uploads, matching the web client's `parallel: 3`. */
        const val PARALLEL_PART_COUNT = 3

        /** Longest single OSS request, matching the web client's 180 s timeout. */
        const val REQUEST_TIMEOUT_MILLIS = 180_000

        /** Request-level retries performed by the OSS SDK, matching the web client's retry budget. */
        const val MAX_ERROR_RETRY = 2

        const val UPLOAD_POLL_INTERVAL_MILLIS = 250L
    }
}
