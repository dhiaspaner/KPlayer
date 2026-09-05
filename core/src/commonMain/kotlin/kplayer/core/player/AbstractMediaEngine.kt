package kplayer.core.player

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kplayer.core.event.PlaybackEvent
import kplayer.core.state.PlaybackError

/**
 * The event half of a [MediaEngine], so no engine has to build it again.
 *
 * Every engine reports the same handful of facts from a native callback that is
 * neither suspending nor coroutine-aware — a `Player.Listener`, a KVO observer, a
 * poll thread, a DOM event. This class turns those into the [events] flow the seam
 * asks for, and names each fact so the mapping stays legible at the call site:
 * `reportBuffering(true)` says what the engine observed, not which event type
 * encodes it.
 *
 * The `report…` calls are safe from any thread — that is the point of the flow —
 * and never suspend, so an engine can call them straight from a native callback.
 *
 * ### Nothing an engine reports is dropped
 *
 * The intake is an unbounded [Channel], so the enqueue behind every `report…` always
 * succeeds: a native callback can neither suspend nor be told "no room". Delivery is
 * then a suspending receive by the one consumer, so a slow [EngineMediaPlayer] makes
 * the queue grow rather than makes facts disappear.
 *
 * A [MutableSharedFlow] cannot offer that. `tryEmit` — the only emission a
 * non-suspending callback can make — returns `false` and discards the event once a
 * subscriber falls behind the buffer, which is exactly the stall during which the
 * events matter most.
 *
 * Two consequences worth knowing:
 *  - **Single consumer.** A channel hands each event to one receiver, and
 *    [EngineMediaPlayer] is the only one the seam allows. A second collector would
 *    not mirror the stream, it would steal from it.
 *  - **Nothing is lost before collection starts.** Events reported before anyone
 *    subscribes sit in the queue instead of vanishing, so an engine that faults on
 *    its very first native call is still heard.
 */
abstract class AbstractMediaEngine : MediaEngine {

    private val intake = Channel<PlaybackEvent>(Channel.UNLIMITED)

    final override val events: Flow<PlaybackEvent> = intake.receiveAsFlow()

    /**
     * Report a fact the shared vocabulary does not name — video's
     * [PlaybackEvent.SubtitleCueChanged] is the only one today. It lands in the
     * state machine, where the medium's `reduceCustom` hook interprets it.
     */
    protected fun report(event: PlaybackEvent) {
        // Cannot fail: an UNLIMITED channel has no capacity to run out of, and the
        // channel is closed only when the engine is done for good.
        intake.trySend(event)
    }

    /**
     * Playback actually started or actually stopped.
     *
     * Must **not** be called for the "stopped" that accompanies reaching the end of
     * the media — that is [reportCompleted]'s job, and reporting both makes the
     * player visibly flash through `Paused` on its way to `Completed`.
     */
    protected fun reportPlaying(isPlaying: Boolean) = report(
        if (isPlaying) PlaybackEvent.PlaybackStarted else PlaybackEvent.PlaybackPaused
    )

    /**
     * The engine started or stopped waiting for data. Safe to call repeatedly with
     * the same value; [EngineMediaPlayer] collapses runs into one started/ended pair.
     */
    protected fun reportBuffering(isBuffering: Boolean) = report(
        if (isBuffering) PlaybackEvent.BufferingStarted else PlaybackEvent.BufferingEnded
    )

    /**
     * The source is loaded and playable. [durationMs] is `0` when unknown, as for a
     * live stream.
     */
    protected fun reportReady(durationMs: Long) = report(PlaybackEvent.Ready(durationMs))

    /** Played through to the end. */
    protected fun reportCompleted() = report(PlaybackEvent.PlaybackCompleted)

    /**
     * The engine faulted. Classify where the platform's own error type is still in
     * reach — `PlaybackException.errorCode`, an `NSError` domain — because upstream
     * nothing can: [PlaybackRetryPolicy] decides what to retry from this alone.
     */
    protected fun reportError(error: PlaybackError) = report(PlaybackEvent.Failure(error))

    /** For an engine whose native stack offers nothing better than a string. */
    protected fun reportError(message: String) = report(PlaybackEvent.Failure(message))
}
