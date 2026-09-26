package com.notes.notes.data

import com.notes.notes.core.DownloadPhase
import com.notes.notes.core.DownloadRingMode
import com.notes.notes.core.DownloadRingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ring as the ViewModel actually drives it.
 *
 * Every test replays a real sequence — write a phase, refresh the projection, hand a WorkManager
 * snapshot in, observe a terminal outcome — through the production projection rule
 * ([downloadTaskProgress]) and the production publish policy ([DownloadRoundProgress]), and then
 * asserts what the user sees over time: which ring, and above all which ring was published *first*.
 */
class DownloadRingTimelineTest {

    /** Mirrors one `refreshTransferUi()` pass: project, reduce, and publish when the ring asks for it. */
    private class DownloadUi {

        val round = DownloadRoundProgress()

        private var clock = 0L

        private val passes = mutableListOf<Pass>()

        data class Pass(
            val atMillis: Long,
            val structural: Boolean,
            val publishNow: Boolean,
            val ring: DownloadRingState,
            val phases: List<DownloadPhase>,
        )

        /** The ring the user currently sees: the last state that was really published. */
        val visibleRing: DownloadRingState
            get() = passes.lastOrNull { it.publishNow }?.ring ?: DownloadRingState.Hidden

        /** Modes of every ring that was published, in order. */
        val publishedModes: List<DownloadRingMode>
            get() = passes.filter { it.publishNow }.map { it.ring.mode }

        fun advance(millis: Long) {
            clock += millis
        }

        fun refresh(tasks: List<DownloadTaskProgress>): Pass {
            val update = round.reduce(tasks, clock)
            return Pass(
                atMillis = clock,
                structural = update.structural,
                publishNow = update.publishNow,
                ring = update.ring,
                phases = tasks.map(DownloadTaskProgress::phase),
            ).also(passes::add)
        }
    }

    @Test
    fun `pausing the only download switches straight from green to the yellow ring`() {
        val ui = DownloadUi()

        ui.refresh(listOf(downloadTask("A", downloaded = 40_000L, total = 100_000L)))
        assertEquals(DownloadRingMode.DETERMINATE, ui.visibleRing.mode)

        /*
         * pauseDownload writes PAUSED into the store before it waits for the worker cancellation, so the
         * row already says PAUSED while the old work item is still RUNNING. The ring must not keep
         * claiming progress in that window.
         */
        val afterPause = ui.refresh(
            listOf(
                downloadTask(
                    "A",
                    downloaded = 40_000L,
                    total = 100_000L,
                    phase = DownloadPhase.PAUSED,
                    hasLiveWork = true,
                )
            )
        )

        assertEquals(listOf(DownloadPhase.PAUSED), afterPause.phases)
        assertEquals(DownloadRingMode.PAUSED, afterPause.ring.mode)
        assertTrue(afterPause.structural)
        assertTrue(afterPause.publishNow)
        // No green ring is ever published after the row became PAUSED.
        assertEquals(
            listOf(DownloadRingMode.DETERMINATE, DownloadRingMode.PAUSED),
            ui.publishedModes,
        )
    }

    @Test
    fun `pause all goes straight to the yellow ring as well`() {
        val ui = DownloadUi()

        ui.refresh(
            listOf(
                downloadTask("A", downloaded = 40_000L, total = 100_000L),
                downloadTask("B", downloaded = 20_000L, total = 100_000L),
            )
        )
        assertEquals(DownloadRingMode.DETERMINATE, ui.visibleRing.mode)

        val afterPauseAll = ui.refresh(
            listOf(
                downloadTask(
                    "A",
                    downloaded = 40_000L,
                    total = 100_000L,
                    phase = DownloadPhase.PAUSED,
                    hasLiveWork = true,
                ),
                downloadTask(
                    "B",
                    downloaded = 20_000L,
                    total = 100_000L,
                    phase = DownloadPhase.PAUSED,
                    hasLiveWork = true,
                ),
            )
        )

        assertEquals(DownloadRingMode.PAUSED, afterPauseAll.ring.mode)
        assertEquals(1f, afterPauseAll.ring.progress, 0.0001f)
        assertTrue(afterPauseAll.publishNow)
        assertEquals(
            listOf(DownloadRingMode.DETERMINATE, DownloadRingMode.PAUSED),
            ui.publishedModes,
        )
    }

    @Test
    fun `resuming a paused 40 percent download goes from yellow to green without hiding`() {
        val ui = DownloadUi()

        ui.refresh(
            listOf(
                downloadTask(
                    "A",
                    downloaded = 40_000L,
                    total = 100_000L,
                    phase = DownloadPhase.PAUSED,
                    hasLiveWork = false,
                )
            )
        )
        assertEquals(DownloadRingMode.PAUSED, ui.visibleRing.mode)

        /*
         * resumeDownload establishes the enqueue grace before the phase can be seen as TRANSFERRING, so
         * the very first projection already treats the transfer as the active member of a new round even
         * though WorkManager has not reported an item yet.
         */
        val afterResume = ui.refresh(
            listOf(
                downloadTask(
                    "A",
                    downloaded = 40_000L,
                    total = 100_000L,
                    phase = DownloadPhase.TRANSFERRING,
                    hasLiveWork = false,
                    withinEnqueueGrace = true,
                )
            )
        )

        assertEquals(DownloadRingMode.DETERMINATE, afterResume.ring.mode)
        assertEquals(0.4f, afterResume.ring.progress, 0.0001f)
        assertTrue(afterResume.publishNow)
        assertEquals(
            listOf(DownloadRingMode.PAUSED, DownloadRingMode.DETERMINATE),
            ui.publishedModes,
        )
    }

    @Test
    fun `resume all never passes through the hidden ring`() {
        val ui = DownloadUi()

        ui.refresh(
            listOf(
                downloadTask(
                    "A",
                    downloaded = 40_000L,
                    total = 100_000L,
                    phase = DownloadPhase.PAUSED,
                    hasLiveWork = false,
                ),
                downloadTask(
                    "B",
                    downloaded = 10_000L,
                    total = 100_000L,
                    phase = DownloadPhase.PAUSED,
                    hasLiveWork = false,
                ),
            )
        )
        assertEquals(DownloadRingMode.PAUSED, ui.visibleRing.mode)

        val afterResumeAll = ui.refresh(
            listOf(
                downloadTask(
                    "A",
                    downloaded = 40_000L,
                    total = 100_000L,
                    phase = DownloadPhase.TRANSFERRING,
                    hasLiveWork = false,
                    withinEnqueueGrace = true,
                ),
                downloadTask(
                    "B",
                    downloaded = 10_000L,
                    total = 100_000L,
                    phase = DownloadPhase.TRANSFERRING,
                    hasLiveWork = false,
                    withinEnqueueGrace = true,
                ),
            )
        )

        assertEquals(DownloadRingMode.DETERMINATE, afterResumeAll.ring.mode)
        assertEquals(0.25f, afterResumeAll.ring.progress, 0.0001f)
        assertFalse(ui.publishedModes.contains(DownloadRingMode.HIDDEN))
    }

    @Test
    fun `a transfer resumed without enqueue grace would not be active yet`() {
        /*
         * This is why the grace has to exist before the phase flips: in the pass where the phase is
         * already TRANSFERRING but WorkManager has not produced an item yet, the transfer is neither
         * active nor paused, and the ring would blink out instead of turning green.
         */
        val tooEarly = downloadTask(
            "A",
            downloaded = 40_000L,
            total = 100_000L,
            phase = DownloadPhase.TRANSFERRING,
            hasLiveWork = false,
            withinEnqueueGrace = false,
        )
        assertFalse(tooEarly.active)

        val ui = DownloadUi()
        ui.refresh(
            listOf(
                downloadTask(
                    "A",
                    downloaded = 40_000L,
                    total = 100_000L,
                    phase = DownloadPhase.PAUSED,
                    hasLiveWork = false,
                )
            )
        )
        val hidden = ui.refresh(listOf(tooEarly))
        assertEquals(DownloadRingMode.HIDDEN, hidden.ring.mode)
    }

    @Test
    fun `a refused resume rolls back to the yellow ring`() {
        val ui = DownloadUi()

        ui.refresh(
            listOf(
                downloadTask(
                    "A",
                    downloaded = 40_000L,
                    total = 100_000L,
                    phase = DownloadPhase.PAUSED,
                    hasLiveWork = false,
                )
            )
        )
        ui.refresh(
            listOf(
                downloadTask(
                    "A",
                    downloaded = 40_000L,
                    total = 100_000L,
                    phase = DownloadPhase.TRANSFERRING,
                    hasLiveWork = false,
                    withinEnqueueGrace = true,
                )
            )
        )
        assertEquals(DownloadRingMode.DETERMINATE, ui.visibleRing.mode)

        // WorkManager refused the work: the ViewModel restores the previous phase and the grace.
        val rolledBack = ui.refresh(
            listOf(
                downloadTask(
                    "A",
                    downloaded = 40_000L,
                    total = 100_000L,
                    phase = DownloadPhase.PAUSED,
                    hasLiveWork = false,
                )
            )
        )

        assertEquals(DownloadRingMode.PAUSED, rolledBack.ring.mode)
        assertEquals(1f, rolledBack.ring.progress, 0.0001f)
        assertTrue(rolledBack.publishNow)
        assertEquals(
            listOf(
                DownloadRingMode.PAUSED,
                DownloadRingMode.DETERMINATE,
                DownloadRingMode.PAUSED,
            ),
            ui.publishedModes,
        )
    }

    @Test
    fun `a paused download whose old work item is still running is not active`() {
        val pausedWithLiveWork = downloadTask(
            "A",
            downloaded = 40_000L,
            total = 100_000L,
            phase = DownloadPhase.PAUSED,
            hasLiveWork = true,
        )

        assertFalse(pausedWithLiveWork.active)
    }

    @Test
    fun `a stale cancelled transfer does not join the new round of another download`() {
        val ui = DownloadUi()

        // A was paused before this round; its cancellation has not left WorkManager yet.
        val update = ui.refresh(
            listOf(
                downloadTask(
                    "A",
                    downloaded = 40_000L,
                    total = 100_000L,
                    phase = DownloadPhase.PAUSED,
                    hasLiveWork = true,
                ),
                downloadTask("B", downloaded = 0L, total = 100_000L),
            )
        )

        assertEquals(listOf("B"), ui.round.memberIds)
        assertEquals(0f, update.ring.progress, 0.0001f)
        assertEquals(100_000L, ui.round.totalBytes)
    }

    @Test
    fun `a completion publishes immediately and keeps the denominator intact`() {
        val ui = DownloadUi()
        ui.refresh(
            listOf(
                downloadTask("A", downloaded = 10_000L, total = 100_000L),
                downloadTask("B", downloaded = 10_000L, total = 100_000L),
            )
        )
        ui.advance(1L)

        // B finished; its record is gone from the store, but its bytes stay in the round.
        ui.round.onTaskCompleted("B")
        val pass = ui.refresh(listOf(downloadTask("A", downloaded = 20_000L, total = 100_000L)))

        assertTrue(pass.structural)
        assertTrue(pass.publishNow)
        assertEquals(120_000L, ui.round.loadedBytes)
        assertEquals(200_000L, ui.round.totalBytes)
    }

    @Test
    fun `a failure publishes immediately and never becomes the yellow ring`() {
        val ui = DownloadUi()
        ui.refresh(listOf(downloadTask("A", downloaded = 40_000L, total = 100_000L)))
        ui.advance(1L)

        ui.round.onTaskFailed()
        val pass = ui.refresh(
            listOf(
                downloadTask(
                    "A",
                    downloaded = 40_000L,
                    total = 100_000L,
                    phase = DownloadPhase.FAILED,
                    hasLiveWork = false,
                )
            )
        )

        assertTrue(pass.structural)
        assertTrue(pass.publishNow)
        assertEquals(DownloadRingMode.HIDDEN, pass.ring.mode)
    }

    @Test
    fun `a cancel publishes immediately`() {
        val ui = DownloadUi()
        ui.refresh(
            listOf(
                downloadTask("A", downloaded = 40_000L, total = 100_000L),
                downloadTask("B", downloaded = 20_000L, total = 100_000L),
            )
        )
        ui.advance(1L)

        ui.round.onTaskCancelled("A")
        val pass = ui.refresh(listOf(downloadTask("B", downloaded = 20_000L, total = 100_000L)))

        assertTrue(pass.structural)
        assertTrue(pass.publishNow)
        assertEquals(listOf("B"), ui.round.memberIds)
    }

    @Test
    fun `a failed transfer is still failed after a restart and shows no yellow ring`() {
        // A fresh owner models the restart: nothing is in memory, the phase comes from the store.
        val afterRestart = DownloadUi().refresh(
            listOf(
                downloadTask(
                    "A",
                    downloaded = 40_000L,
                    total = 100_000L,
                    phase = DownloadPhase.FAILED,
                    hasLiveWork = false,
                )
            )
        )

        assertEquals(DownloadRingMode.HIDDEN, afterRestart.ring.mode)
    }

    @Test
    fun `a failed transfer retried by the user starts a new round at its real progress`() {
        val ui = DownloadUi()
        ui.refresh(
            listOf(
                downloadTask(
                    "A",
                    downloaded = 40_000L,
                    total = 100_000L,
                    phase = DownloadPhase.FAILED,
                    hasLiveWork = false,
                )
            )
        )
        assertEquals(DownloadRingMode.HIDDEN, ui.visibleRing.mode)

        val retried = ui.refresh(
            listOf(
                downloadTask(
                    "A",
                    downloaded = 40_000L,
                    total = 100_000L,
                    phase = DownloadPhase.TRANSFERRING,
                    hasLiveWork = false,
                    withinEnqueueGrace = true,
                )
            )
        )

        assertEquals(DownloadRingMode.DETERMINATE, retried.ring.mode)
        assertEquals(0.4f, retried.ring.progress, 0.0001f)
        assertEquals(listOf("A"), ui.round.memberIds)
    }

    @Test
    fun `byte ticks are coalesced while the ring is running`() {
        val ui = DownloadUi()
        ui.refresh(listOf(downloadTask("A", downloaded = 10_000L, total = 100_000L)))

        ui.advance(10L)
        val tick = ui.refresh(listOf(downloadTask("A", downloaded = 20_000L, total = 100_000L)))
        assertFalse(tick.structural)
        assertFalse(tick.publishNow)

        ui.advance(70L)
        val due = ui.refresh(listOf(downloadTask("A", downloaded = 30_000L, total = 100_000L)))
        assertFalse(due.structural)
        assertTrue(due.publishNow)
    }
}
