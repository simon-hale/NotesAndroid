package com.notes.notes.data

/** What a download attempt must do with the bytes that are already on disk. */
enum class DownloadPlan {
    /** Seek to the recorded offset and append the new bytes after it. */
    CONTINUE_FROM_OFFSET,

    /** Truncate the destination and fetch the object from byte zero. */
    RESTART_FROM_ZERO,
}

/**
 * Pure decisions around resuming a download.
 *
 * The invariant this encodes is that an old object prefix must never be combined with a new object's
 * suffix: whenever the server cannot prove that the bytes at the recorded offset still belong to the
 * object being fetched, the download restarts from zero instead of appending.
 */
object DownloadResumePolicy {

    /**
     * Interprets the HTTP status of a ranged GET.
     *
     * - `206` means the server honoured `Range`, so the response continues the partial file.
     * - `200` while a non-zero range was requested means the server ignored it: the body starts at
     *   byte zero, so appending it would corrupt the file.
     * - `412` means the remote object changed and `416` means the requested range is not satisfiable;
     *   both invalidate the partial file.
     */
    fun planForResponse(statusCode: Int, requestedOffset: Long): DownloadPlan = when (statusCode) {
        HTTP_OK -> if (requestedOffset > 0L) DownloadPlan.RESTART_FROM_ZERO else DownloadPlan.CONTINUE_FROM_OFFSET
        HTTP_PRECONDITION_FAILED, HTTP_RANGE_NOT_SATISFIABLE -> DownloadPlan.RESTART_FROM_ZERO
        else -> DownloadPlan.CONTINUE_FROM_OFFSET
    }

    /**
     * Decides whether the MediaStore destination can still be resumed.
     *
     * Pending MediaStore items are not permanent and may be removed by the system, and a destination
     * that is shorter than the recorded progress lost the bytes the transfer counted on.
     */
    fun planForDestination(
        destinationExists: Boolean,
        destinationSize: Long,
        recordedBytes: Long,
    ): DownloadPlan = when {
        !destinationExists -> DownloadPlan.RESTART_FROM_ZERO
        destinationSize < recordedBytes -> DownloadPlan.RESTART_FROM_ZERO
        else -> DownloadPlan.CONTINUE_FROM_OFFSET
    }

    /** The `Range` header value for a resume, or `null` when the whole object is fetched. */
    fun rangeHeaderValue(offset: Long): String? = if (offset > 0L) "bytes=$offset-" else null

    private const val HTTP_OK = 200
    private const val HTTP_PRECONDITION_FAILED = 412
    private const val HTTP_RANGE_NOT_SATISFIABLE = 416
}
