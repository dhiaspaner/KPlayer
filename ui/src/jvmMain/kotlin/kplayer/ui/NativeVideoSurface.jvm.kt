package kplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kplayer.core.MediaPlayer
import kplayer.engine.KMediaManager
import kplayer.videoplayer.DesktopVideoPlayer

/**
 * Desktop video surface, in whichever way the running OS's engine can render.
 *
 * All three desktop engines decode into frames rather than drawing anywhere
 * themselves, so this always takes the same path — the one iOS opts into with
 * [VideoRenderMode.TEXTURE]: the video is a real participant in the layout and
 * can be blurred, clipped and animated like anything else.
 *
 * | OS | Engine |
 * |---|---|
 * | macOS | AVFoundation |
 * | Windows | Media Foundation (`IMFMediaEngine`, frame-server mode) |
 * | Linux | GStreamer `appsink` |
 *
 * [VideoSurfaceConfig.renderMode] is therefore ignored on every desktop OS —
 * there is exactly one thing each engine can do.
 */
@Composable
actual fun NativeVideoSurface(
    player: MediaPlayer<*, *>,
    config: VideoSurfaceConfig,
    modifier: Modifier,
) {
    val frameSource = player.desktopPlayerOrNull()?.frameSource

    // Inert on desktop: Compose's owner ignores keepScreenOn there. Applied
    // anyway so all four surfaces carry the request the same way, and so it
    // lights up if the owner ever honours it.
    val surfaceModifier = modifier.then(keepScreenOnModifier(player, config))

    if (frameSource != null) {
        ComposeVideoFrameSurface(
            frameSource = frameSource,
            config = config,
            modifier = surfaceModifier,
        )
    } else {
        // A player with no desktop engine behind it — a fake in a preview or a
        // test, or an OS with no engine at all. Nothing to draw is not an error,
        // so the configured background stands in, exactly as it did before there
        // was any desktop backend.
        Box(surfaceModifier.background(config.backgroundColor))
    }
}

/**
 * Digs the desktop backend out of the player.
 *
 * `VideoPlayer()` returns a [KMediaManager] that delegates to the platform
 * player, so the backend is one level down; the direct case is handled too for
 * callers who built a [DesktopVideoPlayer] without the manager.
 */
internal fun MediaPlayer<*, *>.desktopPlayerOrNull(): DesktopVideoPlayer? = when (this) {
    is DesktopVideoPlayer -> this
    is KMediaManager<*, *> -> player as? DesktopVideoPlayer
    else -> null
}
