package com.notes.notes.data

import com.notes.notes.core.UploadTransfer

/**
 * The OSS identity a logical upload was frozen to.
 *
 * A resumable multipart upload can only ever be continued inside the exact bucket, region and object
 * key it was started in. Once any of these values has been persisted for a transfer it must not be
 * replaced, so a ticket that no longer matches means the transfer needs explicit user action instead
 * of a silent restart somewhere else.
 */
data class UploadTargetScope(
    val bucket: String,
    val region: String,
    val objectKey: String,
) {
    val isUnfrozen: Boolean
        get() = bucket.isBlank() && region.isBlank() && objectKey.isBlank()

    companion object {
        fun of(transfer: UploadTransfer): UploadTargetScope = UploadTargetScope(
            bucket = transfer.bucket,
            region = transfer.region,
            objectKey = transfer.objectKey,
        )

        fun of(ticket: OssStsToken): UploadTargetScope = UploadTargetScope(
            bucket = ticket.bucket,
            region = ticket.region,
            objectKey = ticket.objectKey,
        )
    }
}

/** The part of a frozen OSS target that a newly requested ticket no longer matches. */
enum class UploadScopeField {
    BUCKET,
    REGION,
    OBJECT_KEY,
}

/**
 * Raised when a fresh STS ticket points somewhere else than the frozen target of an existing
 * resumable transfer.
 *
 * This is deliberately separate from a local-source change: a moved OSS target must fail closed and
 * preserve the existing resumable task, never silently restart it under the new scope.
 */
class UploadTargetChangedException(
    val field: UploadScopeField,
    message: String,
) : Exception(message)

/** Freezes and validates the OSS identity of a transfer. */
object UploadScopeGuard {

    /**
     * Returns the frozen field that [ticket] no longer matches, or `null` when the ticket is inside
     * the frozen scope.
     *
     * Only non-empty frozen values are compared, so a transfer whose scope was never frozen (a brand
     * new task) accepts any ticket, and a partially frozen legacy record keeps validating the values
     * it does have.
     */
    fun mismatch(frozen: UploadTargetScope, ticket: OssStsToken): UploadScopeField? {
        if (frozen.bucket.isNotBlank() && frozen.bucket != ticket.bucket) return UploadScopeField.BUCKET
        if (frozen.region.isNotBlank() && frozen.region != ticket.region) return UploadScopeField.REGION
        if (frozen.objectKey.isNotBlank() && frozen.objectKey != ticket.objectKey) {
            return UploadScopeField.OBJECT_KEY
        }
        return null
    }

    /**
     * Fills the still empty parts of the frozen scope from [ticket].
     *
     * Values that are already frozen are returned unchanged, so a transfer can never be re-pointed at
     * another bucket, region or object key by a later ticket.
     */
    fun freeze(frozen: UploadTargetScope, ticket: OssStsToken): UploadTargetScope = UploadTargetScope(
        bucket = frozen.bucket.ifBlank { ticket.bucket },
        region = frozen.region.ifBlank { ticket.region },
        objectKey = frozen.objectKey.ifBlank { ticket.objectKey },
    )
}
