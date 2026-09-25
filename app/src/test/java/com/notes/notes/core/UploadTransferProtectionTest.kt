package com.notes.notes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that protect a `METADATA_PENDING` transfer from destructive cleanup.
 *
 * A metadata-pending transfer is no longer a cancellable multipart upload: `CompleteMultipartUpload`
 * already succeeded, so the object exists in OSS while `/api/file/insert/` is still outstanding. Its
 * record is the only local recovery state, and the `AbortIncompleteMultipartUpload` lifecycle rule
 * cannot clean up a completed object. Deleting the record would therefore orphan a complete object
 * with no database row, and its source document is no longer read at all.
 */
class UploadTransferProtectionTest {

    @Test
    fun `transferring uploads are destructively cancellable and need their document`() {
        val transferring = upload(phase = UploadPhase.TRANSFERRING, uri = "content://documents/a")

        assertEquals(listOf(transferring), UploadTransfer.destructivelyCancelable(listOf(transferring)))
        assertEquals(
            setOf("content://documents/a"),
            UploadTransfer.sourceDocumentsInUse(listOf(transferring)),
        )
    }

    @Test
    fun `paused uploads are destructively cancellable and need their document`() {
        val paused = upload(phase = UploadPhase.PAUSED, uri = "content://documents/b")

        assertEquals(listOf(paused), UploadTransfer.destructivelyCancelable(listOf(paused)))
        assertEquals(setOf("content://documents/b"), UploadTransfer.sourceDocumentsInUse(listOf(paused)))
    }

    @Test
    fun `metadata pending uploads are never destructively cancellable`() {
        val finalizing = upload(phase = UploadPhase.METADATA_PENDING, uri = "content://documents/c")

        assertTrue(UploadTransfer.destructivelyCancelable(listOf(finalizing)).isEmpty())
        // Nothing to delete means the caller returns without touching the store, the work or the
        // checkpoint of that transfer.
        assertTrue(UploadTransfer.destructivelyCancelable(listOf(finalizing, finalizing)).isEmpty())
    }

    @Test
    fun `metadata pending uploads no longer consume their source document`() {
        val finalizing = upload(phase = UploadPhase.METADATA_PENDING, uri = "content://documents/c")

        assertTrue(UploadTransfer.sourceDocumentsInUse(listOf(finalizing)).isEmpty())
    }

    @Test
    fun `a mixed batch keeps only the unfinished uploads for a destructive cancel`() {
        val finalizing = upload(id = "mp", phase = UploadPhase.METADATA_PENDING, uri = "content://documents/c")
        val paused = upload(id = "paused", phase = UploadPhase.PAUSED, uri = "content://documents/b")
        val transferring = upload(id = "running", phase = UploadPhase.TRANSFERRING, uri = "content://documents/a")

        val batch = listOf(finalizing, paused, transferring)

        assertEquals(listOf(paused, transferring), UploadTransfer.destructivelyCancelable(batch))
        assertEquals(listOf(finalizing), UploadTransfer.metadataPending(batch))
        // The metadata-pending document may be released; the two unfinished ones may not.
        assertEquals(
            setOf("content://documents/a", "content://documents/b"),
            UploadTransfer.sourceDocumentsInUse(batch),
        )
    }

    @Test
    fun `a preserved metadata pending transfer keeps its recovery identity`() {
        val finalizing = upload(
            id = "mp",
            phase = UploadPhase.METADATA_PENDING,
            uri = "content://documents/c",
            parentId = 42L,
            pathString = "1/42/",
            displayName = "thesis.md",
            objectKey = "notes/1/42/thesis.md",
        )

        // Filtering is pure: the preserved record still carries everything `/api/file/insert/` needs
        // and never turns into another phase or a canceled state.
        val preserved = finalizing
        assertTrue(UploadTransfer.destructivelyCancelable(listOf(preserved)).isEmpty())
        assertEquals(UploadPhase.METADATA_PENDING, preserved.phase)
        assertEquals(42L, preserved.parentId)
        assertEquals("1/42/", preserved.pathString)
        assertEquals("thesis.md", preserved.displayName)
        assertEquals("notes/1/42/thesis.md", preserved.objectKey)
        assertTrue(preserved.isMetadataPending)
    }

    @Test
    fun `an ownerless or empty source uri is never treated as a document in use`() {
        val ownerless = upload(
            phase = UploadPhase.TRANSFERRING,
            uri = "",
        )

        assertTrue(UploadTransfer.sourceDocumentsInUse(listOf(ownerless)).isEmpty())
        assertEquals(listOf(ownerless), UploadTransfer.destructivelyCancelable(listOf(ownerless)))
    }

    private companion object {
        fun upload(
            id: String = "t1",
            phase: UploadPhase,
            uri: String,
            parentId: Long = 7L,
            pathString: String = "1/7/",
            displayName: String = "notes.md",
            objectKey: String = "notes/1/7/notes.md",
        ) = UploadTransfer(
            transferId = id,
            accountKey = "alice",
            sourceUri = uri,
            displayName = displayName,
            expectedSize = 1_000L,
            lastModified = 111L,
            parentId = parentId,
            pathString = pathString,
            language = "en-US",
            batchId = "batch-1",
            batchTotalBytes = 2_000L,
            fileBytes = 1_000L,
            checkpointDir = "/data/oss-resume/$id",
            phase = phase,
            bucket = "notes-bucket",
            region = "oss-cn-hangzhou",
            objectKey = objectKey,
            uploadId = if (phase == UploadPhase.METADATA_PENDING) "" else "upload-$id",
            notice = TransferNotice.NONE,
            createdAt = 0L,
            transferredBytes = if (phase == UploadPhase.METADATA_PENDING) 1_000L else 500L,
        )
    }
}
