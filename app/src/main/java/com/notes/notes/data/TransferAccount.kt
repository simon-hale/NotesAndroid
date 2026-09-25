package com.notes.notes.data

import android.content.Context
import kotlinx.coroutines.flow.first

/**
 * The account that owns the persisted transfers on this device.
 *
 * Transfer records are keyed by the account name they were created for, so a task created while
 * signed in as one user can never be resumed, refreshed or committed under another account.
 */
data class TransferAccountSnapshot(
    val username: String,
    val accessToken: String,
) {

    /**
     * True when [accountKey] may be operated on by this account.
     *
     * When the device does not remember a username there is nothing to compare against, so the
     * in-memory session that enqueued the work stays the only authority.
     */
    fun owns(accountKey: String): Boolean = when {
        accountKey.isBlank() -> true
        username.isBlank() -> true
        else -> accountKey == username
    }

    /** Prefers the currently stored token: a resumed transfer must not sign with a stale one. */
    fun accessTokenOr(fallback: String): String = accessToken.ifBlank { fallback }
}

object TransferAccount {

    suspend fun current(context: Context): TransferAccountSnapshot {
        val stored = runCatching { AppPreferencesStore(context).preferences.first() }.getOrNull()
        return TransferAccountSnapshot(
            username = stored?.savedUsername.orEmpty(),
            accessToken = stored?.savedAccessToken.orEmpty(),
        )
    }
}
