package com.notes.notes.data

import com.notes.notes.core.DownloadPhase
import com.notes.notes.core.DownloadRingState

/**
 * One download task as the round accounting sees it at one instant.
 *
 * The bytes are the freshest values available for the transfer, [phase] is the business state the user
 * sees, and [active] tells whether more bytes are still expected from it.
 */
data class DownloadTaskProgress(
    val transferId: String,
    val phase: DownloadPhase,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val active: Boolean,
)

/**
 * Builds the round-accounting view of one transfer.
 *
 * A task counts as active only while the business state is still [DownloadPhase.TRANSFERRING] and
 * something is really going to move bytes forward: unfinished work in WorkManager, or an enqueue that
 * was requested just now.
 */
fun downloadTaskProgress(
    transferId: String,
    phase: DownloadPhase,
    totalBytes: Long,
    downloadedBytes: Long,
    hasLiveWork: Boolean,
    withinEnqueueGrace: Boolean,
): DownloadTaskProgress = DownloadTaskProgress(
    transferId = transferId,
    phase = phase,
    totalBytes = totalBytes,
    downloadedBytes = downloadedBytes,
    active = phase.isTransferring && (hasLiveWork || withinEnqueueGrace),
)

/** Outcome of one accounting pass. */
data class DownloadRoundUpdate(
    val ring: DownloadRingState,

    /**
     * True when the round changed structurally rather than through a plain byte tick.
     *
     * Structural changes must be published immediately by the ViewModel. Pure byte updates may be
     * coalesced there.
     */
    val structural: Boolean,
)

/**
 * Byte accounting of the current download round.
 *
 * A round starts when the first active download appears and is destroyed in one step when the last
 * active download disappears. Once a file joins the round it stays there until the round ends, even
 * if it pauses or completes, so the denominator never shrinks because of a normal pause/completion.
 *
 * This class owns only round accounting. UI publish timing belongs to the ViewModel.
 */
class DownloadRoundProgress {

    private class Member(
        var totalBytes: Long,
        var downloadedBytes: Long,
        var totalKnown: Boolean,
    ) {
        var completed: Boolean = false

        fun clamp(bytes: Long): Long {
            val value = bytes.coerceAtLeast(0L)
            return if (totalKnown && totalBytes > 0L) {
                value.coerceAtMost(totalBytes)
            } else {
                value
            }
        }
    }

    private val members = LinkedHashMap<String, Member>()

    private var ring = DownloadRingState.Hidden
    private var roundLoadedBytes = 0L
    private var roundTotalBytes = 0L

    /**
     * Incremented whenever the structure of the round changes:
     * membership, known/unknown size state, or the round itself.
     */
    private var roundRevision = 0

    /**
     * Terminal events may require an immediate publish even when they do not otherwise change the
     * visible ring during this pass.
     */
    private var pendingStructuralChange = false

    val memberIds: List<String>
        get() = members.keys.toList()

    val loadedBytes: Long
        get() = roundLoadedBytes

    val totalBytes: Long
        get() = roundTotalBytes

    fun onTaskCompleted(transferId: String) {
        pendingStructuralChange = true

        val member = members[transferId]
            ?: return

        member.completed = true

        if (member.totalKnown) {
            member.downloadedBytes =
                member.totalBytes
        }
    }

    fun onTaskFailed() {
        pendingStructuralChange = true
    }

    fun onTaskCancelled(transferId: String) {
        pendingStructuralChange = true
        members.remove(transferId)
    }

    fun reset() {
        members.clear()
        roundLoadedBytes = 0L
        roundTotalBytes = 0L
        ring = DownloadRingState.Hidden
        pendingStructuralChange = false
    }

    /**
     * Applies one consistent transfer snapshot.
     *
     * All member states, byte counters and the final ring are calculated before the result leaves this
     * method. The ViewModel can therefore publish the returned state atomically.
     */
    fun reduce(
        tasks: List<DownloadTaskProgress>,
    ): DownloadRoundUpdate {
        val revisionBefore =
            roundRevision

        val modeBefore =
            ring.mode

        val eventBefore =
            pendingStructuralChange

        pendingStructuralChange =
            false

        val activeTasks =
            tasks.filter {
                it.active
            }

        if (activeTasks.isEmpty()) {
            endRound()

            val paused =
                tasks.any {
                    it.phase ==
                            DownloadPhase.PAUSED
                }

            ring =
                if (paused) {
                    DownloadRingState.Paused
                } else {
                    DownloadRingState.Hidden
                }

            return DownloadRoundUpdate(
                ring = ring,
                structural =
                    eventBefore ||
                            ring.mode != modeBefore ||
                            roundRevision != revisionBefore,
            )
        }

        activeTasks.forEach { task ->
            if (
                members.containsKey(
                    task.transferId
                )
            ) {
                return@forEach
            }

            members[task.transferId] =
                Member(
                    totalBytes =
                        task.totalBytes.coerceAtLeast(
                            0L
                        ),
                    downloadedBytes =
                        0L,
                    totalKnown =
                        task.totalBytes > 0L,
                )

            roundRevision++
        }

        tasks.forEach { task ->
            val member =
                members[task.transferId]
                    ?: return@forEach

            if (member.completed) {
                return@forEach
            }

            if (task.totalBytes > 0L) {
                if (
                    !member.totalKnown ||
                    member.totalBytes !=
                    task.totalBytes
                ) {
                    member.totalBytes =
                        task.totalBytes

                    member.totalKnown =
                        true

                    roundRevision++
                }
            } else if (member.totalKnown) {
                member.totalBytes =
                    0L

                member.totalKnown =
                    false

                roundRevision++
            }

            if (task.active) {
                member.downloadedBytes =
                    member.clamp(
                        task.downloadedBytes
                    )
            } else {
                member.downloadedBytes =
                    maxOf(
                        member.downloadedBytes,
                        member.clamp(
                            task.downloadedBytes
                        ),
                    )
            }
        }

        recomputeTotals()

        ring =
            if (
                members.values.any {
                    !it.totalKnown
                } ||
                roundTotalBytes <= 0L
            ) {
                DownloadRingState.Indeterminate
            } else {
                DownloadRingState.determinate(
                    roundLoadedBytes
                        .toDouble()
                        .div(
                            roundTotalBytes.toDouble()
                        )
                        .toFloat()
                )
            }

        return DownloadRoundUpdate(
            ring = ring,
            structural =
                eventBefore ||
                        ring.mode != modeBefore ||
                        roundRevision != revisionBefore,
        )
    }

    private fun endRound() {
        if (members.isNotEmpty()) {
            members.clear()
            roundRevision++
        }

        roundLoadedBytes = 0L
        roundTotalBytes = 0L
    }

    private fun recomputeTotals() {
        var loaded = 0L
        var total = 0L

        members.values.forEach { member ->
            loaded +=
                member.downloadedBytes

            total +=
                member.totalBytes
        }

        roundLoadedBytes = loaded
        roundTotalBytes = total
    }
}