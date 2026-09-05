package kplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kplayer.IosVideoPlayer
import kplayer.ui.model.VideoScalingMode
import kplayer.core.MediaPlayer
import kplayer.engine.KMediaManager
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVKit.AVPlayerViewController
import platform.UIKit.UIColor

/**
 * iOS video surface: an `AVPlayerViewController` with its own transport chrome
 * switched off.
 *
 * This replaces an earlier bare `AVPlayerLayer`. The layer is lighter, but
 * Picture-in-Picture is not reachable from it without also driving an
 * `AVPictureInPictureController` and its delegate by hand, whereas
 * `AVPlayerViewController` exposes it as one property — along with the system's
 * PiP restore behaviour, Now Playing integration and AirPlay routing, all of
 * which are fiddly to rebuild. The original objection to the view controller was
 * its built-in controls fighting the Compose overlay; `showsPlaybackControls =
 * false` settles that completely.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun NativeVideoSurface(
    player: MediaPlayer<*, *>,
    config: VideoSurfaceConfig,
    modifier: Modifier,
) {
    // Renders nothing rather than crashing for a player with no AVPlayer behind
    // it (a fake in a preview or test).
    val iosBackend = player.iosPlayerOrNull() ?: return
    val avPlayer = iosBackend.avPlayer

    // TEXTURE draws the frames itself instead of hosting a view controller, and
    // the two are genuinely exclusive: composing both would decode twice and
    // stack two pictures. Everything below this point is the DIRECT path.
    //
    // Switching between them needs no `key(…)` the way Android's does: the two
    // are separate branches, so Compose disposes one subtree and builds the
    // other by itself. That dispose is load-bearing —
    // `ComposeVideoFrameSurface` turns frame output *off* on the way out, which
    // is what stops the copy when a player goes back to DIRECT.
    //
    // What TEXTURE gives up, and why it is not worked around here: PiP, AirPlay
    // routing, Now Playing and the native transport all belong to
    // `AVPlayerViewController`, so `allowsPip` and `showNativeControls` have
    // nothing to act on — rebuilding them over a Canvas would mean driving
    // `AVPictureInPictureController` and its delegate by hand for a mode chosen
    // precisely to keep the video inside the Compose scene. `keepScreenOn` is
    // the exception and applies to both paths: it is the app-global idle timer,
    // never the view controller's to begin with.
    val surfaceModifier = modifier.then(keepScreenOnModifier(player, config))

//    if (config.renderMode == VideoRenderMode.TEXTURE) {
//        // Unconditionally, ignoring showNativeSubtitles: an AVPlayerItemVideoOutput
//        // yields the *video* track's pixels, and AVFoundation burns captions in at
//        // the layer, not into those buffers. Leaving native rendering on here would
//        // mean no subtitles at all rather than native ones, so the cues are routed
//        // to `activeSubtitle` and the app's contentOverlay draws them.
//        //
//        // Not restored on the way out: the DIRECT branch below sets it from
//        // `showNativeSubtitles` on every composition, so flipping back corrects it.
//        LaunchedEffect(iosBackend) { iosBackend.routesSubtitlesToState = true }
//
//
//        // Whether frames are actually arriving is not a println here: the frame
//        // path publishes `frameOutputFailure` and the surface publishes what it
//        // drew, both under the `kplayer/frames` log tag, and
//        // `rememberVideoFrameDiagnostics` puts the pair on screen — see the
//        // sample's frame-output panel.
//        ComposeVideoFrameSurface(
//            frameSource = iosBackend.frameSource,
//            config = config,
//            modifier = surfaceModifier,
//        )
//        return
//    }

    // On iOS, extracting cues for Compose and letting AVFoundation draw them are
    // mutually exclusive — attaching a legible output suppresses native
    // rendering. So the choice has to be pushed into the engine rather than
    // settled at draw time the way it is on Android.
    LaunchedEffect(iosBackend, config.showNativeSubtitles) {
        iosBackend.routesSubtitlesToState = !config.showNativeSubtitles
    }

    UIKitViewController(
        modifier = surfaceModifier,
        factory = {
            AVPlayerViewController().apply {
                setPlayer(avPlayer)
                applyConfig(config)
            }
        },
        update = { controller ->
            if (controller.player !== avPlayer) controller.setPlayer(avPlayer)
            controller.applyConfig(config)
        },
        onRelease = { controller ->
            // Detach only. IosVideoPlayer owns the AVPlayer and outlives this view.
            controller.setPlayer(null)
        },
        properties = UIKitInteropProperties(
            isNativeAccessibilityEnabled = true
        )
    )
}

/** The parts of [VideoSurfaceConfig] the view controller can honour. */
@OptIn(ExperimentalForeignApi::class)
private fun AVPlayerViewController.applyConfig(config: VideoSurfaceConfig) {
    videoGravity = config.scalingMode.toVideoGravity()
    allowsPictureInPicturePlayback = config.allowsPip
    view.backgroundColor = config.backgroundColor.toUIColor()
    // Off by default, which is what makes the Compose overlay the only chrome —
    // AVKit's controls would otherwise sit under it and eat the same taps.
    setShowsPlaybackControls(config.showNativeControls)
}

/**
 * Digs the backend out of the player — the `AVPlayer` for DIRECT, the frame
 * source for TEXTURE, and the subtitle-routing switch for both.
 *
 * `VideoPlayer()` returns a [KMediaManager] that delegates to the platform
 * player, so the backend is one level down; the direct case is handled too for
 * callers who built an [IosVideoPlayer] without the manager. `null` for anything
 * else — a fake in a preview or a test — which is what makes both render paths
 * draw nothing rather than crash.
 */
private fun MediaPlayer<*, *>.iosPlayerOrNull(): IosVideoPlayer? = when (this) {
    is IosVideoPlayer -> this
    is KMediaManager<*, *> -> player as? IosVideoPlayer
    else -> null
}

/**
 * FIT letterboxes, CROP fills and clips, FILL stretches.
 *
 * The `AVLayerVideoGravity*` bindings are typed `String?` but are documented
 * non-null constants, hence the assertion.
 */
private fun VideoScalingMode.toVideoGravity(): String = when (this) {
    VideoScalingMode.FIT -> AVLayerVideoGravityResizeAspect
    VideoScalingMode.CROP -> AVLayerVideoGravityResizeAspectFill
    VideoScalingMode.FILL -> AVLayerVideoGravityResize
}!!

/** Compose colours are sRGB floats, which is exactly what this initialiser wants. */
private fun Color.toUIColor(): UIColor = UIColor(
    red = red.toDouble(),
    green = green.toDouble(),
    blue = blue.toDouble(),
    alpha = alpha.toDouble(),
)
