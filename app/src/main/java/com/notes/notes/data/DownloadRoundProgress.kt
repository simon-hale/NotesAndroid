package com.notes.notes.data

import com.notes.notes.core.DownloadRingMode
import com.notes.notes.core.DownloadRingState

/**
 * One download task as the round accounting sees it at one instant.
 *
 * The bytes are the freshest values available for the transfer; [active] tells whether more bytes are
 * still expected from it. A task that is queued but has not been given a worker slot yet is active
 * too: it will continue transferring, so a moment without a running worker must not end the round.
 */
data class DownloadTaskProgress(
    val transferId: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val active: Boolean,
    val paused: Boolean,
)

/** Outcome of one accounting pass. */
data class DownloadRoundUpdate(
    val ring: DownloadRingState,
    /**
     * True when the round itself changed: a member joined, completed or left, the round ended, or the
     * ring switched between green, indeterminate, paused and hidden. Such a change must reach the UI
     * immediately, while a plain byte tick may be coalesced with the next one.
     */
    val structural: Boolean,
)

/**
 * Byte accounting of the current *download round*.
 *
 * A round is the set of downloads whose bytes the drawer ring shows at the same time. It starts when
 * the first active download appears and is destroyed in one step when the last active download is
 * gone — whether it completed or was paused. Membership is a property of the round, not of the
 * moment: a file that was counted once stays in the round until the round ends, even if it is paused
 * in the meantime or finishes, so neither pausing nor completing one file can shrink the denominator
 * and make the ring jump.
 *
 * The object is a plain state holder with no coroutines of its own: [reduce] is a pure-ish function
 * over the tasks the owner passes in, and the owner (the ViewModel) calls every method from its single
 * main-dispatcher context. Members, [loadedBytes] and [totalBytes] can therefore never be updated
 * concurrently, and [reduce] either applies a whole state transition or none of it.
 */
class DownloadRoundProgress {

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

    /**
     * Transfers whose last attempt ended in an error.
     *
     * A failure is not a user pause: it must never turn the ring yellow, and the file has to be
     * started again explicitly before it joins a round.
     */
    private val failed = mutableSetOf<String>()

    private var ring = DownloadRingState.Hidden
    private var roundLoadedBytes = 0L
    private var roundTotalBytes = 0L

    /** Bumped by every change of the member set, so the owner can tell structural changes from ticks. */
    private var roundRevision = 0

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
        failed.remove(transferId)
        val member = members[transferId] ?: return
        member.completed = true
        if (member.totalKnown) member.downloadedBytes = member.totalBytes
        roundRevision++
    }

    /**
     * A member's attempt failed. Its last valid byte snapshot stays in the round for as long as the
     * round lives, so one failure cannot shift the running progress, but the failure itself is
     * terminal: it is not a pause and it does not rejoin the next round automatically.
     */
    fun onTaskFailed(transferId: String) {
        failed.add(transferId)
    }

    /**
     * A destructive cancel removed the transfer. It leaves the round immediately, because the local
     * partial file and the transfer record are gone as well. The percentage may therefore jump, which
     * is the accepted consequence of the existing cancel semantics.
     */
    fun onTaskCancelled(transferId: String) {
        failed.remove(transferId)
        if (members.remove(transferId) != null) {
            roundRevision++
        }
    }

    /** Drops every knowledge about rounds, for example when the signed-in account changes. */
    fun reset() {
        members.clear()
        failed.clear()
        roundLoadedBytes = 0L
        roundTotalBytes = 0L
        roundRevision++
        ring = DownloadRingState.Hidden
    }

    /**
     * Applies the current task set and returns the ring to show.
     *
     * All members, both byte counters and the ring are updated here before anything is published, so
     * the caller can never observe a green progress and the matching paused state in two steps.
     */
    fun reduce(tasks: List<DownloadTaskProgress>): DownloadRoundUpdate {
        val revisionBefore = roundRevision
        val modeBefore = ring.mode

        val activeTasks = tasks.filter { it.active }
        if (activeTasks.isEmpty()) {
            endRound()
            val paused = tasks.any { task -> task.paused && task.transferId !in failed }
            ring = if (paused) DownloadRingState.Paused else DownloadRingState.Hidden
            return DownloadRoundUpdate(
                ring = ring,
                structural = ring.mode != modeBefore || roundRevision != revisionBefore,
            )
        }

        activeTasks.forEach { task ->
            // Downloading again starts a new attempt, so an earlier failure of this transfer is history.
            failed.remove(task.transferId)
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
        return DownloadRoundUpdate(
            ring = ring,
            structural = ring.mode != modeBefore || roundRevision != revisionBefore,
        )
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
}
