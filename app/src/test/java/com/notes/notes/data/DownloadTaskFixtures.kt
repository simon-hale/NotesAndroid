package com.notes.notes.data

import com.notes.notes.core.DownloadPhase

/**
 * Builds one round-accounting task exactly the way the ViewModel does, through the production
 * [downloadTaskProgress] factory, so the tests exercise the real "is this download active" rule instead
 * of a copy of it.
 *
 * `hasLiveWork` mirrors an unfinished WorkManager item and `withinEnqueueGrace` mirrors a transfer whose
 * work was requested a moment ago.
 */
internal fun downloadTask(
    id: String,
    downloaded: Long,
    total: Long,
    phase: DownloadPhase = DownloadPhase.TRANSFERRING,
    hasLiveWork: Boolean = true,
    withinEnqueueGrace: Boolean = false,
): DownloadTaskProgress = downloadTaskProgress(
    transferId = id,
    phase = phase,
    totalBytes = total,
    downloadedBytes = downloaded,
    hasLiveWork = hasLiveWork,
    withinEnqueueGrace = withinEnqueueGrace,
)
