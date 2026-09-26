package com.notes.notes.data

import com.notes.notes.core.DownloadPhase
import com.notes.notes.core.DownloadRingMode
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
 * A task counts as active only while the business state is still [DownloadPhase.TRANSFERRING] *and*
 * something is really going to move bytes forward: unfinished work in WorkManager, or an enqueue that
 * was requested just now. The phase condition is what keeps the ring consistent with the list: a pause
 * is written to the store before the worker cancellation completes, and from that moment on the task
 * must not be counted as active any more, even while its old work item is still running.
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
     * True when the round itself changed: a member joined, completed or left, the round started or
     * ended, the ring switched between green, indeterminate, paused and hidden, or a terminal download
     * event arrived.
     */
    val structural: Boolean,
    /**
     * True when the caller has to publish this ring right away. Only a pure byte tick may be deferred,
     * and only until the next publish window.
     */
    val publishNow: Boolean,
)

/**
 * Byte accounting and publish policy of the current *download round*.
 *
 * A round is the set of downloads whose bytes the drawer ring shows at the same time. It starts when
 * the first active download appears and is destroyed in one step when the last active download is
 * gone — whether it completed, failed or was paused. Membership is a property of the round, not of the
 * moment: a file that was counted once stays in the round until the round ends, even if it is paused
 * in the meantime or finishes, so neither pausing nor completing one file can shrink the denominator
 * and make the ring jump.
 *
 * The object is a plain state holder with no coroutines of its own: [reduce] is a pure-ish function
 * over the tasks the owner passes in, and the owner (the ViewModel) calls every method from its single
 * main-dispatcher context. Members, [loadedBytes] and [totalBytes] can therefore never be updated
 * concurrently, and [reduce] either applies a whole state transition or none of it.
 */
class DownloadRoundProgress(
    private val publishIntervalMillis: Long = DEFAULT_PUBLISH_INTERVAL_MILLIS,
) {

    private class Member(
        var totalBytes: Long,
        var downloadedBytes: Long,
        var totalKnown: Boolean,
    ) {
        /** A finished member keeps its place in the round but never reports bytes again. */
        var completed: Boolean = false

        /** Keeps a byte count inside `0..totalBytes`; an unknown size only forbids a negative value. */
        fun clamp(bytes: Long): Long {
            val value = bytes.coerceAtLeast(0L)
            return if (totalKnown && totalBytes > 0L) value.coerceAtMost(totalBytes) else value
        }
    }

    /** Members of the current round in join order. A member leaves only when the round ends. */
    private val members = LinkedHashMap<String, Member>()

    private var ring = DownloadRingState.Hidden
    private var roundLoadedBytes = 0L
    private var roundTotalBytes = 0L

    /** Bumped by every change of the member set, so the owner can tell structural changes from ticks. */
    private var roundRevision = 0

    /**
     * Set by a terminal download event and consumed by the next [reduce]. A completion, failure or
     * cancel has to be published immediately even when it leaves the visible ring unchanged, because a
     * pure byte tick and a state change must never be treated alike.
     */
    private var pendingStructuralChange = false

    private var lastRingPublishAt = 0L

    /** Ids of the round currently shown, in join order. */
    val memberIds: List<String> get() = members.keys.toList()

    /** Bytes of this round's members that count as loaded so far. */
    val loadedBytes: Long get() = roundLoadedBytes

    /** Sum of this round's member sizes; `0` while every member's size is still unknown. */
    val totalBytes: Long get() = roundTotalBytes

    /**
     * A member finished. It is frozen at its full size and stays in the round, because the downloads
     * that are still running must keep counting it.
     */
    fun onTaskCompleted(transferId: String) {
        pendingStructuralChange = true
        val member = members[transferId] ?: return
        member.completed = true
        if (member.totalKnown) member.downloadedBytes = member.totalBytes
    }

    /**
     * A download attempt failed.
     *
     * The failure itself lives in the persisted phase, which keeps it out of the yellow "all paused"
     * ring and out of the next round; the round only has to publish the state it already has, because
     * the failed member keeps its last valid byte snapshot until the round ends.
     */
    fun onTaskFailed() {
        pendingStructuralChange = true
    }

    /**
     * A destructive cancel removed the transfer. It leaves the round immediately, because the local
     * partial file and the transfer record are gone as well. The percentage may therefore jump, which
     * is the accepted consequence of the existing cancel semantics.
     */
    fun onTaskCancelled(transferId: String) {
        pendingStructuralChange = true
        members.remove(transferId)
    }

    /** Drops every knowledge about rounds, for example when the signed-in account changes. */
    fun reset() {
        members.clear()
        roundLoadedBytes = 0L
        roundTotalBytes = 0L
        roundRevision++
        ring = DownloadRingState.Hidden
        pendingStructuralChange = false
        lastRingPublishAt = 0L
    }

    /**
     * Applies the current task set and returns the ring to show.
     *
     * All members, both byte counters and the ring are updated here before anything is published, so
     * the caller can never observe a green progress and the matching paused state in two steps. The
     * publish decision is made in the same pass: a structural change — including a terminal event that
     * happened since the previous pass — is always published at once, while a plain byte tick is
     * coalesced to at most one publish per [publishIntervalMillis].
     */
    fun reduce(tasks: List<DownloadTaskProgress>, nowMillis: Long): DownloadRoundUpdate {
        val revisionBefore = roundRevision
        val modeBefore = ring.mode
        val eventBefore = pendingStructuralChange
        pendingStructuralChange = false

        val activeTasks = tasks.filter { it.active }
        if (activeTasks.isEmpty()) {
            endRound()
            // Only a real user pause turns the ring yellow. A failed download is a terminal state of
            // its own and must never be reported as "everything is paused".
            val paused = tasks.any { it.phase == DownloadPhase.PAUSED }
            ring = if (paused) DownloadRingState.Paused else DownloadRingState.Hidden
            return publish(nowMillis, eventBefore || ring.mode != modeBefore || roundRevision != revisionBefore)
        }

        activeTasks.forEach { task ->
            if (members.containsKey(task.transferId)) return@forEach
            // Joining brings the complete file size into the denominator and the bytes that are
            // already there into the numerator: a resumed 40 MB / 100 MB file starts the round at 40 %.
            members[task.transferId] = Member(
                totalBytes = task.totalBytes.coerceAtLeast(0L),
                downloadedBytes = 0L,
                totalKnown = task.totalBytes > 0L,
            )
            roundRevision++
        }

        tasks.forEach { task ->
            val member = members[task.transferId] ?: return@forEach
            // A completed member is already reported as fully loaded and never counts twice.
            if (member.completed) return@forEach
            if (task.totalBytes > 0L) {
                if (!member.totalKnown || member.totalBytes != task.totalBytes) {
                    // The size is only known once the first response arrived, and a restart of the
                    // transfer replaces it. The ring switches from the indeterminate animation to real
                    // byte progress in the same publish.
                    member.totalBytes = task.totalBytes
                    member.totalKnown = true
                    roundRevision++
                }
            } else if (member.totalKnown) {
                // The length is not known right now: the downloader either discarded it because the
                // remote object is being fetched again, or never received it. Faking a percentage from
                // a length that no longer belongs to the object being fetched is not an option, so this
                // member keeps the round indeterminate until a reliable length arrives.
                member.totalBytes = 0L
                member.totalKnown = false
                roundRevision++
            }
            if (task.active) {
                // Assignment, never accumulation: continuing a paused member cannot add its bytes twice.
                member.downloadedBytes = member.clamp(task.downloadedBytes)
            } else {
                // A paused or failed member stops transferring, but the bytes it really has at that
                // moment still belong to the round. They only ever move forward, so the ring cannot
                // jump back because a persisted value trails the last reported one.
                member.downloadedBytes = maxOf(
                    member.downloadedBytes,
                    member.clamp(task.downloadedBytes),
                )
            }
            // A member whose transfer record is already gone keeps its frozen snapshot.
        }

        recomputeTotals()
        ring = if (members.values.any { !it.totalKnown } || roundTotalBytes <= 0L) {
            DownloadRingState.Indeterminate
        } else {
            DownloadRingState.determinate(
                roundLoadedBytes.toDouble().div(roundTotalBytes.toDouble()).toFloat()
            )
        }
        return publish(nowMillis, eventBefore || ring.mode != modeBefore || roundRevision != revisionBefore)
    }

    private fun publish(nowMillis: Long, structural: Boolean): DownloadRoundUpdate {
        val publishNow = structural || nowMillis - lastRingPublishAt >= publishIntervalMillis
        if (publishNow) lastRingPublishAt = nowMillis
        return DownloadRoundUpdate(ring = ring, structural = structural, publishNow = publishNow)
    }

    /** Ends the round in one step: members and both byte counters are dropped together. */
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
            loaded += member.downloadedBytes
            total += member.totalBytes
        }
        roundLoadedBytes = loaded
        roundTotalBytes = total
    }

    companion object {
        /** Coalescing window for plain byte-progress ring publishes. */
        const val DEFAULT_PUBLISH_INTERVAL_MILLIS = 80L
    }
}
