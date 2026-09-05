package kplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kplayer.ui.model.SeekInteraction

/**
 * Snapshot-backed container for the seek bar's in-flight drag.
 *
 * The *behaviour* is not here — it is [SeekInteraction] in `kplayer.ui.model`: drag,
 * single commit on release, then hold the target until the engine catches up.
 * This class is the thin Compose shell around that value, and it is deliberately
 * the only part that is Compose-specific, so a SwiftUI or TV consumer reuses the
 * rule and writes its own container.
 *
 * Snapshot state rather than a `StateFlow` on purpose: a drag is a gesture, at
 * frame rate, owned by whichever toolkit is handling the touch. Publishing the
 * in-flight position across a boundary buys nothing — no other consumer wants
 * it, and only the committed seek is meaningful outside this screen.
 *
 * Kept out of the player for the same reason: routing a drag through the engine
 * would put a native round-trip in a per-frame path and make the thumb's
 * smoothness a property of the backend.
 *
 * It is remembered one level above the seek bar so that *other* slots —
 * [PlayerState.DurationText], a thumbnail strip, a chapter label — can read
 * [PlayerState.displayPositionMs] and follow the thumb too, which they could not
 * do if the state were private to the slider.
 *
 * Hoistable via [rememberSeekInteractionState], the same shape as
 * [PlayerUiStateHolder] / [rememberPlayerUiStateHolder] — pass one in when
 * something outside the player needs to read [isScrubbing], e.g. a parent screen
 * suppressing its own auto-hide timer during a drag.
 */
@Stable
class SeekInteractionState {

    private var interaction by mutableStateOf(SeekInteraction.Idle)

    /** True while the finger is down. Auto-hide is suppressed on this. */
    val isScrubbing: Boolean get() = interaction.isScrubbing

    /** The drag target, then the pending target, then the engine's own position. */
    internal fun displayPosition(enginePositionMs: Long): Long =
        interaction.displayPosition(enginePositionMs)

    internal fun onDrag(positionMs: Long) {
        interaction = interaction.onDrag(positionMs)
    }

    /** Commits the drag; returns the position to seek to, or null if none. */
    internal fun onDragEnd(): Long? {
        val (next, seekToMs) = interaction.onDragEnd()
        interaction = next
        return seekToMs
    }

    /** True once the engine has landed close enough to the pending seek target. */
    internal fun hasCaughtUp(enginePositionMs: Long): Boolean =
        interaction.hasCaughtUp(enginePositionMs)

    /** Non-null between issuing a seek and the engine reporting it landed. */
    internal val pendingSeekMs: Long? get() = interaction.pendingSeekMs

    internal fun settle() {
        interaction = interaction.settled()
    }
}

/**
 * Creates a [SeekInteractionState] that survives recomposition.
 *
 * Not saved across process death like [rememberPlayerUiStateHolder] — a
 * mid-drag position is meaningless once the process and the gesture that
 * produced it are both gone.
 */
@Composable
fun rememberSeekInteractionState(): SeekInteractionState = remember { SeekInteractionState() }
