package kplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kplayer.core.MediaPlayer
import kplayer.core.audio.AudioSessionMode
import kplayer.interruption.InterruptionConfig
import kplayer.core.state.MediaSource
import kplayer.videoplayer.VideoPlayer
import kplayer.videoplayer.VideoPlayerState

/**
 * Builds a fully-wired `:core` / `:video` engine and releases it when the
 * composable leaves the composition.
 *
 * The engine is returned directly — there is no controller wrapper. Everything
 * the UI needs is already on [MediaPlayer]: `state` to read and `onAction` to
 * command. Presentation state that the engine has no business knowing about
 * lives in [rememberPlayerUiStateHolder] instead.
 *
 * No `expect`/`actual` is needed here: `VideoPlayer()` is already the
 * platform-resolved factory in `:video`, and on Android it reads the
 * application context from `kplayer.appContext`, so the signature is identical
 * on both platforms. Remember to call `initializeContext(this)` from your
 * Android `Application`/`Activity`.
 *
 * @param interruptionConfig policy for calls, backgrounding, headphone
 *   disconnects and focus loss. Pass a `MutableStateFlow` to change it live.
 * @param audioSessionMode content kind, so each platform picks the right native
 *   session category — [AudioSessionMode.Movie] for video with dialogue.
 *
 * For playback that must survive configuration changes, build the engine in a
 * `ViewModel` and call `release()` from `onCleared()` instead of using this.
 */
@Composable
fun rememberVideoPlayer(
    interruptionConfig: StateFlow<InterruptionConfig> =
        MutableStateFlow(InterruptionConfig.MediaPlayerDefault),
    audioSessionMode: AudioSessionMode = AudioSessionMode.Movie,
): MediaPlayer<MediaSource, VideoPlayerState> {
    val player = remember {
        VideoPlayer(
            interruptionConfig = interruptionConfig,
            audioSessionMode = audioSessionMode,
        )
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    return player
}
