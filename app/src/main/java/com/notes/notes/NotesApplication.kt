package com.notes.notes

import android.app.Application
import com.notes.notes.data.PreviewCacheCleaner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotesApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            PreviewCacheCleaner.pruneStale(
                context = this@NotesApplication,
                maxAgeMillis = PREVIEW_CACHE_MAX_AGE_MILLIS,
            )
        }
    }

    private companion object {
        const val PREVIEW_CACHE_MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L
    }
}
