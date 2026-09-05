package kplayer.core.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kplayer.core.event.PlaybackAction
import kplayer.core.event.PlaybackEvent
import kplayer.core.state.MediaSource
import kplayer.core.state.PlaybackError
import kplayer.core.state.PlayerState
import kplayer.core.state.isBuffering
import kplayer.core.state.toPlaybackError
import kotlin.time.Duration.Companion.milliseconds

/**
 * A complete player of any medium, given only a [MediaEngine].
 *
 * Everything that would otherwise be copy-pasted into each platform backend lives
 * here — action dispatch, the engine-event to state-machine wiring, buffering
 * bookkeeping, the `playWhenReady` auto-play, volume clamping and the position-sync
 * loop. A backend is then just an engine plus a native handle.
 *
 * The point is testability: with a fake engine this class exercises the entire
 * backend contract on the JVM, no device and no media required. See `:audio`'s
 * `EngineMediaPlayerTest`, which covers this for both mediums.
 *
 * ### Facts come up one way
 *
 * The engine reports through [MediaEngine.events] and nothing else, and this class
 * is its only subscriber. Collection starts in the constructor, undispatched, so
 * the subscription exists before any caller can issue a command: the flow does not
 * replay, and an engine that faults on its very first native call would otherwise
 * report into nothing.
 *
 * What the engine reports is *not* republished verbatim. Everything a subscriber to
 * [events] sees has been through [onEvent], which is also where this class raises
 * its own events — so the two streams merge into one and a caller cannot tell, nor
 * need to, which side a fact came from. The difference is deliberate: the buffering
 * runs collapsed below and the duplicate `BufferingEnded`s dropped never reach a
 * subscriber, because [events] describes what the player accepted, and [state] is
 * computed from exactly the same events.
 *
 * ### Failure has exactly one route out
 *
 * Playback fails from two directions — an action throws while being applied, or the
 * engine reports a fault long afterwards — and both arrive at [reportFailure],
 * which is the only place that hands [PlaybackEvent.Failure] to the machine.
 * Synchronous failures get there through [runAction] wrapped around the dispatch
 * `when`, so every action is covered by construction rather than by each branch
 * remembering to `try`; asynchronous ones get there from [onEngineFailure].
 *
 * That funnel is what makes retrying possible at all: both routes hold a
 * [PlaybackAction], so re-running one is just executing it again, and
 * [PlaybackRetryPolicy] decides whether to. The alternative — a policy that knows
 * how to reconstruct each kind of command — is a second copy of the dispatch `when`
 * that silently rots.
 *
 * @param engine the native player. Owned by this class — [release] releases it.
 * @param initialState the medium's zero state, e.g. `AudioPlayerState()`.
 * @param scope every [PlaybackAction], the engine-event collector and the
 *   position-sync loop are dispatched here. Must be main-thread bound in
 *   production: ExoPlayer rejects off-main calls and `AVPlayer` mutation off-main
 *   is undefined.
 * @param positionSyncIntervalMs how often `positionMs` is refreshed while playing.
 * @param retryPolicy consulted after every failure that has an action behind it.
 *   Defaults to [PlaybackRetryPolicy.None] — a silent reload is a product decision.
 * @param reduceCustom see [PlaybackStateMachine.reduceCustom].
 * @param onLoad see [PlaybackStateMachine.onLoad].
 */
open class EngineMediaPlayer<S : PlayerState<S>>(
    protected val engine: MediaEngine,
    initialState: S,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val positionSyncIntervalMs: Long = 500L,
    private val retryPolicy: PlaybackRetryPolicy = PlaybackRetryPolicy.None,
    reduceCustom: (S, PlaybackEvent) -> S? = { _, _ -> null },
    onLoad: (S) -> S = { it },
) : AbstractMediaPlayer<S>(
    initialState = initialState,
    scope = scope,
    reduceCustom = reduceCustom,
    onLoad = onLoad,
) {


    private var positionJob: Job? = null


    /**
     * The action a retry chain is currently walking, and how many attempts it has
     * spent. Keyed by the action so unrelated failures never share a budget, and
     * cleared the moment the engine reports real progress ([PlaybackEvent.Ready],
     * playback actually starting, or completion) — a load that succeeds and then
     * fails ten minutes later is a new incident, not the tail of an old one.
     *
     * Only touched from [scope], which is single-threaded in production and in tests.
     */
    private var retryAction: PlaybackAction? = null
    private var retryAttempts = 0

    init {
        // UNDISPATCHED: the collect() below must have subscribed by the time this
        // constructor returns. A SharedFlow does not replay, so a subscription
        // merely *scheduled* on the scope would silently drop anything the engine
        // reported first.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {

            engine.events.collect { onEngineEvent(it) }
        }
    }

    // ── Facts from the engine ───────────────────────────────────────────────

    /**
     * Everything the engine reports, in the one place that may act on it.
     *
     * Not exposed: these are inbound facts, and a caller able to inject `Ready`
     * could desynchronise the state machine from the engine. The events are passed
     * to the machine as they arrive; what this adds is the bookkeeping the engine
     * has no business knowing about — the buffering de-duplication, the position
     * loop, auto-play, and the retry chain.
     */
    private fun onEngineEvent(event: PlaybackEvent) {
        when (event) {
            PlaybackEvent.PlaybackStarted -> {
                clearRetryChain()
                onEvent(event)
                startPositionSync()
            }

            PlaybackEvent.PlaybackPaused -> {
                onEvent(event)
                stopPositionSync()
            }

            PlaybackEvent.BufferingStarted -> {
                if (state.value.isBuffering) return
                onEvent(event)
            }

            PlaybackEvent.BufferingEnded -> endBuffering()

            is PlaybackEvent.Ready -> {
                endBuffering()
                clearRetryChain()
                onEvent(event)
                // Auto-play is the player's call, not the engine's: the engine has
                // no idea what the caller asked for. play() goes the long way round
                // through execute() so the action is dispatched like any other.
                if (state.value.playWhenReady) play()
            }

            PlaybackEvent.PlaybackCompleted -> {
                endBuffering()
                stopPositionSync()
                clearRetryChain()
                onEvent(event)
            }

            is PlaybackEvent.Failure -> onEngineFailure(event.error)

            // A medium-specific fact — SubtitleCueChanged today — straight through
            // to the machine, where reduceCustom interprets it. Nothing here needs
            // to know what it means.
            else -> onEvent(event)
        }
    }

    // ── Actions from the caller ─────────────────────────────────────────────

    /**
     * The single boundary between "apply this action" and "this went wrong".
     *
     * [applyAction] says only what each action does; nothing in it handles a
     * failure, because [runAction] handles all of them. A new action is therefore
     * covered the moment it is added — the failure path is a property of this
     * method, not something each branch has to remember.
     */
    final override fun execute(action: PlaybackAction) {
        scope.launch { runAction(action) }
    }

    private fun applyAction(action: PlaybackAction) {
        when (action) {
            is PlaybackAction.Load -> loadInternal(action.source)
            PlaybackAction.Play -> engine.play()
            PlaybackAction.Pause -> engine.pause()
            PlaybackAction.Stop -> stopInternal()
            PlaybackAction.Release -> releaseInternal()
            is PlaybackAction.SeekTo -> seekToInternal(action.positionMs)
            is PlaybackAction.SetPlaybackSpeed -> setSpeedInternal(action.speed)
            is PlaybackAction.SetVolume -> setVolumeInternal(action.volume)
        }
    }

    // ── Error boundary ──────────────────────────────────────────────────────

    /**
     * Apply [action], and keep applying it for as long as [retryPolicy] asks.
     *
     * Native players throw for reasons common code cannot enumerate — a released
     * `MediaCodec`, an `AVPlayer` mutated after teardown, a JNA call into a library
     * that is not there. Left uncaught inside [scope] they would take out the whole
     * player silently; caught here they become a described failure with the action
     * that caused them still attached.
     *
     * The loop, rather than a single re-run, is what lets a budget of more than two
     * attempts mean anything: a retry that throws again is just the next iteration,
     * reported and weighed exactly like the first failure.
     *
     * [CancellationException] is rethrown before anything is described. Cancellation
     * is structured concurrency, not a playback failure: `release()` cancels [scope],
     * and treating that as an error would invent an `Error` status the caller never
     * hit *and* break cancellation for everything above.
     */
    private suspend fun runAction(action: PlaybackAction) {
        while (true) {
            val error = try {
                applyAction(action)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: PlaybackFailure) {
                // Already described by the player itself — mapping it again would
                // only widen a known failure back into Unknown.
                e.error
            } catch (e: Throwable) {
                // Platform classification, without this class knowing a single
                // platform type: `toPlaybackError` is actualised per target and
                // funnels into the same tables the engines use for their
                // asynchronous faults.
                e.toPlaybackError()
            }

            reportFailure(error)
            retryDelayFor(action, error)
                .peekOnGiveUp {
                    return
                }
                .onRetryAfter { decision ->
                    delay(duration = decision.delay)
                }
        }
    }

    /**
     * A fault the engine reported on its own, long after whatever command provoked
     * it returned.
     *
     * Recovery is a reload rather than a repeat of the last command: a faulted
     * engine has thrown its prepared item away, so re-running the `seekTo` that
     * happened to be in flight would fail against the same dead item. With no
     * source loaded there is nothing to re-run at all, and the failure simply
     * stands.
     */
    private fun onEngineFailure(error: PlaybackError) {
        reportFailure(error)

        val reload = state.value.source?.let(PlaybackAction::Load) ?: return
        val decision = retryDelayFor(reload, error) as? RetryDecision.RetryAfter ?: return

        scope.launch {
            delay(decision.delay)
            runAction(reload)
        }
    }

    /**
     * Hand [error] to the state machine, and stop anything that was still running as
     * though playback were healthy. Overridable so a backend can log or translate
     * before calling `super`.
     */
    protected open fun reportFailure(error: PlaybackError) {
        endBuffering()
        stopPositionSync()
        onEvent(PlaybackEvent.Failure(error))
    }

    /** Whether to run [action] again after [error], and how long to wait first. */
    private fun retryDelayFor(action: PlaybackAction, error: PlaybackError): RetryDecision =
        retryPolicy.decide(action, error, attempt = registerAttempt(action))

    /** Attempts spent on [action] including the one that just failed. */
    private fun registerAttempt(action: PlaybackAction): Int {
        if (action != retryAction) {
            retryAction = action
            retryAttempts = 0
        }
        return ++retryAttempts
    }

    private fun clearRetryChain() {
        retryAction = null
        retryAttempts = 0
    }

    /**
     * A failure this player described itself, carried out through the same `throw`
     * the boundary already handles rather than through a second return channel.
     */
    private class PlaybackFailure(val error: PlaybackError) : Exception(error.message)

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun loadInternal(source: MediaSource) {
        stopPositionSync()

        // Validated before LoadRequested is announced: a source the engine cannot
        // represent would otherwise strand the player in Buffering with nothing
        // left to complete it. Described here rather than through the errorMapper
        // because this is not a platform exception — nobody threw.
        if (!engine.setSource(source)) {
            throw PlaybackFailure(error = PlaybackError.Source("Invalid source: $source"))
        }

        onEvent(PlaybackEvent.LoadRequested(source))
        engine.prepare()
    }

    private fun stopInternal() {
        engine.pause()
        stopPositionSync()
        engine.seekTo(0L)
        onEvent(PlaybackEvent.StopRequested)
    }

    private fun releaseInternal() {
        engine.pause()
        stopPositionSync()
        engine.release()
        onEvent(PlaybackEvent.ReleaseRequested)
    }

    private fun seekToInternal(positionMs: Long) {
        engine.seekTo(positionMs)
        // Reported straight away so a scrubbing UI does not wait for the next sync
        // tick; the engine's own position takes over from the following tick.
        onEvent(PlaybackEvent.PositionSynced(positionMs))
    }

    private fun setSpeedInternal(speed: Float) {
        engine.setSpeed(speed)
        onEvent(PlaybackEvent.SpeedChanged(speed))
    }

    private fun setVolumeInternal(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        engine.setVolume(clamped)
        onEvent(PlaybackEvent.VolumeChanged(clamped))
    }

    private fun endBuffering() {
        if (!state.value.isBuffering) return
        onEvent(PlaybackEvent.BufferingEnded)
    }

    // ── Position sync ─────────────────────────────────────────────────────────

    /**
     * Driven by the engine's playing/paused reports rather than by `play()` /
     * `pause()`, so position stops advancing during a stall — the engine is not
     * actually playing then, whatever it was told to do.
     */
    private fun startPositionSync() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch {
            while (isActive) {
                onEvent(PlaybackEvent.PositionSynced(engine.currentPositionMs()))
                delay(positionSyncIntervalMs.milliseconds)
            }
        }
    }

    private fun stopPositionSync() {
        positionJob?.cancel()
        positionJob = null
    }

    companion object {

        /**
         * What common code can honestly say about an arbitrary throwable: nothing.
         *
         * Classification needs the platform's own exception types — ExoPlayer's
         * `PlaybackException.errorCode`, an `NSError` domain — so a backend that has
         * them passes its own mapper rather than this widening to guess from message
         * text.
         */
        val DefaultErrorMapper: (PlaybackAction, Throwable) -> PlaybackError =
            { _, throwable -> PlaybackError.Unknown(throwable.message, throwable) }
    }
}
