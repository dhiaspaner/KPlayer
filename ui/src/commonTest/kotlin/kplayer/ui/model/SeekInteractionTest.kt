package kplayer.ui.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scrub rule, tested with no Compose, no coroutines and no player — which is
 * the point of it being a value in `kplayer.ui.model` rather than behaviour welded
 * into a state holder.
 */
class SeekInteractionTest {

    @Test
    fun idle_shows_the_engine_position() {
        assertEquals(1_000L, SeekInteraction.Idle.displayPosition(1_000L))
        assertFalse(SeekInteraction.Idle.isScrubbing)
    }

    @Test
    fun drag_wins_over_the_engine_and_issues_no_seek() {
        val dragging = SeekInteraction.Idle.onDrag(42_000L).onDrag(43_000L)

        assertTrue(dragging.isScrubbing)
        assertEquals(43_000L, dragging.displayPosition(1_000L))
        assertNull(dragging.pendingSeekMs)
    }

    @Test
    fun release_commits_exactly_one_seek_and_holds_the_target() {
        val (state, seekToMs) = SeekInteraction.Idle.onDrag(42_000L).onDragEnd()

        assertEquals(42_000L, seekToMs)
        assertFalse(state.isScrubbing)
        // Still the target, not the engine's stale position — no rubber-band.
        assertEquals(42_000L, state.displayPosition(1_000L))
    }

    @Test
    fun release_without_a_drag_commits_nothing() {
        val (state, seekToMs) = SeekInteraction.Idle.onDragEnd()

        assertNull(seekToMs)
        assertEquals(SeekInteraction.Idle, state)
    }

    @Test
    fun engine_within_tolerance_counts_as_caught_up() {
        val pending = SeekInteraction.Idle.onDrag(42_000L).onDragEnd().state

        assertTrue(pending.hasCaughtUp(42_000L + SeekInteractionDefaults.SETTLE_TOLERANCE_MS))
        assertTrue(pending.hasCaughtUp(42_000L - SeekInteractionDefaults.SETTLE_TOLERANCE_MS))
        assertFalse(pending.hasCaughtUp(42_000L + SeekInteractionDefaults.SETTLE_TOLERANCE_MS + 1))
    }

    @Test
    fun nothing_pending_never_reports_caught_up() {
        assertFalse(SeekInteraction.Idle.hasCaughtUp(0L))
    }

    @Test
    fun settling_hands_the_thumb_back_to_the_engine() {
        val settled = SeekInteraction.Idle.onDrag(42_000L).onDragEnd().state.settled()

        assertNull(settled.pendingSeekMs)
        assertEquals(1_000L, settled.displayPosition(1_000L))
    }

    @Test
    fun a_drag_started_while_a_seek_is_pending_wins() {
        val pending = SeekInteraction.Idle.onDrag(42_000L).onDragEnd().state

        assertEquals(60_000L, pending.onDrag(60_000L).displayPosition(1_000L))
    }
}
