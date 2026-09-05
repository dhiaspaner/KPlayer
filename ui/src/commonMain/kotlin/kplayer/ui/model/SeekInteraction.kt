package kplayer.ui.model

import kotlin.math.abs

/**
 * The seek bar's in-flight drag, as an immutable value.
 *
 * A drag has two phases the engine cannot represent, and both have to win over
 * the engine's reported position or the thumb visibly fights the finger:
 *
 * - **[dragPositionMs]** — finger down. No seek has been issued yet; exactly one
 *   goes out on release, so a 4-second drag is one seek, not two hundred.
 * - **[pendingSeekMs]** — finger up, seek issued, engine has not caught up. The
 *   naive implementation clears the drag here and the thumb snaps back to the
 *   old position for a frame or two before jumping forward. Holding the target
 *   until the engine confirms removes that rubber-band.
 *
 * Plain Kotlin — no Compose, no coroutines, no platform — because it is the
 * *rule*, not the container. A Compose player wraps it in snapshot state
 * ([kplayer.ui.SeekInteractionState]); a SwiftUI player wraps it in an
 * `@Observable`; a test drives it with neither. Each of those is a few lines of
 * container over the same behaviour, which is the only part that can drift.
 *
 * In `:ui` because chrome is this module's concern, not `:core`'s — but in
 * `kplayer.ui.model` rather than `kplayer.ui` so the toolkit-free half stays
 * visibly separate from the Compose containers around it.
 *
 * ```kotlin
 * var seek = SeekInteraction.Idle
 *
 * seek = seek.onDrag(42_000)          // finger moves — no seek issued
 * val (next, target) = seek.onDragEnd()
 * seek = next
 * target?.let { player.seekTo(it) }   // exactly one seek, on release
 *
 * // later, as engine positions arrive:
 * if (seek.hasCaughtUp(enginePositionMs)) seek = seek.settled()
 * ```
 *
 * The drag itself is deliberately **not** something to publish across a UI
 * boundary: it belongs to whichever toolkit owns the gesture. Only the committed
 * seek from [onDragEnd] is meaningful to anyone else.
 */
data class SeekInteraction(
    /** Non-null while the user holds the thumb. */
    val dragPositionMs: Long? = null,
    /** Non-null between issuing a seek and the engine reporting it landed. */
    val pendingSeekMs: Long? = null,
) {

    /** True while the finger is down. */
    val isScrubbing: Boolean get() = dragPositionMs != null

    /** The drag target, then the pending target, then the engine's own position. */
    fun displayPosition(enginePositionMs: Long): Long =
        dragPositionMs ?: pendingSeekMs ?: enginePositionMs

    fun onDrag(positionMs: Long): SeekInteraction = copy(dragPositionMs = positionMs)

    /**
     * Commits the drag: the resulting state, plus the one position to seek to —
     * or `null` when no drag was in progress.
     */
    fun onDragEnd(): SeekCommit = when (val target = dragPositionMs) {
        null -> SeekCommit(this, null)
        else -> SeekCommit(SeekInteraction(dragPositionMs = null, pendingSeekMs = target), target)
    }

    /**
     * True once the engine has landed within [toleranceMs] of the pending seek,
     * i.e. the thumb can be handed back to it. False when no seek is pending.
     */
    fun hasCaughtUp(
        enginePositionMs: Long,
        toleranceMs: Long = SeekInteractionDefaults.SETTLE_TOLERANCE_MS,
    ): Boolean {
        val target = pendingSeekMs ?: return false
        return abs(enginePositionMs - target) <= toleranceMs
    }

    /** Hands the thumb back to the engine. */
    fun settled(): SeekInteraction = copy(pendingSeekMs = null)

    companion object {
        /** Neither dragging nor waiting on a seek. */
        val Idle = SeekInteraction()
    }
}

/** The outcome of [SeekInteraction.onDragEnd]: the next state and the seek to issue. */
data class SeekCommit(
    val state: SeekInteraction,
    /** The position to seek to, or `null` if no drag was in progress. */
    val seekToMs: Long?,
)

object SeekInteractionDefaults {

    /** How close the engine must land to the seek target to be "caught up". */
    const val SETTLE_TOLERANCE_MS = 500L

    /**
     * Longest the thumb is ever held at a target the engine never confirms.
     *
     * The safety valve: if the seek is rejected or the stream ends first, the
     * engine never reports the target and the thumb would otherwise stay frozen
     * there forever. Enforcing the deadline needs a clock, so it belongs to the
     * container — this is only the number they should all agree on.
     */
    const val SETTLE_TIMEOUT_MS = 2_000L
}
