package kplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kplayer.core.MediaPlayer

/**
 * The native **video** render surface — the only platform-specific part of the
 * player.
 *
 * Named for video deliberately: an audio player reuses [PlayerUiStateHolder],
 * [PlayerState] and the templates, but has no surface at all (or a
 * waveform/artwork one), so the video surface must not sit on a neutral name.
 *
 * Android builds an `AspectRatioFrameLayout` around a `SurfaceView` or
 * `TextureView`; iOS hosts an `AVPlayerViewController` with its own controls
 * switched off. Both reach into [player] for the native handle, attach to it,
 * and **detach without releasing** on dispose — the surface's lifetime is
 * shorter than the engine's, which survives configuration changes.
 *
 * Takes `MediaPlayer<*, *>` because a surface only ever needs the native handle
 * hiding inside it; nothing here reads the state or source types.
 *
 * @param config everything about how frames are drawn. Note that
 *   [VideoSurfaceConfig.scalingMode] is read live here — [FlexibleVideoPlayer]
 *   overwrites it from the UI state holder before passing the config down.
 */
@Composable
expect fun NativeVideoSurface(
    player: MediaPlayer<*, *>,
    config: VideoSurfaceConfig,
    modifier: Modifier,
)
