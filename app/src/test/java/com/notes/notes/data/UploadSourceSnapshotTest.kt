package com.notes.notes.data

import com.notes.notes.core.TransferNotice
import com.notes.notes.core.UploadPhase
import com.notes.notes.core.UploadTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The source snapshot of a resumable upload.
 *
 * The scenario these tests protect is: upload revision A, pause, modify the document to revision B,
 * resume (B replaces A), pause B, resume B — the second resume of B must continue B instead of
 * invalidating it again because the persisted snapshot still describes A.
 */
class UploadSourceSnapshotTest {

    private val ticket = stsToken()
    private val checkpointDirectory = "/data/oss-resume/t1"

    private val revisionA = UploadSourceStats(size = 1_000L, lastModified = 111L)
    private val revisionB = UploadSourceStats(size = 2_500L, lastModified = 222L)

    @Test
    fun `revision B is adopted and the second resume of B continues it`() {
        // 1. upload revision A
        var transfer = uploadTransfer(expectedSize = revisionA.size, lastModified = revisionA.lastModified)
        var plan = planFor(transfer, revisionA)
        assertFalse(plan.sourceChanged)
        assertEquals(1_000L, plan.transfer.expectedSize)
        transfer = plan.transfer

        // 2. pause A: the worker captured the multipart id and some progress
        transfer = transfer.copy(uploadId = "upload-A", transferredBytes = 900L)

        // 3. the local document is modified to revision B, then resumed
        plan = planFor(transfer, revisionB)
        assertTrue(plan.sourceChanged)
        transfer = plan.transfer
        assertEquals(revisionB.size, transfer.expectedSize)
        assertEquals(revisionB.lastModified, transfer.lastModified)
        assertEquals("", transfer.uploadId)
        assertEquals(0L, transfer.transferredBytes)
        assertEquals(TransferNotice.SOURCE_CHANGED, transfer.notice)
        assertEquals(2_500L, transfer.fileBytes)

        // 4. pause B
        transfer = transfer.copy(uploadId = "upload-B", transferredBytes = 500L)

        // 5. resume B: it is the same revision as the persisted snapshot, so nothing is invalidated
        plan = planFor(transfer, revisionB)
        assertFalse(plan.sourceChanged)
        assertEquals("upload-B", plan.transfer.uploadId)
        assertEquals(500L, plan.transfer.transferredBytes)
        assertEquals(revisionB.size, plan.transfer.expectedSize)
        assertEquals(revisionB.lastModified, plan.transfer.lastModified)
    }

    @Test
    fun `a modification of B is still detected after B was adopted`() {
        val afterB = planFor(
            uploadTransfer(expectedSize = revisionB.size, lastModified = revisionB.lastModified),
            revisionB,
        ).transfer

        val revisionC = UploadSourceStats(size = 3_000L, lastModified = 333L)
        val plan = planFor(afterB, revisionC)

        assertTrue(plan.sourceChanged)
        assertEquals(3_000L, plan.transfer.expectedSize)
        assertEquals(333L, plan.transfer.lastModified)
    }

    @Test
    fun `an unknown picker size is learned without counting as a modification`() {
        // The picker reported no usable size (0), so the worker's reading becomes the snapshot.
        val transfer = uploadTransfer(expectedSize = 0L, lastModified = 0L)

        val plan = planFor(transfer, revisionA)

        assertFalse(plan.sourceChanged)
        assertEquals(1_000L, plan.transfer.expectedSize)
        assertEquals(111L, plan.transfer.lastModified)
        assertEquals(TransferNotice.NONE, plan.transfer.notice)

        // Once learned, a later modification is detected against it.
        val changed = planFor(plan.transfer, revisionB)
        assertTrue(changed.sourceChanged)
        assertEquals(2_500L, changed.transfer.expectedSize)
    }

    @Test
    fun `a known size without a provider timestamp is partially learned`() {
        val transfer = uploadTransfer(expectedSize = 1_000L, lastModified = 0L)

        val plan = planFor(transfer, revisionA)

        assertFalse(plan.sourceChanged)
        assertEquals(1_000L, plan.transfer.expectedSize)
        assertEquals(111L, plan.transfer.lastModified)
    }

    @Test
    fun `a provider that reports no timestamp is ignored rather than treated as a change`() {
        val transfer = uploadTransfer(expectedSize = 1_000L, lastModified = 111L)

        val plan = planFor(transfer, UploadSourceStats(size = 1_000L, lastModified = 0L))

        assertFalse(plan.sourceChanged)
        assertEquals(111L, plan.transfer.lastModified)
    }

    @Test
    fun `a learned snapshot is never overwritten by an unknown reading`() {
        val transfer = uploadTransfer(expectedSize = 1_000L, lastModified = 111L)

        val plan = planFor(transfer, UploadSourceStats(size = 1_000L, lastModified = 0L))

        assertEquals(1_000L, plan.transfer.expectedSize)
        assertEquals(111L, plan.transfer.lastModified)
    }

    @Test
    fun `the frozen scope survives a source change`() {
        val transfer = uploadTransfer(
            expectedSize = revisionA.size,
            lastModified = revisionA.lastModified,
            bucket = "notes-bucket",
            region = "oss-cn-hangzhou",
            objectKey = "a/b/c.md",
            uploadId = "upload-A",
        )

        val plan = planFor(transfer, revisionB)

        assertTrue(plan.sourceChanged)
        assertEquals("notes-bucket", plan.transfer.bucket)
        assertEquals("oss-cn-hangzhou", plan.transfer.region)
        assertEquals("a/b/c.md", plan.transfer.objectKey)
        assertEquals(checkpointDirectory, plan.transfer.checkpointDir)
    }

    @Test
    fun `a source change keeps the already persisted notice until it is consumed`() {
        val transfer = uploadTransfer(
            expectedSize = revisionA.size,
            lastModified = revisionA.lastModified,
            notice = TransferNotice.SOURCE_CHANGED,
        )

        // No new change: the pending notice must not be cleared by an unrelated resume.
        val plan = planFor(transfer, revisionA)

        assertFalse(plan.sourceChanged)
        assertEquals(TransferNotice.SOURCE_CHANGED, plan.transfer.notice)
    }

    private fun planFor(transfer: UploadTransfer, stats: UploadSourceStats): UploadSourcePlan {
        val preparation = UploadSourceSnapshot.prepare(
            current = transfer,
            stats = stats,
            ticket = ticket,
            checkpointDirectory = checkpointDirectory,
        )
        return (preparation as UploadAttemptPreparation.Accepted).plan
    }

    private companion object {
        fun stsToken() = OssStsToken(
            overwriteSameName = false,
            region = "oss-cn-hangzhou",
            bucket = "notes-bucket",
            accessKeyId = "ak",
            accessKeySecret = "sk",
            securityToken = "st",
            objectKey = "a/b/c.md",
            expiration = Long.MAX_VALUE / 1000L,
        )

        fun uploadTransfer(
            expectedSize: Long,
            lastModified: Long,
            bucket: String = "",
            region: String = "",
            objectKey: String = "",
            uploadId: String = "",
            notice: TransferNotice = TransferNotice.NONE,
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
            batchTotalBytes = 2_500L,
            fileBytes = expectedSize.coerceAtLeast(1L),
            checkpointDir = "/data/oss-resume/t1",
            phase = UploadPhase.PAUSED,
            bucket = bucket,
            region = region,
            objectKey = objectKey,
            uploadId = uploadId,
            notice = notice,
            createdAt = 0L,
            transferredBytes = 0L,
        )
    }
}
