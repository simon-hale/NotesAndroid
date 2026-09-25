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

    fun persistReadPermissionIfNeeded(context: Context, uri: Uri): Throwable? {
        if (hasPersistedReadPermission(context, uri)) return null
        return persistReadPermission(context, uri)
    }

    fun hasPersistedReadPermission(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && permission.uri == uri
        }

    /** Every document this app currently holds a persisted read permission for. */
    fun persistedReadPermissionUris(context: Context): List<String> =
        context.contentResolver.persistedUriPermissions
            .asSequence()
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .distinct()
            .toList()

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
