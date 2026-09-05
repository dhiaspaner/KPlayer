package kplayer.core.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kplayer.core.MediaPlayer
import kplayer.core.event.PlaybackAction
import kplayer.core.event.PlaybackEvent
import kplayer.core.state.MediaSource
import kplayer.core.state.PlayerState

/**
 * Everything about playback that is neither platform- nor medium-specific: the
 * state machine, the state/feedback streams, and the translation of the
 * [MediaPlayer] calls into [PlaybackAction]s.
 *
 * Subclasses implement exactly one method — [execute] — and report back what the
 * native engine did through [onEvent]. Commands go down as [PlaybackAction], facts
 * come up as [PlaybackEvent]; the two never cross, which is why [state] describes
 * what the engine did rather than what it was asked to do.
 *
 * @param reduceCustom see [PlaybackStateMachine.reduceCustom].
 * @param onLoad see [PlaybackStateMachine.onLoad].
 * @param scope runs the pump that delivers [events]. Not where transitions happen —
 *   those are inline under the graph's lock, on whatever thread called [onEvent].
 *   Cancelling it stops delivery, so anything still queued at that moment is lost;
 *   that is teardown, not backpressure.
 */
abstract class AbstractMediaPlayer<S : PlayerState<S>>(
    initialState: S,
    scope: CoroutineScope,
    reduceCustom: (S, PlaybackEvent) -> S? = { _, _ -> null },
    onLoad: (S) -> S = { it },
) : MediaPlayer<MediaSource, S> {

    private val machine = PlaybackStateMachine(
        initialState = initialState,
        reduceCustom = reduceCustom,
        onLoad = onLoad,
    )

    override val state = machine.state

    /**
     * Queue of events accepted but not yet handed to subscribers.
     *
     * Unbounded, because [onEvent] runs on native callbacks that can neither suspend
     * nor cope with being refused. This is the half of the guarantee the producer
     * sees: enqueuing always succeeds.
     */
    private val outbox = Channel<PlaybackEvent>(Channel.UNLIMITED)

    private val _events = MutableSharedFlow<PlaybackEvent>()

    /**
     * Everything this player is willing to say happened, in one stream.
     *
     * Two sources feed it and both arrive through [onEvent]: facts a subclass
     * relays from the native engine, and events the player raises itself
     * (`LoadRequested`, `PositionSynced`, a described `Failure`). Merging them
     * here rather than exposing the engine's flow directly is what makes
     * player-originated events visible to a caller at all — and it means a
     * subscriber sees exactly what [state] was computed from, never a fact the
     * player suppressed.
     *
     * Delivered with a suspending `emit` from [outbox], never `tryEmit`. A
     * `tryEmit` into a bounded buffer starts returning `false` the moment a
     * subscriber falls behind — silently, and during exactly the stalls whose
     * events a caller most needs. Here a slow subscriber grows the queue instead.
     */
    final override val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()

    init {
        // No UNDISPATCHED needed: the channel holds whatever is reported before this
        // pump first runs, which a SharedFlow with no replay would have discarded.
        scope.launch {
            outbox.receiveAsFlow().collect { _events.emit(it) }
        }
    }

    /**
     * Report a fact from the native engine. Called from platform callbacks —
     * `Player.Listener` on Android, KVO / notifications on iOS — never from a
     * caller wanting to change playback.
     *
     * The machine runs first, so a subscriber to [events] that reads [state] on
     * receipt sees the state this event produced rather than the one before it —
     * transitions are synchronous, so "first" is a real ordering guarantee.
     */
    protected fun onEvent(event: PlaybackEvent) {
        machine.onEvent(event)
        outbox.trySend(event)
    }

    override fun load(source: MediaSource) = execute(PlaybackAction.Load(source))
    override fun play() = execute(PlaybackAction.Play)
    override fun pause() = execute(PlaybackAction.Pause)
    override fun stop() = execute(PlaybackAction.Stop)
    override fun release() = execute(PlaybackAction.Release)
    override fun seekTo(positionMs: Long) = execute(PlaybackAction.SeekTo(positionMs))
    override fun setPlaybackSpeed(speed: Float) = execute(PlaybackAction.SetPlaybackSpeed(speed))
    override fun setVolume(volume: Float) = execute(PlaybackAction.SetVolume(volume))

    /**
     * Apply [action] to the native engine.
     *
     * Implementations must not update [state] directly; they issue the native call
     * and let the resulting callback come back through [onEvent].
     */
    protected abstract fun execute(action: PlaybackAction)
}
