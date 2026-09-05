package kplayer.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kplayer.core.MediaPlayer
import kplayer.core.audio.AudioInterruption
import kplayer.core.event.PlaybackEvent
import kplayer.interruption.InterruptionCause
import kplayer.interruption.InterruptionEvent
import kplayer.interruption.PlaybackInterruptionHandler
import kplayer.observers.InterruptionObserver
import kplayer.core.state.MediaSource
import kplayer.core.state.PlaybackError
import kplayer.core.state.PlayerState

class KMediaManager<
        S : PlayerState<S>,
        P : MediaPlayer<MediaSource,S>
>(
    val player: P,
    val playbackInterruptionHandler: PlaybackInterruptionHandler,
    val observers: List<InterruptionObserver>,
    val audioSessionCoordinator: AudioSessionCoordinator,
    private val scope: CoroutineScope,
) : MediaPlayer<MediaSource, S> by player {

    /**
     * Failures this manager describes itself, before the wrapped player ever hears
     * about the command — today only a denied audio session.
     *
     * Kept separate from the player's own stream rather than emitted into it: the
     * player did not do this, and reaching into another object's event source to
     * say so would make [player] responsible for a fact it has no way to explain.
     *
     * Unbounded, so describing a denial never fails and never suspends the caller
     * that pressed play — [runIfAudioSessionAvailable] runs on whatever thread the
     * UI called from.
     */
    private val managerEvents = Channel<PlaybackEvent>(Channel.UNLIMITED)

    private val _events = MutableSharedFlow<PlaybackEvent>()

    /**
     * The player's events and this manager's, as one stream.
     *
     * This override is not optional. `by player` would otherwise generate a
     * forwarder straight to `player.events`, and every failure this manager
     * describes — the whole reason it wraps the player — would be invisible to a
     * caller holding the only handle there is.
     *
     * Delivered with a suspending `emit`, so a subscriber that falls behind slows
     * the merge instead of losing events to a full buffer.
     *
     * Ends with [release], which cancels [scope]: a released player has nothing
     * further to report.
     */
    override val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()

    init {
        observers.forEach { it.start() }

        // UNDISPATCHED, for the same reason EngineMediaPlayer subscribes that way:
        // neither source replays, so a merge merely *scheduled* on the scope would
        // drop whatever was reported between construction and its first dispatch.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            merge(player.events, managerEvents.receiveAsFlow()).collect { _events.emit(it) }
        }

        // Kept subscribed for the manager's full lifetime, including while
        // stopped. Whether a given interruption should actually affect
        // playback (e.g. ignore resume while Stopped) is decided downstream
        // in playbackInterruptionHandler, not here — see its status guard.
        scope.launch {
            audioSessionCoordinator.interruptions.collect { change ->
                when (change) {
                    AudioInterruption.Began ->
                        playbackInterruptionHandler.onEvent(
                            InterruptionEvent.Began(InterruptionCause.AudioFocusLoss)
                        )

                    is AudioInterruption.Ended ->
                        playbackInterruptionHandler.onEvent(
                            InterruptionEvent.Ended(
                                InterruptionCause.AudioFocusLoss,
                                systemAllowsResume = change.systemAllowsResume,
                            )
                        )

                    AudioInterruption.DuckBegan ->
                        playbackInterruptionHandler.onEvent(InterruptionEvent.DuckBegan)

                    AudioInterruption.DuckEnded ->
                        playbackInterruptionHandler.onEvent(InterruptionEvent.DuckEnded)
                }
            }
        }
    }

    /**
     * Run [block] only if this player owns the audio session, and say so when it
     * does not.
     *
     * Denial is reported rather than swallowed because it is indistinguishable from
     * a no-op at the call site: a caller that pressed play and heard nothing has no
     * other way to learn that something else holds the session.
     */
    private fun runIfAudioSessionAvailable(block: () -> Unit) {
        if (audioSessionCoordinator.acquire())
            block()
        else
            // Straight onto the queue rather than through scope.launch: the denial is
            // then already ordered ahead of anything the caller does next, instead of
            // landing whenever the scope gets round to it.
            managerEvents.trySend(PlaybackEvent.Failure(PlaybackError.AudioSessionDenied))
    }

    override fun load(source: MediaSource) {
        runIfAudioSessionAvailable { player.load(source) }
    }

    override fun play() {
        runIfAudioSessionAvailable { player.play() }
    }

    override fun pause() {
        player.pause()
        // Intentionally no release() here — pause is transient, we keep
        // the session so resume doesn't have to re-arbitrate ownership.
    }

    override fun stop() {
        player.stop()
        audioSessionCoordinator.release()
        // interruptions subscription stays alive (see init) — the handler's
        // status guard is responsible for ignoring stray events post-stop.
    }

    override fun release() {
        audioSessionCoordinator.release()
        observers.forEach { it.stop() }
        player.release()
        scope.cancel()
    }
}