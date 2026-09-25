package com.notes.notes.data

/**
 * Byte accounting of one logical download.
 *
 * This is pure state on purpose: the resume rules are the part of a download that must never be
 * wrong, and a restart has to discard *all* knowledge about the previous representation. In
 * particular the total length belongs to the object that was being fetched, so a restart clears it
 * together with the byte count and the ETag: the length of an old object must never be used to decide
 * whether the end of a replacement object's stream means the download is complete.
 */
class DownloadProgressState(
    downloadedBytes: Long = 0L,
    totalBytes: Long = 0L,
    etag: String = "",
) {

    var downloadedBytes: Long = downloadedBytes.coerceAtLeast(0L)
        private set

    /** Total length of the object being fetched, or `0` while it is unknown. */
    var totalBytes: Long = totalBytes.coerceAtLeast(0L)
        private set

    /** ETag of the object being fetched, or `""` while it is unknown. */
    var etag: String = etag
        private set

    /** True while the destination still has to be truncated and written from byte zero. */
    var restartFromZero: Boolean = false
        private set

    val hasKnownTotal: Boolean get() = totalBytes > 0L

    val fraction: Float
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    fun recordBytesRead(count: Long) {
        if (count > 0L) downloadedBytes += count
    }

    /** Adopts the remaining length reported by a `206 Partial Content` response. */
    fun takeResumedTotal(statusCode: Int, contentLength: Long, requestedOffset: Long) {
        if (contentLength < 0L) return
        totalBytes = if (statusCode == HTTP_PARTIAL_CONTENT && requestedOffset > 0L) {
            requestedOffset + contentLength
        } else {
            contentLength
        }
    }

    /** Adopts the length of a response that starts at byte zero. */
    fun takeFreshTotal(contentLength: Long) {
        if (contentLength >= 0L) totalBytes = contentLength
    }

    fun takeETag(value: String) {
        if (value.isNotBlank()) etag = value
    }

    /**
     * Discards everything known about the previous representation.
     *
     * The next response establishes a fresh total length; until then completion cannot be judged
     * against the old object's size.
     */
    fun requestRestartFromZero() {
        downloadedBytes = 0L
        totalBytes = 0L
        etag = ""
        restartFromZero = true
    }

    fun consumeRestartFromZero(): Boolean {
        val restart = restartFromZero
        restartFromZero = false
        return restart
    }

    /**
     * True when a clean end of stream means the whole object arrived.
     *
     * With an unknown total there is nothing to verify against, so the end of the stream is the only
     * completion signal available; a known total must be satisfied.
     */
    fun isCompleteAfterEndOfStream(): Boolean = totalBytes <= 0L || downloadedBytes >= totalBytes

    private companion object {
        const val HTTP_PARTIAL_CONTENT = 206
    }
}
