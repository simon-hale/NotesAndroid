package com.notes.notes.data

import com.notes.notes.core.TransferNotice
import com.notes.notes.core.UploadPhase
import com.notes.notes.core.UploadTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frozen OSS identity of a resumable upload.
 *
 * A ticket that moved to another bucket, region or object key must never continue a multipart upload
 * and must never replace the persisted scope, so the transfer (and with it the SDK checkpoint) stays
 * exactly where it was until the user explicitly cancels it.
 */
class UploadScopeGuardTest {

    private val ticket = stsToken(bucket = "notes-bucket", region = "oss-cn-hangzhou", objectKey = "a/b/c.md")

    @Test
    fun `a ticket inside the frozen scope is accepted`() {
        val frozen = UploadTargetScope("notes-bucket", "oss-cn-hangzhou", "a/b/c.md")

        assertNull(UploadScopeGuard.mismatch(frozen, ticket))
    }

    @Test
    fun `a changed bucket is rejected`() {
        val frozen = UploadTargetScope("other-bucket", "oss-cn-hangzhou", "a/b/c.md")

        assertEquals(UploadScopeField.BUCKET, UploadScopeGuard.mismatch(frozen, ticket))
    }

    @Test
    fun `a changed region is rejected`() {
        val frozen = UploadTargetScope("notes-bucket", "oss-cn-beijing", "a/b/c.md")

        assertEquals(UploadScopeField.REGION, UploadScopeGuard.mismatch(frozen, ticket))
    }

    @Test
    fun `a changed object key is rejected`() {
        val frozen = UploadTargetScope("notes-bucket", "oss-cn-hangzhou", "a/b/other.md")

        assertEquals(UploadScopeField.OBJECT_KEY, UploadScopeGuard.mismatch(frozen, ticket))
    }

    @Test
    fun `an unfrozen transfer accepts the first ticket`() {
        val frozen = UploadTargetScope("", "", "")

        assertNull(UploadScopeGuard.mismatch(frozen, ticket))
        assertEquals(
            UploadTargetScope("notes-bucket", "oss-cn-hangzhou", "a/b/c.md"),
            UploadScopeGuard.freeze(frozen, ticket),
        )
    }

    @Test
    fun `freezing never re-points a value that is already frozen`() {
        val frozen = UploadTargetScope("notes-bucket", "", "a/b/c.md")

        val reFrozen = UploadScopeGuard.freeze(frozen, ticket.copy(bucket = "other", objectKey = "other/key"))

        assertEquals("notes-bucket", reFrozen.bucket)
        assertEquals("oss-cn-hangzhou", reFrozen.region)
        assertEquals("a/b/c.md", reFrozen.objectKey)
    }

    @Test
    fun `a rejected scope produces no prepared transfer to persist`() {
        val transfer = uploadTransfer(
            bucket = "notes-bucket",
            region = "oss-cn-hangzhou",
            objectKey = "a/b/c.md",
            uploadId = "upload-1",
            checkpointDir = "/data/oss-resume/t1",
            expectedSize = 1_000L,
            lastModified = 111L,
        )
        val movedTicket = ticket.copy(bucket = "elsewhere")

        val preparation = UploadSourceSnapshot.prepare(
            current = transfer,
            stats = UploadSourceStats(size = 1_000L, lastModified = 111L),
            ticket = movedTicket,
            checkpointDirectory = "/data/oss-resume/t1",
        )

        assertTrue(preparation is UploadAttemptPreparation.Rejected)
        assertEquals(UploadScopeField.BUCKET, (preparation as UploadAttemptPreparation.Rejected).field)
        // The worker has no plan in this case, so it cannot update the record, clear the upload id or
        // delete the checkpoint the preserved multipart upload still needs.
        assertEquals("upload-1", transfer.uploadId)
        assertEquals("/data/oss-resume/t1", transfer.checkpointDir)
        assertEquals(UploadPhase.TRANSFERRING, transfer.phase)
    }

    @Test
    fun `a matching scope still freezes an unfrozen region`() {
        val transfer = uploadTransfer(bucket = "notes-bucket", region = "", objectKey = "a/b/c.md")

        val preparation = UploadSourceSnapshot.prepare(
            current = transfer,
            stats = UploadSourceStats(size = 1_000L, lastModified = 111L),
            ticket = ticket,
            checkpointDirectory = "/data/oss-resume/t1",
        )

        val plan = (preparation as UploadAttemptPreparation.Accepted).plan
        assertEquals("oss-cn-hangzhou", plan.transfer.region)
        assertEquals("notes-bucket", plan.transfer.bucket)
        assertEquals("a/b/c.md", plan.transfer.objectKey)
    }

    @Test
    fun `the provider scope is taken from the first ticket and never widened`() {
        val firstTicket = ticket
        val scope = UploadTargetScope.of(firstTicket)

        // A later ticket with new credentials for the same object still fits the frozen scope.
        assertNull(UploadScopeGuard.mismatch(scope, firstTicket.copy(accessKeyId = "rotated")))
        // A ticket for another object does not, whichever field moved.
        assertEquals(UploadScopeField.OBJECT_KEY, UploadScopeGuard.mismatch(scope, firstTicket.copy(objectKey = "x")))
        assertEquals(UploadScopeField.REGION, UploadScopeGuard.mismatch(scope, firstTicket.copy(region = "oss-cn-beijing")))
        assertEquals(UploadScopeField.BUCKET, UploadScopeGuard.mismatch(scope, firstTicket.copy(bucket = "other")))
    }

    private companion object {
        fun stsToken(
            bucket: String,
            region: String,
            objectKey: String,
        ) = OssStsToken(
            overwriteSameName = false,
            region = region,
            bucket = bucket,
            accessKeyId = "ak",
            accessKeySecret = "sk",
            securityToken = "st",
            objectKey = objectKey,
            expiration = Long.MAX_VALUE / 1000L,
        )

        fun uploadTransfer(
            bucket: String,
            region: String,
            objectKey: String,
            uploadId: String = "",
            checkpointDir: String = "",
            expectedSize: Long = 0L,
            lastModified: Long = 0L,
        ) = UploadTransfer(
            transferId = "t1",
            accountKey = "alice",
            sourceUri = "content://documents/1",
            displayName = "notes.md",
            expectedSize = expectedSize,
            lastModified = lastModified,
            parentId = 7L,
            pathString = "1/7/",
            language = "en-US",
            batchId = "batch-1",
            batchTotalBytes = 1_000L,
            fileBytes = 1_000L,
            checkpointDir = checkpointDir,
            phase = UploadPhase.TRANSFERRING,
            bucket = bucket,
            region = region,
            objectKey = objectKey,
            uploadId = uploadId,
            notice = TransferNotice.NONE,
            createdAt = 0L,
            transferredBytes = 0L,
        )
    }
}
