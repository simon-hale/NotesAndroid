package com.notes.notes.data

import android.content.Context
import java.io.File

object PreviewCacheCleaner {
    private const val PREVIEW_CACHE_DIR = "preview-cache"

    fun clearAll(context: Context) {
        deleteFiles(context, previewCacheDir(context).listFiles().orEmpty().map(File::getAbsolutePath))
    }

    fun deleteFiles(context: Context, paths: Collection<String>) {
        val cacheDirPath = previewCacheDir(context).runCatching { canonicalPath }.getOrNull() ?: return
        val cacheDirPrefix = "$cacheDirPath${File.separator}"
        paths.asSequence()
            .map(::File)
            .distinctBy { it.absolutePath }
            .forEach { file ->
                val canonicalPath = file.runCatching { canonicalPath }.getOrNull() ?: return@forEach
                if (canonicalPath != cacheDirPath && !canonicalPath.startsWith(cacheDirPrefix)) return@forEach
                file.delete()
            }
    }

    fun pruneStale(context: Context, maxAgeMillis: Long, nowMillis: Long = System.currentTimeMillis()) {
        if (maxAgeMillis <= 0L) return
        val expirationTime = nowMillis - maxAgeMillis
        previewCacheDir(context)
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isFile)
            .filter { it.lastModified() in 1 until expirationTime }
            .forEach(File::delete)
    }

    private fun previewCacheDir(context: Context): File {
        return File(context.cacheDir, PREVIEW_CACHE_DIR).apply { mkdirs() }
    }
}
