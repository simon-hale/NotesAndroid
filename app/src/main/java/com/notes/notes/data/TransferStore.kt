package com.notes.notes.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.notes.notes.core.DownloadPhase
import com.notes.notes.core.DownloadTransfer
import com.notes.notes.core.TransferNotice
import com.notes.notes.core.UploadPhase
import com.notes.notes.core.UploadTransfer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Dedicated transfer-state store.
 *
 * Transfer state is deliberately kept out of the normal application preferences: it is large, it is
 * mutated by background workers, and it has a different lifetime (a transfer record outlives a
 * finished upload only until its metadata is committed).
 *
 * Only the small, non-secret fields of a transfer are persisted. JWTs, STS AccessKey/Secret/
 * SecurityToken and presigned download URLs never reach this store.
 */
private val Context.transfersDataStore by preferencesDataStore(name = "notes_transfers")

class TransferStore(private val context: Context) {

    private object Keys {
        val uploads = stringPreferencesKey("uploads_json")
        val downloads = stringPreferencesKey("downloads_json")
    }

    private val preferences: Flow<Preferences> = context.transfersDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }

    val uploads: Flow<List<UploadTransfer>> = preferences.map { decodeUploads(it[Keys.uploads]) }

    val downloads: Flow<List<DownloadTransfer>> = preferences.map { decodeDownloads(it[Keys.downloads]) }

    suspend fun uploadsOnce(): List<UploadTransfer> = uploads.first()

    suspend fun downloadsOnce(): List<DownloadTransfer> = downloads.first()

    suspend fun upload(transferId: String): UploadTransfer? =
        uploadsOnce().firstOrNull { it.transferId == transferId }

    suspend fun download(transferId: String): DownloadTransfer? =
        downloadsOnce().firstOrNull { it.transferId == transferId }

    suspend fun addUpload(transfer: UploadTransfer) {
        context.transfersDataStore.edit { preferences ->
            val current = decodeUploads(preferences[Keys.uploads])
            if (current.any { it.transferId == transfer.transferId }) return@edit
            preferences[Keys.uploads] = encodeUploads(current + transfer)
        }
    }

    suspend fun addUploads(transfers: List<UploadTransfer>) {
        if (transfers.isEmpty()) return
        context.transfersDataStore.edit { preferences ->
            val current = decodeUploads(preferences[Keys.uploads])
            val known = current.mapTo(mutableSetOf()) { it.transferId }
            val merged = current + transfers.filter { known.add(it.transferId) }
            preferences[Keys.uploads] = encodeUploads(merged)
        }
    }

    /**
     * Applies [transform] to an existing upload record.
     *
     * The update is a no-op when the record is gone, which is what keeps a cancelled transfer from
     * being resurrected by a worker that is still unwinding.
     */
    suspend fun updateUpload(
        transferId: String,
        transform: (UploadTransfer) -> UploadTransfer,
    ): UploadTransfer? {
        var result: UploadTransfer? = null
        context.transfersDataStore.edit { preferences ->
            val current = decodeUploads(preferences[Keys.uploads])
            val index = current.indexOfFirst { it.transferId == transferId }
            if (index < 0) return@edit
            val updated = transform(current[index])
            result = updated
            preferences[Keys.uploads] = encodeUploads(
                current.toMutableList().apply { this[index] = updated }
            )
        }
        return result
    }

    suspend fun removeUpload(transferId: String): UploadTransfer? = removeUploads(listOf(transferId)).firstOrNull()

    suspend fun removeUploads(transferIds: Collection<String>): List<UploadTransfer> {
        if (transferIds.isEmpty()) return emptyList()
        val ids = transferIds.toSet()
        var removed: List<UploadTransfer> = emptyList()
        context.transfersDataStore.edit { preferences ->
            val current = decodeUploads(preferences[Keys.uploads])
            removed = current.filter { it.transferId in ids }
            preferences[Keys.uploads] = encodeUploads(current.filterNot { it.transferId in ids })
        }
        return removed
    }

    /**
     * Atomically removes only uploads that are still safe to destructively cancel.
     *
     * The phase check happens inside the same DataStore edit that removes the records. This closes the
     * race where a UI snapshot still says TRANSFERRING while UploadWorker has already persisted
     * METADATA_PENDING after CompleteMultipartUpload succeeded.
     *
     * METADATA_PENDING must never be removed here because its complete OSS object can no longer be
     * cleaned by AbortMultipartUpload or the incomplete-multipart lifecycle rule.
     */
    suspend fun removeCancellableUploads(
        transferIds: Collection<String>,
    ): List<UploadTransfer> {
        if (transferIds.isEmpty()) {
            return emptyList()
        }

        val requestedIds = transferIds.toSet()
        var removed: List<UploadTransfer> = emptyList()

        context.transfersDataStore.edit { preferences ->
            val current =
                decodeUploads(preferences[Keys.uploads])

            removed =
                current.filter { transfer ->
                    transfer.transferId in requestedIds &&
                            !transfer.isMetadataPending
                }

            if (removed.isEmpty()) {
                return@edit
            }

            val removedIds =
                removed.mapTo(
                    mutableSetOf(),
                    UploadTransfer::transferId,
                )

            preferences[Keys.uploads] =
                encodeUploads(
                    current.filterNot { transfer ->
                        transfer.transferId in removedIds
                    }
                )
        }

        return removed
    }
    suspend fun removeUploadsForAccount(accountKey: String): List<UploadTransfer> {
        var removed: List<UploadTransfer> = emptyList()
        context.transfersDataStore.edit { preferences ->
            val current = decodeUploads(preferences[Keys.uploads])
            removed = current.filter { it.accountKey == accountKey }
            preferences[Keys.uploads] = encodeUploads(current.filterNot { it.accountKey == accountKey })
        }
        return removed
    }

    suspend fun addDownload(transfer: DownloadTransfer) {
        context.transfersDataStore.edit { preferences ->
            val current = decodeDownloads(preferences[Keys.downloads])
            if (current.any { it.transferId == transfer.transferId }) return@edit
            preferences[Keys.downloads] = encodeDownloads(current + transfer)
        }
    }

    suspend fun updateDownload(
        transferId: String,
        transform: (DownloadTransfer) -> DownloadTransfer,
    ): DownloadTransfer? {
        var result: DownloadTransfer? = null
        context.transfersDataStore.edit { preferences ->
            val current = decodeDownloads(preferences[Keys.downloads])
            val index = current.indexOfFirst { it.transferId == transferId }
            if (index < 0) return@edit
            val updated = transform(current[index])
            result = updated
            preferences[Keys.downloads] = encodeDownloads(
                current.toMutableList().apply { this[index] = updated }
            )
        }
        return result
    }

    suspend fun removeDownload(transferId: String): DownloadTransfer? =
        removeDownloads(listOf(transferId)).firstOrNull()

    suspend fun removeDownloads(transferIds: Collection<String>): List<DownloadTransfer> {
        if (transferIds.isEmpty()) return emptyList()
        val ids = transferIds.toSet()
        var removed: List<DownloadTransfer> = emptyList()
        context.transfersDataStore.edit { preferences ->
            val current = decodeDownloads(preferences[Keys.downloads])
            removed = current.filter { it.transferId in ids }
            preferences[Keys.downloads] = encodeDownloads(current.filterNot { it.transferId in ids })
        }
        return removed
    }

    suspend fun removeDownloadsForAccount(accountKey: String): List<DownloadTransfer> {
        var removed: List<DownloadTransfer> = emptyList()
        context.transfersDataStore.edit { preferences ->
            val current = decodeDownloads(preferences[Keys.downloads])
            removed = current.filter { it.accountKey == accountKey }
            preferences[Keys.downloads] = encodeDownloads(current.filterNot { it.accountKey == accountKey })
        }
        return removed
    }

    private fun encodeUploads(transfers: List<UploadTransfer>): String {
        val array = JSONArray()
        transfers.forEach { transfer -> array.put(transfer.toJson()) }
        return array.toString()
    }

    private fun encodeDownloads(transfers: List<DownloadTransfer>): String {
        val array = JSONArray()
        transfers.forEach { transfer -> array.put(transfer.toJson()) }
        return array.toString()
    }

    private fun decodeUploads(raw: String?): List<UploadTransfer> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    item.toUploadTransfer()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun decodeDownloads(raw: String?): List<DownloadTransfer> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    item.toDownloadTransfer()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun UploadTransfer.toJson(): JSONObject = JSONObject().apply {
        put(KEY_TRANSFER_ID, transferId)
        put(KEY_ACCOUNT_KEY, accountKey)
        put(KEY_SOURCE_URI, sourceUri)
        put(KEY_DISPLAY_NAME, displayName)
        put(KEY_EXPECTED_SIZE, expectedSize)
        put(KEY_LAST_MODIFIED, lastModified)
        put(KEY_PARENT_ID, parentId)
        put(KEY_PATH_STRING, pathString)
        put(KEY_LANGUAGE, language)
        put(KEY_BATCH_ID, batchId)
        put(KEY_BATCH_TOTAL_BYTES, batchTotalBytes)
        put(KEY_FILE_BYTES, fileBytes)
        put(KEY_CHECKPOINT_DIR, checkpointDir)
        put(KEY_PHASE, phase.storageValue)
        put(KEY_BUCKET, bucket)
        put(KEY_REGION, region)
        put(KEY_OBJECT_KEY, objectKey)
        put(KEY_UPLOAD_ID, uploadId)
        put(KEY_NOTICE, notice.storageValue)
        put(KEY_CREATED_AT, createdAt)
        put(KEY_TRANSFERRED_BYTES, transferredBytes)
    }

    private fun JSONObject.toUploadTransfer(): UploadTransfer? {
        val id = optString(KEY_TRANSFER_ID)
        if (id.isBlank()) return null
        return UploadTransfer(
            transferId = id,
            accountKey = optString(KEY_ACCOUNT_KEY),
            sourceUri = optString(KEY_SOURCE_URI),
            displayName = optString(KEY_DISPLAY_NAME),
            expectedSize = optLong(KEY_EXPECTED_SIZE, 0L),
            lastModified = optLong(KEY_LAST_MODIFIED, 0L),
            parentId = optLong(KEY_PARENT_ID, 0L),
            pathString = optString(KEY_PATH_STRING),
            language = optString(KEY_LANGUAGE),
            batchId = optString(KEY_BATCH_ID),
            batchTotalBytes = optLong(KEY_BATCH_TOTAL_BYTES, 0L),
            fileBytes = optLong(KEY_FILE_BYTES, 0L),
            checkpointDir = optString(KEY_CHECKPOINT_DIR),
            phase = UploadPhase.fromStorage(optString(KEY_PHASE)),
            bucket = optString(KEY_BUCKET),
            region = optString(KEY_REGION),
            objectKey = optString(KEY_OBJECT_KEY),
            uploadId = optString(KEY_UPLOAD_ID),
            notice = TransferNotice.fromStorage(optString(KEY_NOTICE)),
            createdAt = optLong(KEY_CREATED_AT, 0L),
            transferredBytes = optLong(KEY_TRANSFERRED_BYTES, 0L),
        )
    }

    private fun DownloadTransfer.toJson(): JSONObject = JSONObject().apply {
        put(KEY_TRANSFER_ID, transferId)
        put(KEY_ACCOUNT_KEY, accountKey)
        put(KEY_FILE_ID, fileId)
        put(KEY_FILE_NAME, fileName)
        put(KEY_DESTINATION_URI, destinationUri)
        put(KEY_DOWNLOADED_BYTES, downloadedBytes)
        put(KEY_TOTAL_BYTES, totalBytes)
        put(KEY_ETAG, etag)
        put(KEY_LANGUAGE, language)
        put(KEY_PHASE, phase.storageValue)
        put(KEY_NOTICE, notice.storageValue)
        put(KEY_CREATED_AT, createdAt)
    }

    private fun JSONObject.toDownloadTransfer(): DownloadTransfer? {
        val id = optString(KEY_TRANSFER_ID)
        if (id.isBlank()) return null
        return DownloadTransfer(
            transferId = id,
            accountKey = optString(KEY_ACCOUNT_KEY),
            fileId = optLong(KEY_FILE_ID, 0L),
            fileName = optString(KEY_FILE_NAME),
            destinationUri = optString(KEY_DESTINATION_URI),
            downloadedBytes = optLong(KEY_DOWNLOADED_BYTES, 0L),
            totalBytes = optLong(KEY_TOTAL_BYTES, 0L),
            etag = optString(KEY_ETAG),
            language = optString(KEY_LANGUAGE),
            phase = DownloadPhase.fromStorage(optString(KEY_PHASE)),
            notice = TransferNotice.fromStorage(optString(KEY_NOTICE)),
            createdAt = optLong(KEY_CREATED_AT, 0L),
        )
    }

    companion object {
        private const val KEY_TRANSFER_ID = "transferId"
        private const val KEY_ACCOUNT_KEY = "accountKey"
        private const val KEY_SOURCE_URI = "sourceUri"
        private const val KEY_DISPLAY_NAME = "displayName"
        private const val KEY_EXPECTED_SIZE = "expectedSize"
        private const val KEY_LAST_MODIFIED = "lastModified"
        private const val KEY_PARENT_ID = "parentId"
        private const val KEY_PATH_STRING = "pathString"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_BATCH_ID = "batchId"
        private const val KEY_BATCH_TOTAL_BYTES = "batchTotalBytes"
        private const val KEY_FILE_BYTES = "fileBytes"
        private const val KEY_CHECKPOINT_DIR = "checkpointDir"
        private const val KEY_PHASE = "phase"
        private const val KEY_BUCKET = "bucket"
        private const val KEY_REGION = "region"
        private const val KEY_OBJECT_KEY = "objectKey"
        private const val KEY_UPLOAD_ID = "uploadId"
        private const val KEY_NOTICE = "notice"
        private const val KEY_CREATED_AT = "createdAt"
        private const val KEY_TRANSFERRED_BYTES = "transferredBytes"
        private const val KEY_FILE_ID = "fileId"
        private const val KEY_FILE_NAME = "fileName"
        private const val KEY_DESTINATION_URI = "destinationUri"
        private const val KEY_DOWNLOADED_BYTES = "downloadedBytes"
        private const val KEY_TOTAL_BYTES = "totalBytes"
        private const val KEY_ETAG = "etag"
    }
}
