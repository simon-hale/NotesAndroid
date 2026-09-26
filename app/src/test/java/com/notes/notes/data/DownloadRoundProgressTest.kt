package com.notes.notes.data

import com.notes.notes.core.DownloadPhase
import com.notes.notes.core.DownloadRingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The byte accounting of one download round.
 *
 * The invariants covered here are the ones a user can actually see: a file joins a round at most once,
 * pausing or finishing one file never shrinks the denominator while others still run, the round is
 * destroyed in one step when the last active download leaves, and a yellow ring only ever means
 * "everything is paused".
 */
class DownloadRoundProgressTest {

    private val now = 1_000L

    @Test
    fun `a resumed file starts its round at its real byte progress`() {
        val round = DownloadRoundProgress()

        val update = round.reduce(listOf(downloadTask("A", downloaded = 40_000L, total = 100_000L)), now)

        assertEquals(DownloadRingMode.DETERMINATE, update.ring.mode)
        assertEquals(0.4f, update.ring.progress, 0.0001f)
        assertEquals(listOf("A"), round.memberIds)
        assertEquals(40_000L, round.loadedBytes)
        assertEquals(100_000L, round.totalBytes)
        assertTrue(update.structural)
        assertTrue(update.publishNow)
    }

    @Test
    fun `a new member adds its full size and its existing bytes at once`() {
        val round = DownloadRoundProgress()
        round.reduce(listOf(downloadTask("A", downloaded = 40_000L, total = 100_000L)), now)

        val update = round.reduce(
            listOf(
                downloadTask("A", downloaded = 40_000L, total = 100_000L),
                downloadTask("B", downloaded = 0L, total = 100_000L),
            ),
            now,
        )

        // The percentage is allowed to fall: the denominator grew by a whole file.
        assertEquals(0.2f, update.ring.progress, 0.0001f)
        assertEquals(200_000L, round.totalBytes)
    }

    @Test
    fun `pausing a member freezes its bytes without shrinking the denominator`() {
        val round = DownloadRoundProgress()
        round.reduce(
            listOf(
                downloadTask("A", downloaded = 40_000L, total = 100_000L),
                downloadTask("B", downloaded = 20_000L, total = 100_000L),
            ),
            now,
        )

        val update = round.reduce(
            listOf(
                downloadTask(
                    "A",
                    downloaded = 40_000L,
                    total = 100_000L,
                    phase = DownloadPhase.PAUSED,
                    hasLiveWork = false,
                ),
                downloadTask("B", downloaded = 30_000L, total = 100_000L),
            ),
            now,
        )

        assertEquals(0.35f, update.ring.progress, 0.0001f)
        assertEquals(200_000L, round.totalBytes)
        assertEquals(listOf("A", "B"), round.memberIds)
    }

    @Test
    fun `resuming inside the same round never counts bytes twice`() {
        val round = DownloadRoundProgress()
        round.reduce(
            listOf(
                downloadTask("A", downloaded = 40_000L, total = 100_000L),
                downloadTask("B", downloaded = 20_000L, total = 100_000L),
            ),
            now,
        )
        round.reduce(
            listOf(
                downloadTask(
                    "A",
                    downloaded = 40_000L,
                    total = 100_000L,
                    phase = DownloadPhase.PAUSED,
                    hasLiveWork = false,
                ),
                downloadTask("B", downloaded = 30_000L, total = 100_000L),
            ),
            now,
        )

        val update = round.reduce(
            listOf(
                downloadTask("A", downloaded = 45_000L, total = 100_000L),
                downloadTask("B", downloaded = 35_000L, total = 100_000L),
            ),
            now,
        )

        assertEquals(80_000L, round.loadedBytes)
        assertEquals(0.4f, update.ring.progress, 0.0001f)
    }

    @Test
    fun `the example round keeps its denominator until the last active member is gone`() {
        val round = DownloadRoundProgress()
        round.reduce(
            listOf(
                downloadTask("A", downloaded = 0L, total = 100_000L),
                downloadTask("B", downloaded = 0L, total = 100_000L),
                downloadTask("C", downloaded = 0L, total = 100_000L),
            ),
            now,
        )

        // A is paused after 40 %; B and C keep downloading and stay in the round.
        val withPause = round.reduce(
            listOf(
                downloadTask(
                    "A",
                    downloaded = 40_000L,
                    total = 100_000L,
                    phase = DownloadPhase.PAUSED,
                    hasLiveWork = false,
                ),
                downloadTask("B", downloaded = 90_000L, total = 100_000L),
                downloadTask("C", downloaded = 90_000L, total = 100_000L),
            ),
            now,
        )
        assertEquals(220_000L, round.loadedBytes)
        assertEquals(300_000L, round.totalBytes)
        assertEquals(0.7333f, withPause.ring.progress, 0.0001f)

        // B completes. Its record is gone from the task list, but it keeps 100/100 in the round.
        round.onTaskCompleted("B")
        val afterB = round.reduce(
            listOf(
                downloadTask(
                    "A",
                    downloaded = 40_000L,
                    total = 100_000L,
                    phase = DownloadPhase.PAUSED,
                    hasLiveWork = false,
                ),
                downloadTask("C", downloaded = 100_000L, total = 100_000L),
            ),
            now,
        )
        assertEquals(240_000L, round.loadedBytes)
        assertEquals(300_000L, round.totalBytes)
        assertEquals(0.8f, afterB.ring.progress, 0.0001f)
        // A completion is a state change, not a byte tick: it must never wait for the publish window.
        assertTrue(afterB.structural)
        assertTrue(afterB.publishNow)

        // C completes too: the round ends in one step and the still paused A turns the ring yellow.
        round.onTaskCompleted("C")
        val ended = round.reduce(
            listOf(
                downloadTask(
                    "A",
                    downloaded = 40_000L,
                    total = 100_000L,
                    phase = DownloadPhase.PAUSED,
                    hasLiveWork = false,
                )
            ),
            now,
        )
        assertEquals(DownloadRingMode.PAUSED, ended.ring.mode)
        assertEquals(1f, ended.ring.progress, 0.0001f)
        assertTrue(ended.structural)
        assertTrue(round.memberIds.isEmpty())
        assertEquals(0L, round.loadedBytes)
        assertEquals(0L, round.totalBytes)

        // A is resumed: a brand new round starts from its real 40 %.
        val resumed = round.reduce(listOf(downloadTask("A", downloaded = 40_000L, total = 100_000L)), now)
        assertEquals(DownloadRingMode.DETERMINATE, resumed.ring.mode)
        assertEquals(0.4f, resumed.ring.progress, 0.0001f)
        assertEquals(listOf("A"), round.memberIds)
        assertEquals(100_000L, round.totalBytes)
    }

    @Test
    fun `the round disappears once nothing is active or paused`() {
        val round = DownloadRoundProgress()
        round.reduce(listOf(downloadTask("A", downloaded = 50_000L, total = 100_000L)), now)

        round.onTaskCompleted("A")
        val update = round.reduce(emptyList(), now)

        assertEquals(DownloadRingMode.HIDDEN, update.ring.mode)
        assertTrue(round.memberIds.isEmpty())
        assertTrue(update.structural)
        assertTrue(update.publishNow)
    }

    @Test
    fun `an unknown size keeps the ring indeterminate and switches over once it is known`() {
        val round = DownloadRoundProgress()

        val unknown = round.reduce(listOf(downloadTask("A", downloaded = 1_024L, total = 0L)), now)
        assertEquals(DownloadRingMode.INDETERMINATE, unknown.ring.mode)
        assertEquals(listOf("A"), round.memberIds)

        val known = round.reduce(listOf(downloadTask("A", downloaded = 1_024L, total = 4_096L)), now)
        assertEquals(DownloadRingMode.DETERMINATE, known.ring.mode)
        assertEquals(0.25f, known.ring.progress, 0.0001f)
        assertTrue(known.structural)
        assertTrue(known.publishNow)
    }

    @Test
    fun `a restart that discards the size makes the ring indeterminate again`() {
        val round = DownloadRoundProgress()
        round.reduce(listOf(downloadTask("A", downloaded = 40_000L, total = 100_000L)), now)

        // The object is fetched from byte zero again: the length of the old representation is gone.
        val restarted = round.reduce(listOf(downloadTask("A", downloaded = 0L, total = 0L)), now)
        assertEquals(DownloadRingMode.INDETERMINATE, restarted.ring.mode)
        assertEquals(listOf("A"), round.memberIds)
        assertEquals(0L, round.totalBytes)

        val refetched = round.reduce(listOf(downloadTask("A", downloaded = 100L, total = 7_777L)), now)
        assertEquals(DownloadRingMode.DETERMINATE, refetched.ring.mode)
        assertEquals(100f / 7_777f, refetched.ring.progress, 0.0001f)
        assertEquals(7_777L, round.totalBytes)
    }

    @Test
    fun `queued members are active and keep the round alive`() {
        val round = DownloadRoundProgress()
        round.reduce(listOf(downloadTask("A", downloaded = 80_000L, total = 100_000L)), now)

        // A is waiting for the network while B has not received a single byte yet.
        val update = round.reduce(
            listOf(
                downloadTask("A", downloaded = 80_000L, total = 100_000L),
                downloadTask("B", downloaded = 0L, total = 200_000L),
            ),
            now,
        )

        assertEquals(DownloadRingMode.DETERMINATE, update.ring.mode)
        assertEquals(80_000L, round.loadedBytes)
        assertEquals(300_000L, round.totalBytes)
    }

    @Test
    fun `a failure is not a pause and does not make the ring yellow`() {
        val round = DownloadRoundProgress()
        round.reduce(listOf(downloadTask("A", downloaded = 40_000L, total = 100_000L)), now)

        round.onTaskFailed()
        val failed = round.reduce(
            listOf(
                downloadTask(
                    "A",
                    downloaded = 40_000L,
                    total = 100_000L,
                    phase = DownloadPhase.FAILED,
                    hasLiveWork = false,
                )
            ),
            now,
        )
        assertEquals(DownloadRingMode.HIDDEN, failed.ring.mode)
        assertTrue(failed.structural)
        assertTrue(failed.publishNow)

        // Retrying it is a new start event: it rejoins with its own bytes.
        val retried = round.reduce(listOf(downloadTask("A", downloaded = 40_000L, total = 100_000L)), now)
        assertEquals(DownloadRingMode.DETERMINATE, retried.ring.mode)
        assertEquals(0.4f, retried.ring.progress, 0.0001f)

        // Pausing it afterwards is a user pause again.
        val paused = round.reduce(
            listOf(
                downloadTask(
                    "A",
                    downloaded = 40_000L,
                    total = 100_000L,
                    phase = DownloadPhase.PAUSED,
                    hasLiveWork = false,
                )
            ),
            now,
        )
        assertEquals(DownloadRingMode.PAUSED, paused.ring.mode)
    }

    @Test
    fun `a failed transfer is neither paused nor a round member`() {
        val round = DownloadRoundProgress()

        val update = round.reduce(
            listOf(
                downloadTask(
                    "A",
                    downloaded = 90_000L,
                    total = 100_000L,
                    phase = DownloadPhase.FAILED,
                    hasLiveWork = false,
                ),
                downloadTask("B", downloaded = 0L, total = 50_000L),
            ),
            now,
        )

        assertEquals(listOf("B"), round.memberIds)
        assertEquals(0f, update.ring.progress, 0.0001f)
        assertEquals(50_000L, round.totalBytes)
    }

    @Test
    fun `a cancelled member leaves the round immediately`() {
        val round = DownloadRoundProgress()
        round.reduce(
            listOf(
                downloadTask("A", downloaded = 40_000L, total = 100_000L),
                downloadTask("B", downloaded = 20_000L, total = 100_000L),
            ),
            now,
        )

        round.onTaskCancelled("A")
        val update = round.reduce(listOf(downloadTask("B", downloaded = 20_000L, total = 100_000L)), now)

        assertEquals(listOf("B"), round.memberIds)
        assertEquals(0.2f, update.ring.progress, 0.0001f)
        assertTrue(update.structural)
        assertTrue(update.publishNow)
    }

    @Test
    fun `byte values never leave the zero to total range`() {
        val round = DownloadRoundProgress()

        val clamped = round.reduce(listOf(downloadTask("A", downloaded = 500_000L, total = 100_000L)), now)
        assertEquals(1f, clamped.ring.progress, 0.0001f)
        assertEquals(100_000L, round.loadedBytes)

        val negative = DownloadRoundProgress()
            .reduce(listOf(downloadTask("B", downloaded = -5L, total = 100_000L)), now)
        assertEquals(0f, negative.ring.progress, 0.0001f)
    }

    @Test
    fun `plain byte ticks are coalesced while structural changes publish at once`() {
        val round = DownloadRoundProgress()
        round.reduce(listOf(downloadTask("A", downloaded = 10_000L, total = 100_000L)), now)

        val tick = round.reduce(listOf(downloadTask("A", downloaded = 20_000L, total = 100_000L)), now + 10L)
        assertFalse(tick.structural)
        assertFalse(tick.publishNow)

        val dueTick = round.reduce(listOf(downloadTask("A", downloaded = 30_000L, total = 100_000L)), now + 80L)
        assertFalse(dueTick.structural)
        assertTrue(dueTick.publishNow)

        val joined = round.reduce(
            listOf(
                downloadTask("A", downloaded = 30_000L, total = 100_000L),
                downloadTask("B", downloaded = 0L, total = 100_000L),
            ),
            now + 81L,
        )
        assertTrue(joined.structural)
        assertTrue(joined.publishNow)
    }

    @Test
    fun `reset drops every round`() {
        val round = DownloadRoundProgress()
        round.reduce(listOf(downloadTask("A", downloaded = 40_000L, total = 100_000L)), now)

        round.reset()

        assertTrue(round.memberIds.isEmpty())
        assertEquals(0L, round.loadedBytes)
        assertEquals(0L, round.totalBytes)
        assertEquals(
            DownloadRingMode.HIDDEN,
            round.reduce(emptyList(), now).ring.mode,
        )
    }
}
