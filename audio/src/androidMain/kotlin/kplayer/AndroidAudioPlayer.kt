package kplayer

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kplayer.audioplayer.AudioPlayerState
import kplayer.core.player.EngineMediaPlayer
import kplayer.audioplayer.ExoAudioEngine
import kplayer.core.audio.AudioSessionMode
import kplayer.core.event.PlaybackAction

/**
 * Audio backend for Android: [EngineMediaPlayer] driving an ExoPlayer.
 *
 * There is deliberately no logic here. Everything the player does lives in
 * [EngineMediaPlayer] (and is unit-tested against a fake engine); everything
 * ExoPlayer-specific lives in `ExoAudioEngine`. This class exists to name the
 * combination and to expose the native handle.
 */
class AndroidAudioPlayer private constructor(
    private val exoEngine: ExoAudioEngine,
    scope: CoroutineScope,
) : EngineMediaPlayer<AudioPlayerState>(exoEngine, AudioPlayerState(), scope) {

    /**
     * @param scope scope every [PlaybackAction] is dispatched on. Must be
     *   main-thread bound — ExoPlayer rejects calls from anywhere else.
     */
    constructor(
        context: Context,
        audioSessionMode: AudioSessionMode = AudioSessionMode.Music,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    ) : this(ExoAudioEngine(context, audioSessionMode), scope)

    /**
     * The engine instance, exposed for integrations that need the real `Player` —
     * a `MediaSession` for the lock screen and notification, or an analytics
     * listener.
     *
     * Do not issue transport commands through this — playback must go through
     * [kplayer.core.MediaPlayer] so the state machine and the interruption engine stay in sync.
     * Do not release it either; this player owns its lifetime.
     */
    val exoPlayer: ExoPlayer get() = exoEngine.exoPlayer
}
