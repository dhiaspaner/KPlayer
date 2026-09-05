package kplayer.core.player

import kotlinx.coroutines.flow.Flow
import kplayer.core.event.PlaybackEvent
import kplayer.core.state.MediaSource

/**
 * The whole surface a media engine has to provide. ExoPlayer sits behind it on
 * Android, `AVPlayer` on iOS, GStreamer and the desktop stacks on the JVM, a
 * media element on the web, and a fake sits behind it in tests.
 *
 * This is the seam that makes [EngineMediaPlayer] — and therefore all of the
 * backend sequencing: buffering bookkeeping, auto-play, position sync, the
 * load/stop/release ordering — testable in `commonTest` with no device and no
 * real media. Everything that is *policy* lives above this interface;
 * implementations are only allowed to know how to drive their native player.
 *
 * ### Implementing one
 *
 * Extend [AbstractMediaEngine] rather than implementing this directly: it owns
 * [events] and gives you the `report…` calls the rules below are written in terms
 * of. Two rules, and they are the ones a native player will break if you let it:
 *
 * 1. **Translate quirks here, not upstream.** Report clean semantics even when the
 *    native API does not. `ExoAudioEngine` swallows the spurious `isPlaying=false`
 *    that ExoPlayer emits at end-of-media, because upstream that would read as a
 *    pause; `AvAudioEngine` re-applies playback speed after `play()`, because
 *    `AVPlayer` silently resets `rate` to 1.0. Neither quirk is visible above.
 * 2. **Never report state you were told to enter.** Only report what the native
 *    player actually did, through [events], once it has done it. [play] must not
 *    report [PlaybackEvent.PlaybackStarted] itself — wait for the native callback,
 *    or `PlaybackState` starts describing intentions instead of facts.
 *
 * Calls arrive on whatever thread [EngineMediaPlayer]'s action scope dispatches on,
 * which is the main thread by default — both mobile engines require that. Events
 * may be reported from any thread; the flow below is the hand-off.
 */
interface MediaEngine {

    /**
     * Facts from the native player, never intentions.
     *
     * A hot stream, not a callback list: engines report from wherever their native
     * stack fires — the main thread for ExoPlayer and `AVPlayer`, a poll thread for
     * the desktop engines — and the flow carries them to [EngineMediaPlayer]'s
     * scope, which is where every state update belongs. Nothing replays, so an
     * event reported before anyone collects is gone; [EngineMediaPlayer] subscribes
     * in its constructor for exactly that reason.
     *
     * The shared vocabulary is [PlaybackEvent.PlaybackStarted] / [PlaybackEvent.PlaybackPaused],
     * [PlaybackEvent.BufferingStarted] / [PlaybackEvent.BufferingEnded],
     * [PlaybackEvent.Ready], [PlaybackEvent.PlaybackCompleted] and
     * [PlaybackEvent.Failure]. Anything else — video's
     * [PlaybackEvent.SubtitleCueChanged], which has no audio counterpart — is
     * carried straight to the state machine, where the medium's `reduceCustom` hook
     * decides what it means. That is what keeps a medium-specific fact from
     * widening this interface for everybody.
     */
    val events: Flow<PlaybackEvent>

    /**
     * Point the engine at [source] without starting to load it.
     *
     * @return `false` if this engine cannot represent the source at all (an
     *   unparseable URL, say), in which case nothing must have changed. The caller
     *   turns that into a failure rather than a silent no-op.
     */
    fun setSource(source: MediaSource): Boolean

    /** Begin loading whatever [setSource] accepted; ends in [PlaybackEvent.Ready]. */
    fun prepare()

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun setSpeed(speed: Float)

    /** [volume] is already clamped to `0f..1f` by the caller. */
    fun setVolume(volume: Float)

    /** Polled while playing to drive `positionMs`. Must be cheap. */
    fun currentPositionMs(): Long

    /** Tear down the native player. Nothing else is called afterwards. */
    fun release()

}
