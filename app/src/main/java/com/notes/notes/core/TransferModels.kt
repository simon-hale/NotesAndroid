package com.notes.notes.core

import androidx.compose.runtime.Immutable

/**
 * Persistent upload phases.
 *
 * `TRANSFERRING` and `PAUSED` mean the OSS multipart upload still exists and the backend database
 * must not be touched. `METADATA_PENDING` means `CompleteMultipartUpload` already succeeded, so the
 * complete object exists in the bucket and only `/api/file/insert/` is still outstanding.
 */
enum class UploadPhase(val storageValue: String) {
    TRANSFERRING("transferring"),
    PAUSED("paused"),
    METADATA_PENDING("metadata_pending");

    companion object {
        fun fromStorage(raw: String?): UploadPhase =
            entries.firstOrNull { it.storageValue == raw } ?: TRANSFERRING
    }
}

/** Persistent download phases. A paused download keeps its partial MediaStore item and byte count. */
enum class DownloadPhase(val storageValue: String) {
    TRANSFERRING("transferring"),
    PAUSED("paused");

    companion object {
        fun fromStorage(raw: String?): DownloadPhase =
            entries.firstOrNull { it.storageValue == raw } ?: TRANSFERRING
    }
}

/**
 * One-off notices a transfer worker wants to surface to the user once. They are persisted next to the
 * transfer because the worker has no way to talk to the UI directly.
 */
enum class TransferNotice(val storageValue: String) {
    NONE("none"),
    RESTARTED_REMOTE_CHANGED("restarted_remote_changed"),
    RESTARTED_PARTIAL_MISSING("restarted_partial_missing"),
    RESTARTED_RANGE_IGNORED("restarted_range_ignored"),
    SOURCE_CHANGED("source_changed");

    companion object {
        fun fromStorage(raw: String?): TransferNotice =
            entries.firstOrNull { it.storageValue == raw } ?: NONE
    }
}

/**
 * Persistent record of one logical upload.
 *
 * The transfer target (parentId, pathString, display name) is frozen here at creation time: credential
 * refreshes and metadata commits for an existing transfer must never be derived from whatever
 * directory the user happens to be browsing later.
 *
 * Credentials are deliberately absent: STS tickets, the JWT and presigned URLs are never persisted.
 */
@Immutable
data class UploadTransfer(
    val transferId: String,
    val accountKey: String,
    val sourceUri: String,
    val displayName: String,
    val expectedSize: Long,
    val lastModified: Long,
    val parentId: Long,
    val pathString: String,
    val language: String,
    val batchId: String,
    val batchTotalBytes: Long,
    val fileBytes: Long,
    val checkpointDir: String,
    val phase: UploadPhase,
    val bucket: String,
    val region: String,
    val objectKey: String,
    val uploadId: String,
    val notice: TransferNotice,
    val createdAt: Long,
    /** Last observed part progress, kept so a paused row can still show where it stopped. */
    val transferredBytes: Long,
) {
    val isMetadataPending: Boolean get() = phase == UploadPhase.METADATA_PENDING
}

/**
 * Persistent record of one logical download.
 *
 * The presigned URL is intentionally missing: every resume attempt asks `/api/file/url/` again.
 */
@Immutable
data class DownloadTransfer(
    val transferId: String,
    val accountKey: String,
    val fileId: Long,
    val fileName: String,
    val destinationUri: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val etag: String,
    val language: String,
    val phase: DownloadPhase,
    val notice: TransferNotice,
    val createdAt: Long,
)

/** Upload row shown by the disk screen, merged from the transfer store and live WorkManager state. */
@Immutable
data class UploadTransferEntry(
    val transferId: String,
    val displayName: String,
    val phase: UploadPhase,
    val fileBytes: Long,
    val transferredBytes: Long,
    val waitingForNetwork: Boolean,
)

/** Download row shown by the downloaded-files sheet. */
@Immutable
data class DownloadTransferEntry(
    val transferId: String,
    val fileName: String,
    val phase: DownloadPhase,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val waitingForNetwork: Boolean,
) {
    val fraction: Float
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}
