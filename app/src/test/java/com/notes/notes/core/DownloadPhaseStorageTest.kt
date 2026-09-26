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
    fun `stored values of every phase decode as themselves`() {
        assertEquals(DownloadPhase.FAILED, DownloadPhase.fromStorage("failed"))
        assertEquals(DownloadPhase.PAUSED, DownloadPhase.fromStorage("paused"))
        assertEquals(DownloadPhase.CANCELING, DownloadPhase.fromStorage("canceling"))
        assertEquals(DownloadPhase.TRANSFERRING, DownloadPhase.fromStorage("transferring"))
    }

    @Test
    fun `missing and unknown values keep decoding as transferring`() {
        assertEquals(DownloadPhase.TRANSFERRING, DownloadPhase.fromStorage(null))
        assertEquals(DownloadPhase.TRANSFERRING, DownloadPhase.fromStorage(""))
        assertEquals(DownloadPhase.TRANSFERRING, DownloadPhase.fromStorage("something-new"))
    }

    @Test
    fun `only transferring keeps running and only a pause or a failure is resumable`() {
        assertTrue(DownloadPhase.TRANSFERRING.isTransferring)
        assertFalse(DownloadPhase.PAUSED.isTransferring)
        assertFalse(DownloadPhase.FAILED.isTransferring)
        assertFalse(DownloadPhase.CANCELING.isTransferring)

        assertFalse(DownloadPhase.TRANSFERRING.isResumable)
        assertTrue(DownloadPhase.PAUSED.isResumable)
        assertTrue(DownloadPhase.FAILED.isResumable)
        // A cancel in flight cannot be resumed: the user is deleting that download.
        assertFalse(DownloadPhase.CANCELING.isResumable)
    }
}
