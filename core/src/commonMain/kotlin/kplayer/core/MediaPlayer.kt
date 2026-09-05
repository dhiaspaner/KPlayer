package kplayer.core

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kplayer.core.event.PlaybackAction
import kplayer.core.event.PlaybackEvent
import kplayer.core.state.PlaybackState
import kplayer.core.state.MediaSource


interface MediaPlayer<S : MediaSource, T : PlaybackState> {
    val state: StateFlow<T>

    val events: SharedFlow<PlaybackEvent>

    fun load(source: S)
    fun play()
    fun pause()
    fun stop()
    fun release()
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float)
    fun setVolume(volume: Float)


    /**
     * Single entry point for commands expressed as data — the input half of the
     * MVI pipeline the Compose layer drives.
     *
     * The UI never calls [play] / [pause] / [seekTo] directly; it emits a
     * [PlaybackAction] into a dispatcher lambda that ends here. That buys one
     * interception point for analytics, logging, remote transports or policy
     * ("no seeking past the ad break") instead of one per call site, and it is
     * the same vocabulary `AbstractVideoPlayer` already speaks internally.
     *
     * Implemented in terms of the methods above rather than beside them, so a
     * decorator that only overrides [play] — as `KMediaManager` does for audio
     * focus — is still honoured by actions. **Decorators built with interface
     * delegation (`by player`) must override this**, or Kotlin's generated
     * forwarder will hand the action straight to the wrapped player and skip
     * the decoration.
     *
     * The cast on [PlaybackAction.Load] is the one price of keeping
     * [PlaybackAction] non-generic. Every backend in this library is a
     * `MediaPlayer<MediaSource, …>`, so it always holds; a player that narrows
     * `S` further should override this and reject foreign sources.
     */
    fun onAction(action: PlaybackAction) {
        when (action) {
            is PlaybackAction.Load ->   load(action.source as S)
            PlaybackAction.Play -> play()
            PlaybackAction.Pause -> pause()
            PlaybackAction.Stop -> stop()
            PlaybackAction.Release -> release()
            is PlaybackAction.SeekTo -> seekTo(action.positionMs)
            is PlaybackAction.SetPlaybackSpeed -> setPlaybackSpeed(action.speed)
            is PlaybackAction.SetVolume -> setVolume(action.volume)
        }
    }
}
