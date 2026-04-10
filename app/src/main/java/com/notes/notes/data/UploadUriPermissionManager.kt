package com.notes.notes.data

import android.content.Context
import android.content.Intent
import android.net.Uri

object UploadUriPermissionManager {
    private const val READ_PERMISSION_FLAG = Intent.FLAG_GRANT_READ_URI_PERMISSION

    fun persistReadPermission(context: Context, uri: Uri): Throwable? {
        return runCatching {
            context.contentResolver.takePersistableUriPermission(uri, READ_PERMISSION_FLAG)
        }.exceptionOrNull()
    }

    fun releaseReadPermission(context: Context, uri: Uri): Throwable? {
        val hasPersistedReadPermission = context.contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && permission.uri == uri
        }
        if (!hasPersistedReadPermission) return null

        return runCatching {
            context.contentResolver.releasePersistableUriPermission(uri, READ_PERMISSION_FLAG)
        }.exceptionOrNull()
    }

    fun releaseAllPersistedReadPermissions(context: Context): List<Throwable> {
        val persistedUris = context.contentResolver.persistedUriPermissions
            .asSequence()
            .filter { it.isReadPermission }
            .map { it.uri }
            .distinct()
            .toList()

        return persistedUris.mapNotNull { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(uri, READ_PERMISSION_FLAG)
            }.exceptionOrNull()
        }
    }
}
