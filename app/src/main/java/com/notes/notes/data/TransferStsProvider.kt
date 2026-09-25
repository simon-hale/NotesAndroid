package com.notes.notes.data

import android.os.Looper
import com.alibaba.sdk.android.oss.ClientException
import com.alibaba.sdk.android.oss.common.auth.OSSFederationCredentialProvider
import com.alibaba.sdk.android.oss.common.auth.OSSFederationToken
import com.notes.notes.core.AppLanguage
import com.notes.notes.core.UploadTransfer
import kotlinx.coroutines.runBlocking

/**
 * Task-scoped OSS credentials for exactly one upload.
 *
 * Notes STS tickets are scoped to a single object key, so a shared, application-wide federation
 * provider would be able to hand out credentials for a different object than the one a running
 * multipart upload belongs to. Every resumable task therefore gets its own provider, permanently
 * bound to the transfer's frozen target.
 *
 * The provider re-requests a ticket shortly before the current one expires, using the same frozen
 * `pathString`, `parentId` and filename, and rejects a refreshed ticket whose bucket, region or
 * object key moved: signing with credentials for another object would corrupt the transfer.
 * Refreshes are invisible to the user and never run on the main thread.
 */
class TransferStsCredentialProvider(
    private val transfer: UploadTransfer,
    private val accessToken: String,
    private val backendService: NotesBackendService,
    firstTicket: OssStsToken,
) : OSSFederationCredentialProvider() {

    private val lock = Any()

    private var ticket: OssStsToken = firstTicket

    /** The OSS identity this task is frozen to; every refreshed ticket must stay inside it. */
    private val frozenScope: UploadTargetScope = UploadTargetScope.of(firstTicket)

    /** Fetches a ticket through the shared OSS client factory so connection settings stay identical. */
    override fun getFederationToken(): OSSFederationToken = getValidFederationToken()

    override fun getValidFederationToken(): OSSFederationToken {
        // The SDK calls this from its own upload threads before signing each request; the base class
        // caching is re-implemented here because the first ticket is supplied by the caller.
        val current = synchronized(lock) {
            if (isExpiringSoon(ticket)) {
                refresh(ticket) ?: ticket
            } else {
                ticket
            }
        }
        return OSSFederationToken(
            current.accessKeyId,
            current.accessKeySecret,
            current.securityToken,
            current.expiration.takeIf { it > 0L } ?: FAR_FUTURE_EXPIRATION_SECONDS,
        )
    }

    private fun isExpiringSoon(candidate: OssStsToken): Boolean {
        if (candidate.expiration <= 0L) return false
        val nowSeconds = System.currentTimeMillis() / 1000L
        return nowSeconds >= candidate.expiration - REFRESH_MARGIN_SECONDS
    }

    /**
     * Requests a new ticket for the frozen target.
     *
     * Returns the new ticket, or `null` when the refresh failed but the previous ticket is still
     * valid, so a transient backend hiccup does not kill an in-flight multipart upload. When the
     * previous ticket is already expired the failure is propagated to the SDK instead.
     */
    private fun refresh(previous: OssStsToken): OssStsToken? {
        if (Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper()) {
            throw ClientException("STS refresh must not run on the main thread")
        }
        val refreshed = try {
            requestTicket()
        } catch (error: Throwable) {
            if (isExpired(previous)) {
                throw ClientException("Unable to refresh the OSS credentials", error)
            }
            return null
        }
        validate(refreshed)
        ticket = refreshed
        return refreshed
    }

    private fun isExpired(candidate: OssStsToken): Boolean {
        if (candidate.expiration <= 0L) return false
        return System.currentTimeMillis() / 1000L >= candidate.expiration
    }

    private fun requestTicket(): OssStsToken = runBlocking {
        backendService.requestOssSts(
            accessToken = accessToken,
            pathString = transfer.pathString,
            filename = transfer.displayName,
            parentId = transfer.parentId,
            language = AppLanguage.fromCode(transfer.language),
            usage = OSS_USAGE_SINGLE_FILE_UPLOAD,
        )
    }

    /**
     * A resumed upload keeps writing the same object, so a ticket that moved to another bucket,
     * region or object key must never be used to continue it. The check is the same one the worker
     * applies to the first ticket, so the frozen scope holds for the whole lifetime of the task.
     */
    private fun validate(refreshed: OssStsToken) {
        if (!refreshed.hasCredentials) {
            throw ClientException("Refreshed OSS credentials are incomplete")
        }
        val field = UploadScopeGuard.mismatch(frozenScope, refreshed) ?: return
        throw ClientException("Refreshed OSS credentials changed the ${field.name.lowercase()}")
    }

    private companion object {
        const val REFRESH_MARGIN_SECONDS = 120L
        const val OSS_USAGE_SINGLE_FILE_UPLOAD = "SINGLE_FILE_UPLOAD"
        const val FAR_FUTURE_EXPIRATION_SECONDS = Long.MAX_VALUE / 1000L
    }
}
