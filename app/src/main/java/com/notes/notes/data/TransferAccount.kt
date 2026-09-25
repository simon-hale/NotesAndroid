package com.notes.notes.data

import android.content.Context
import kotlinx.coroutines.flow.first

/**
 * The account that owns the persisted transfers on this device.
 *
 * Transfer records are keyed by the account name they were created for, so a task created while
 * signed in as one user can never be resumed, refreshed or committed under another account. A worker
 * that is not authorized to operate a transfer stops without touching it: cleaning a foreign owner's
 * records, checkpoints or MediaStore items is never a side effect of running someone else's work.
 */
data class TransferAccountSnapshot(
    val username: String,
    val accessToken: String,
) {

    /**
     * True when [accountKey] may be operated on by this account.
     *
     * When the device does not remember a username there is nothing to compare against, so the work
     * item's own owner key and its fallback token become the only authority (see [accessTokenFor]).
     */
    fun owns(accountKey: String): Boolean = when {
        accountKey.isBlank() -> true
        username.isBlank() -> true
        else -> accountKey == username
    }

    /**
     * Prefers the currently stored token: a resumed transfer must not sign with a stale one.
     *
     * The fallback token travelled through WorkManager together with the transfer it was enqueued
     * for, so it may only ever be used for that same owner. A transfer owned by another account is
     * never operated on with this session's credentials.
     */
    fun accessTokenFor(
        fallback: String,
        transferAccountKey: String,
        workAccountKey: String,
    ): String {
        if (accessToken.isNotBlank()) return accessToken
        if (transferAccountKey.isBlank()) return fallback
        return if (transferAccountKey == workAccountKey) fallback else ""
    }
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
