package kplayer.ui

import android.content.Context
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import kplayer.ui.model.VideoScalingMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.SubtitleView
import kplayer.AndroidVideoPlayer
import kplayer.core.MediaPlayer
import kplayer.engine.KMediaManager
import kplayer.core.event.PlaybackAction

/**
 * Android video surface.
 *
 * Builds the view hierarchy by hand instead of using Media3's `PlayerView`.
 * `PlayerView` reads its `surface_type` only from an XML attribute, so it can
 * never switch between `SurfaceView` and `TextureView` at runtime — which is
 * exactly what [VideoSurfaceConfig.renderMode] promises. The hierarchy
 * below assembles the pieces `PlayerView` composes internally — aspect-ratio
 * frame, video view, subtitle view, and `PlayerControlView` when
 * [VideoSurfaceConfig.showNativeControls] asks for it — without inheriting the
 * XML-only surface type that makes `PlayerView` itself unusable here.
 */
@OptIn(UnstableApi::class)
@Composable
actual fun NativeVideoSurface(
    player: MediaPlayer<*, *>,
    config: VideoSurfaceConfig,
    modifier: Modifier,
) {
    // Renders nothing rather than crashing for a player with no ExoPlayer behind
    // it (a fake in a preview or test).
    val exoPlayer = player.exoPlayerOrNull() ?: return

    val lifecycleOwner = LocalLifecycleOwner.current

    // Not the host view's own `keepScreenOn` flag, which is what this used to
    // drive off ExoPlayer's onIsPlayingChanged: the modifier sets the same flag
    // on Compose's view through a ref count, so the request survives the
    // `key()` rebuild below and reads the same status every other platform does.
    val surfaceModifier = modifier.then(keepScreenOnModifier(player, config))

    // SurfaceView and TextureView are different views, not a mode on one view,
    // so changing the type has to rebuild the hierarchy. key() does that; an
    // update() branch could not.
    key(config.renderMode) {
        AndroidView(
            modifier = surfaceModifier,
            factory = { context ->
                VideoSurfaceHost(context, config.renderMode).apply { attach(exoPlayer) }
            },
            update = { host ->
                // AndroidView may hand back a detached instance it reused.
                host.attach(exoPlayer)
                host.apply(config)
            },
            onRelease = { host ->
                // Detach only. AndroidVideoPlayer owns the engine and outlives
                // this view.
                host.detach()
            },
        )
    }

    // Pause on ON_PAUSE so audio does not continue over another screen. Routed
    // as a PlaybackAction, not straight to ExoPlayer, so the state machine and
    // the interruption engine observe the pause. The engine is deliberately
    // *not* released here — that belongs to whoever owns it.
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) player.onAction(PlaybackAction.Pause)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/**
 * The video output: an aspect-ratio-aware frame holding the video view with the
 * native subtitle view stacked on top.
 *
 * Owns the ExoPlayer listener that keeps the frame's aspect ratio and the
 * subtitle cues current. It never releases the player — [detach] only unhooks
 * rendering.
 */
@UnstableApi
private class VideoSurfaceHost(
    context: Context,
    renderMode: VideoRenderMode,
) : FrameLayout(context) {

    /**
     * Scales and letterboxes the video view. A separate child rather than this
     * class's own superclass because `AspectRatioFrameLayout` is final — and the
     * composition is what `PlayerView` does internally anyway, so the subtitle
     * view below can sit outside it and stay unscaled.
     */
    private val contentFrame = AspectRatioFrameLayout(context)

    /** `SurfaceView` or `TextureView`; fixed for this instance's lifetime. */
    private val videoView: View = when (renderMode) {
        VideoRenderMode.DIRECT -> SurfaceView(context)
        VideoRenderMode.TEXTURE -> TextureView(context)
    }

    private val subtitleView = SubtitleView(context)

    /**
     * Media3's stock transport UI, used only when
     * [VideoSurfaceConfig.showNativeControls] is on.
     *
     * The standalone `PlayerControlView` rather than switching the whole surface
     * to `PlayerView`: `PlayerView` would drag its XML-only `surface_type` back
     * in and cost the `SurfaceView`/`TextureView` choice this class exists to
     * provide. This is the same widget `PlayerView` embeds, minus that coupling.
     *
     * Always in the hierarchy but `GONE` unless asked for — it binds to the
     * player in [attach], so toggling the config is a visibility change rather
     * than a re-attach.
     */
    private val controlView = PlayerControlView(context)

    private var attachedPlayer: ExoPlayer? = null

    private val listener = object : Player.Listener {

        /**
         * Letterboxing needs the video's real shape, which is only known once
         * decoding starts — and changes on an adaptive stream's first quality
         * switch. `pixelWidthHeightRatio` covers anamorphic content, where the
         * coded size is not the display size.
         */
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            contentFrame.setAspectRatio(
                if (videoSize.height == 0 || videoSize.width == 0) {
                    0f // unset: let the frame take whatever size it is given
                } else {
                    videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            )
        }

        /**
         * Feeds the native subtitle view. The same cues reach shared code via
         * `AndroidVideoPlayer` — the two are independent, which is what lets
         * `showNativeSubtitles` be a pure visibility switch here.
         */
        override fun onCues(cueGroup: CueGroup) {
            subtitleView.setCues(cueGroup.cues)
        }
    }

    init {
        contentFrame.addView(
            videoView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        addView(
            contentFrame,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        // Above the video, below everything Compose draws over this view. Kept
        // outside contentFrame so CROP/FILL never scale or clip the captions.
        addView(
            subtitleView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        // Top of the native stack, so the transport sits over the captions the
        // way it does in PlayerView.
        addView(
            controlView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        controlView.visibility = View.GONE

        // Tap-to-toggle, the behaviour PlayerView implements for itself. Only
        // armed while native controls are on — otherwise Compose's own tap layer
        // owns the gesture and this must not compete for it.
        setOnClickListener {
            if (controlView.isFullyVisible) controlView.hide() else controlView.show()
        }
        isClickable = false
    }

    fun attach(player: ExoPlayer) {
        if (attachedPlayer === player) return
        detach()
        when (videoView) {
            is SurfaceView -> player.setVideoSurfaceView(videoView)
            is TextureView -> player.setVideoTextureView(videoView)
        }
        player.addListener(listener)
        controlView.player = player
        attachedPlayer = player
        // Seed from current state; the listener only fires on *changes*, so a
        // player that is already playing mid-rotation would otherwise never
        // report its size or cues to a freshly built host.
        listener.onVideoSizeChanged(player.videoSize)
        subtitleView.setCues(player.currentCues.cues)
    }

    fun detach() {
        val player = attachedPlayer ?: return
        player.removeListener(listener)
        player.clearVideoSurface()
        controlView.player = null
        attachedPlayer = null
    }

    fun apply(config: VideoSurfaceConfig) {
        contentFrame.resizeMode = config.scalingMode.toResizeMode()
        setBackgroundColor(config.backgroundColor.toArgb())
        subtitleView.visibility = if (config.showNativeSubtitles) View.VISIBLE else View.GONE

        // Only intercept touches while the native transport is the thing that
        // needs them; Compose draws its own tap layer over this otherwise.
        isClickable = config.showNativeControls
        if (config.showNativeControls) {
            controlView.show()
        } else {
            controlView.hide()
            controlView.visibility = View.GONE
        }
    }
}

/**
 * Digs the ExoPlayer instance out of the engine for rendering.
 *
 * `VideoPlayer()` returns a [KMediaManager] that delegates to the platform
 * player, so the handle is one level down; the direct case is handled too for
 * callers who built an [AndroidVideoPlayer] without the manager.
 */
private fun MediaPlayer<*, *>.exoPlayerOrNull(): ExoPlayer? = when (this) {
    is AndroidVideoPlayer -> exoPlayer
    is KMediaManager<*, *> -> (player as? AndroidVideoPlayer)?.exoPlayer
    else -> null
}

/** FIT letterboxes, CROP zooms and clips, FILL stretches. */
@OptIn(UnstableApi::class)
private fun VideoScalingMode.toResizeMode(): Int = when (this) {
    VideoScalingMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    VideoScalingMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    VideoScalingMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
}
