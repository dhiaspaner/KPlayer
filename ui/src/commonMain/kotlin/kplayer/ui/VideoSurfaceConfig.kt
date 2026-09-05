package kplayer.ui

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import kplayer.ui.model.VideoScalingMode

/**
 * How decoded frames reach the screen.
 *
 * The trade-off is the same everywhere even though only some platforms let you
 * pick: frames can go **straight to the system compositor**, which is cheap and
 * can stay in a protected path, or **through a texture the UI draws**, which
 * costs a copy but makes the video an ordinary participant in your layout.
 *
 * Not a cosmetic choice. Picking wrong shows up either as a power and thermal
 * cost on long playback, or as a visual bug — video punching through a rounded
 * corner, ignoring an animation — that looks like a Compose problem and is not.
 */
enum class VideoRenderMode {

    /**
     * Frames handed straight to the system compositor.
     *
     * The default, and the right answer for ordinary playback: cheapest,
     * lowest-latency, and the only mode that can keep a **secure DRM path**
     * (Widevine L1 and equivalents).
     *
     * The cost is that the video is not part of your UI's drawing pass. It
     * ignores alpha, rotation and scale; it does not clip to rounded corners;
     * and it can flicker or z-fight during animated or shared-element
     * transitions. Anything drawn *over* it still works — the control overlay is
     * unaffected.
     *
     * Android renders this as a `SurfaceView`.
     */
    DIRECT,

    /**
     * Frames drawn into a texture that the UI composites like any other content.
     *
     * Choose it when the player itself is animated, clipped or transformed — a
     * rounded-corner card, a shrinking mini-player, a transition into
     * fullscreen. It behaves like the rest of your layout because it is part of
     * it.
     *
     * The cost: an extra GPU copy per frame (measurably more power and heat on
     * long playback), and **no secure DRM path**, so protected content will fail
     * or fall back to a lower resolution.
     *
     * Android renders this as a `TextureView`.
     */
    TEXTURE;

    companion object {

        /**
         * The mode every default is expressed in terms of — one constant rather
         * than the same literal repeated at each entry point.
         *
         * [VideoSurfaceConfig], [PlayerUiStateHolder] and
         * [rememberPlayerUiStateHolder] all defer to this, so they cannot drift
         * apart. They did: the holder's constructor said one thing and its
         * `remember` factory said another, which made the same call site mean two
         * different things depending on which one you reached for.
         *
         * [DIRECT] because it is the right answer for ordinary playback — cheapest,
         * lowest latency, and the only mode that can keep a secure DRM path. Choose
         * the other **by configuration** rather than by editing this: pass
         * `VideoSurfaceConfig(renderMode = TEXTURE)` for a player that needs it, or
         * call `setRenderMode` on the holder to switch while playing. Changing this
         * constant is the library-wide override, and costs a copy per frame plus
         * DRM for every player that did not ask.
         */
        val Default: VideoRenderMode = DIRECT
    }
}

/**
 * Everything about how the *video itself* is rendered, as one value.
 *
 * Deliberately separate from [PlayerUiStateHolder]: this describes the native
 * surface, is normally decided once by the calling screen, and is `null`-free
 * plain data. The holder describes chrome the user can change at runtime. The
 * two meet only at [scalingMode] — see the note there.
 *
 * Not every field applies to every platform, because the platforms genuinely
 * differ. Each one below says what it does where; nothing is silently ignored
 * without being documented as such.
 *
 * ```kotlin
 * // A player inside an animated, rounded-corner card with app-drawn captions.
 * VideoSurfaceConfig(
 *     renderMode = VideoRenderMode.TEXTURE,
 *     targetAspectRatio = 16f / 9f,
 *     showNativeSubtitles = false,
 * )
 * ```
 */
@Stable
data class VideoSurfaceConfig(

    /**
     * How the frame is fitted into the surface bounds.
     *
     * This is the **initial** value only. [FlexibleVideoPlayer] seeds its
     * [PlayerUiStateHolder] from it and the holder owns it afterwards, so a
     * `ScalingModeButton` tap is not overwritten on the next recomposition. Pass
     * your own holder if you want to drive the mode from outside the player.
     */
    val scalingMode: VideoScalingMode = VideoScalingMode.FIT,

    /**
     * Fixes the player's own aspect ratio, e.g. `16f / 9f`.
     *
     * Applied as a Compose layout constraint on the player box, so it holds
     * before any media has loaded — which is the point: without it a player
     * whose media is still buffering has no intrinsic size and the surrounding
     * layout jumps once the first frame arrives.
     *
     * `null` means the player takes whatever size [modifier] gives it.
     */
    val targetAspectRatio: Float? = null,

    /**
     * Fills the surface wherever video does not: behind letterbox bars, and for
     * the whole area while idle or buffering.
     */
    val backgroundColor: Color = Color.Black,

    /**
     * Holds the screen awake **while playing**.
     *
     * Deliberately not "while the player exists": a paused player has no claim
     * on the user's battery, so every platform drops the request as soon as
     * playback stops.
     *
     * Held through Compose's own `Modifier.keepScreenOn()` — `View.keepScreenOn`
     * on Android, `UIApplication.idleTimerDisabled` on iOS — which ref-counts
     * every node that asks, so the request nests with whatever else the app is
     * doing rather than clobbering it. **Web and desktop honour none of this**:
     * Compose has no screen-on hook on either, and this library adds no wake
     * lock of its own.
     */
    val keepScreenOn: Boolean = true,

    /**
     * Whether the platform draws subtitles itself.
     *
     * Set it to `false` and render `activeSubtitle` from `contentOverlay` to
     * draw captions in Compose instead — styled, positioned and animated by the
     * app rather than by the OS. Subtitles get no dedicated parameter on
     * [FlexibleVideoPlayer]; they are simply the most common content overlay.
     *
     * The two platforms reach this differently, and it is worth knowing which
     * you are on. Android decodes the text track either way, so this just hides
     * the native `SubtitleView` and the cues keep flowing to
     * [kplayer.core.state.PlaybackState.activeSubtitle]. iOS makes it an either/or:
     * routing cues to code *replaces* AVFoundation's rendering, so
     * `showNativeSubtitles = true` means `activeSubtitle` stays `null` even
     * while captions are visible.
     */
    val showNativeSubtitles: Boolean = true,

    /**
     * Lets the **platform** draw the transport controls instead of Compose.
     *
     * Android shows a Media3 `PlayerControlView`, iOS turns
     * `AVPlayerViewController.showsPlaybackControls` back on. You get each
     * platform's own play/pause, scrubber, timing, and — on iOS — the AirPlay,
     * PiP and subtitle-track buttons, all matching the OS version the user is
     * actually running.
     *
     * **This replaces the Compose overlay entirely.** When it is `true`,
     * [FlexibleVideoPlayer] renders neither `controlsOverlay` nor its
     * tap-to-toggle layer, and [PlayerUiStateHolder.controlsVisible] stops
     * driving anything — the native controller owns its own visibility and
     * auto-hide. Two stacked control sets would fight for the same taps, so this
     * is an either/or rather than a layering.
     *
     * Default `false`: a replaceable Compose overlay is the reason this library
     * exists. Reach for this when you want the platform's stock look for free,
     * or as a reference to compare custom chrome against.
     *
     * Independent of [showNativeSubtitles] — captions and controls are separate
     * decisions, and a Compose subtitle overlay still renders underneath native
     * controls.
     */
    val showNativeControls: Boolean = false,

    /**
     * How frames reach the screen; see [VideoRenderMode] for the trade-off.
     *
     * The **initial** value only, exactly like [scalingMode]: [FlexibleVideoPlayer]
     * seeds its [PlayerUiStateHolder] from it and the holder owns it afterwards,
     * so a `setRenderMode` from your own UI is not overwritten on the next
     * recomposition. Drive it from the holder when you want to switch — into
     * [VideoRenderMode.TEXTURE] for a blur or a transition, back to
     * [VideoRenderMode.DIRECT] for ordinary playback.
     *
     * Honoured on Android, where the two modes are genuinely different views
     * (`SurfaceView` vs `TextureView`), and on iOS, where they are two different
     * renderers: [VideoRenderMode.DIRECT] hosts an `AVPlayerViewController`
     * through UIKit interop, and [VideoRenderMode.TEXTURE] pulls decoded frames
     * from an `AVPlayerItemVideoOutput` and draws them as ordinary Compose
     * content.
     *
     * The iOS distinction is sharper than Android's, because UIKit interop puts
     * the video in its own layer *above* the Compose scene rather than inside the
     * drawing pass. Under [VideoRenderMode.DIRECT] the video cannot be blurred,
     * clipped to a rounded corner, cross-faded or drawn beneath other Compose
     * content, and no `Modifier` on the player affects it. Under
     * [VideoRenderMode.TEXTURE] it is an `Image` and all of that works — at the
     * cost of a copy per frame, and of AirPlay, Picture-in-Picture and Now
     * Playing integration, which belong to the view controller.
     *
     * [showNativeSubtitles] and [showNativeControls] are therefore ignored under
     * [VideoRenderMode.TEXTURE] on iOS: both are the view controller's, and cues
     * are routed to `activeSubtitle` for the app to draw.
     *
     * On desktop the mode is not a choice — there is no native view to hand
     * frames to, so playback is always the frame-pulling path.
     */
    val renderMode: VideoRenderMode = VideoRenderMode.Default,

    /**
     * Allows the system to move playback into Picture-in-Picture.
     *
     * **iOS only.** Maps to `AVPlayerViewController.allowsPictureInPicturePlayback`,
     * which is genuinely a property of the video surface.
     *
     * Android has no equivalent here, and this flag does nothing there. PiP on
     * Android is entered by the *Activity* (`enterPictureInPictureMode`) and
     * declared in the manifest (`android:supportsPictureInPicture`), neither of
     * which a view can reach or should try to. Drive it from your Activity and
     * observe [PlayerUiStateHolder.isFullscreen] if you want the two coordinated.
     */
    val allowsPip: Boolean = true,
)
