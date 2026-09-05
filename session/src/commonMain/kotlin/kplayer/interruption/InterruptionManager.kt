package kplayer.interruption

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Tracks the set of interruptions currently affecting playback.
 *
 * Pure state — it makes no playback decisions (that is
 * [PlaybackInterruptionHandler]'s job). Replacing the previous
 * per-source booleans with a set lets any number of interruptions coexist:
 * "resume only when nothing else is interrupting" becomes `active.isEmpty()`.
 *
 * [active] is exposed so UI can show *why* playback is paused
 * (e.g. "Paused — phone call").
 */
class InterruptionManager {

    private val _active = MutableStateFlow<Set<InterruptionCause>>(emptySet())
    val active: StateFlow<Set<InterruptionCause>> = _active.asStateFlow()

    fun begin(cause: InterruptionCause) = _active.update { it + cause }

    fun end(cause: InterruptionCause) = _active.update { it - cause }
}
