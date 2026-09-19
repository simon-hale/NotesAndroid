package com.notes.notes.data

import android.content.Context
import java.io.File

/**
 * Deletes leftovers of the retired upload path that copied every picked document into `cacheDir`
 * before uploading it. Uploads now stream straight from the picked document, so nothing else writes
 * into this directory and any remaining file is stale.
 */
object UploadCacheCleaner {
    private const val UPLOAD_CACHE_DIR = "upload-cache"

    fun clearStale(context: Context) {
        val cacheRootPath = context.cacheDir.runCatching { canonicalPath }.getOrNull() ?: return
        val uploadCacheDir = File(cacheRootPath, UPLOAD_CACHE_DIR)
        val uploadCachePath = uploadCacheDir.runCatching { canonicalPath }.getOrNull() ?: return
        if (!uploadCachePath.startsWith("$cacheRootPath${File.separator}")) return
        uploadCacheDir.deleteRecursively()
    }
}
