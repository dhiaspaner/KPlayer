package kplayer.audioplayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kplayer.core.MediaPlayer
import kplayer.core.audio.AudioSessionMode
import kplayer.core.event.PlaybackEvent
import kplayer.interruption.InterruptionConfig
import kplayer.core.player.EngineMediaPlayer
import kplayer.core.player.PlaybackStateMachine
import kplayer.core.state.MediaSource
import kplayer.core.state.PlaybackError

/**
 * Desktop audio player.
 *
 * Real playback, unlike the rejecting stub this replaced: `EngineMediaPlayer` driving
 * whichever backend [DesktopAudioEngines] picks for the running OS.
 *
 * If the platform's native media libraries are missing, the player degrades rather than
 * throwing from inside `play()`: every command reports a `PlaybackEvent.Failure` carrying
 * an actionable message. Collect [MediaPlayer.events] to surface it.
 */
actual fun AudioPlayer(
    interruptionConfig: StateFlow<InterruptionConfig>,
    audioSessionMode: AudioSessionMode,
): MediaPlayer<MediaSource, AudioPlayerState> =
    if (DesktopAudioEngines.isAvailable) {
        MediaPlayer {
            player { DesktopAudioPlayer() }
            interruptionConfig(interruptionConfig)
            // Desktop has no session category to map the mode onto; carried for parity.
            mode(audioSessionMode)
        }
    } else {
        UnavailableAudioPlayer(DesktopAudioEngines.unavailableReason)
    }

/**
 * Audio backend for desktop. No logic of its own — everything lives in
 * `EngineMediaPlayer`, and everything platform-specific in the engine.
 *
 * `Dispatchers.Default` rather than `Main`: a desktop JVM process has no main looper
 * unless Compose installs one, and GStreamer is safe to drive from any thread.
 */
class DesktopAudioPlayer(
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : EngineMediaPlayer<AudioPlayerState>(
    engine = DesktopAudioEngines.create(),
    initialState = AudioPlayerState(),
    scope = scope,
)

/**
 * Stands in when the platform's media natives are absent, so constructing a player
 * never throws and the reason reaches the caller through [MediaPlayer.events].
 *
 * The same shape as `:video`'s `UnavailableVideoPlayer`, down to the `tryEmit`: there
 * is no scope here to suspend in, and a caller that subscribes after construction has
 * missed nothing it could have acted on anyway.
 */
private class UnavailableAudioPlayer(
    private val reason: String,
) : MediaPlayer<MediaSource, AudioPlayerState> {

    private val machine = PlaybackStateMachine(AudioPlayerState())
    private val _events = MutableSharedFlow<PlaybackEvent>()

    override val state: StateFlow<AudioPlayerState> = machine.state
    override val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()

    override fun load(source: MediaSource) = reject()
    override fun play() = reject()
    override fun pause() = reject()
    override fun stop() = reject()
    override fun release() = reject()
    override fun seekTo(positionMs: Long) = reject()
    override fun setPlaybackSpeed(speed: Float) = reject()
    override fun setVolume(volume: Float) = reject()

    private fun reject() {
        _events.tryEmit(PlaybackEvent.Failure(PlaybackError.Unknown(reason)))
    }
}
