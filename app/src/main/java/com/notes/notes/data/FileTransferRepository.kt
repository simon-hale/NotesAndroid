package com.notes.notes.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.alibaba.sdk.android.oss.OSS
import com.alibaba.sdk.android.oss.OSSClient
import com.alibaba.sdk.android.oss.callback.OSSProgressCallback
import com.alibaba.sdk.android.oss.common.auth.OSSStsTokenCredentialProvider
import com.alibaba.sdk.android.oss.model.PutObjectRequest
import com.notes.notes.core.DownloadedFileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FileTransferRepository(
    private val context: Context,
    private val backendService: NotesBackendService = NotesBackendService(),
) {

    private val downloadRelativePath = "${Environment.DIRECTORY_DOWNLOADS}/Notes"

    suspend fun copyUriToTemporaryFile(uri: Uri, displayName: String): File = withContext(Dispatchers.IO) {
        val fileName = displayName.ifBlank { "upload-${System.currentTimeMillis()}" }
        val tempDir = File(context.cacheDir, "upload-cache").apply { mkdirs() }
        val tempFile = File(tempDir, "${System.currentTimeMillis()}-$fileName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("Unable to read file uri: $uri")
        tempFile
    }

    suspend fun uploadToOss(
        sts: OssStsToken,
        sourceFile: File,
        onProgress: (currentBytes: Long, totalBytes: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
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
        val oss: OSS = OSSClient(context, endpoint, credentialProvider)
        val request = PutObjectRequest(sts.bucket, sts.objectKey, sourceFile.absolutePath).apply {
            progressCallback = OSSProgressCallback<PutObjectRequest> { _, currentSize, totalSize ->
                onProgress(currentSize, totalSize)
            }
        }
        oss.putObject(request)
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
}
