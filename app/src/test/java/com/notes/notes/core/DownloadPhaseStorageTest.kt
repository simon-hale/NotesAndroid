package com.notes.notes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persisted download phases.
 *
 * `FAILED` is part of the stored state because a failure must survive a restart: a record that was
 * written as failed may never be decoded as a user pause, or the ring would claim "everything is
 * paused" for a download that actually errored. Records written by older versions only contain
 * `transferring` or `paused`, so every unknown value still has to decode safely.
 */
class DownloadPhaseStorageTest {

    @Test
    fun `every phase survives a storage round trip`() {
        DownloadPhase.entries.forEach { phase ->
            assertEquals(phase, DownloadPhase.fromStorage(phase.storageValue))
        }
    }

    @Test
    fun `stored values of the failing phase decode as failed`() {
        assertEquals(DownloadPhase.FAILED, DownloadPhase.fromStorage("failed"))
        assertEquals(DownloadPhase.PAUSED, DownloadPhase.fromStorage("paused"))
        assertEquals(DownloadPhase.TRANSFERRING, DownloadPhase.fromStorage("transferring"))
    }

    @Test
    fun `missing and unknown values keep decoding as transferring`() {
        assertEquals(DownloadPhase.TRANSFERRING, DownloadPhase.fromStorage(null))
        assertEquals(DownloadPhase.TRANSFERRING, DownloadPhase.fromStorage(""))
        assertEquals(DownloadPhase.TRANSFERRING, DownloadPhase.fromStorage("something-new"))
    }

    @Test
    fun `only transferring keeps running and every other phase is resumable`() {
        assertTrue(DownloadPhase.TRANSFERRING.isTransferring)
        assertFalse(DownloadPhase.PAUSED.isTransferring)
        assertFalse(DownloadPhase.FAILED.isTransferring)

        assertFalse(DownloadPhase.TRANSFERRING.isResumable)
        assertTrue(DownloadPhase.PAUSED.isResumable)
        assertTrue(DownloadPhase.FAILED.isResumable)
    }
}
