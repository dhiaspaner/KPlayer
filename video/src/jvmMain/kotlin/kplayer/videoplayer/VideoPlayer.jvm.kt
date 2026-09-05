package kplayer.videoplayer

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
import kplayer.core.player.MediaEngine
import kplayer.videoplayer.frame.VideoFrameSource
import kplayer.core.state.MediaSource
import kplayer.core.state.PlaybackError

/**
 * Desktop video player.
 *
 * Real playback on macOS and Windows, unlike the rejecting stub this replaced:
 * `EngineMediaPlayer` driving whichever backend [DesktopVideoEngines] picks.
 *
 * Frames reach the screen through `:ui`'s `NativeVideoSurface.jvm.kt`, which draws
 * [frameSource]'s BGRA bytes as ordinary Compose content — there is no native view
 * to host on desktop, so the picture is drawn rather than composited.
 *
 * On an OS with no engine, or with the native stack missing, the player degrades
 * to the old behaviour — every command emits `PlaybackFeedback.Rejected` with an
 * actionable message — rather than throwing from inside `play()`.
 */
actual fun VideoPlayer(
    interruptionConfig: StateFlow<InterruptionConfig>,
    audioSessionMode: AudioSessionMode,
): MediaPlayer<MediaSource, VideoPlayerState> =
    if (DesktopVideoEngines.isAvailable) {
        MediaPlayer {
            player { DesktopVideoPlayer() }
            interruptionConfig(interruptionConfig)
            // Desktop has no session category to map the mode onto; carried for
            // parity, exactly as DesktopAudioPlayer does.
            mode(audioSessionMode)
        }
    } else {
        UnavailableVideoPlayer(DesktopVideoEngines.unavailableReason)
    }

/**
 * Video backend for desktop. No logic of its own — everything lives in
 * `EngineMediaPlayer`, and everything platform-specific in the engine.
 *
 * `Dispatchers.Default` rather than `Main`: a desktop JVM process has no main
 * looper unless Compose installs one, and none of the three engines requires the
 * UI thread for transport.
 */
class DesktopVideoPlayer private constructor(
    private val desktopEngine: MediaEngine,
    scope: CoroutineScope,
) : EngineMediaPlayer<VideoPlayerState>(
    engine = desktopEngine,
    initialState = VideoPlayerState(),
    scope = scope,
    reduceCustom = VideoCueReducer,
    onLoad = VideoOnLoad,
) {

    constructor(
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ) : this(DesktopVideoEngines.create(), scope)

    /**
     * The decoded frames, for `:ui`'s desktop surface to draw.
     *
     * `null` only for a stub engine with no video path at all — every real
     * desktop engine (macOS, Windows, Linux) implements [VideoFrameSource]: none
     * of them has a native view to hand pixels to instead, so pulling frames is
     * the only way any of them puts a picture on screen.
     */
    val frameSource: VideoFrameSource? get() = desktopEngine as? VideoFrameSource
}

/**
 * Stands in when the platform has no video engine, so constructing a player never
 * throws and the reason reaches the caller through [MediaPlayer.feedback].
 */
private class UnavailableVideoPlayer(
    private val reason: String,
) : MediaPlayer<MediaSource, VideoPlayerState> {


    private val _events = MutableSharedFlow<PlaybackEvent>()

    override val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()
    private val machine = VideoPlayerStateMachine()

    override val state: StateFlow<VideoPlayerState> = machine.state

    override fun load(source: MediaSource) = reject()
    override fun play(): Unit = reject()
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
