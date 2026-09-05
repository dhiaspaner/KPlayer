package kplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.WebElementView
import kplayer.core.MediaPlayer
import kplayer.ui.model.VideoScalingMode
import kplayer.engine.KMediaManager
import kplayer.videoplayer.WebVideoPlayer
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.HIDDEN
import org.w3c.dom.HTMLElement
import org.w3c.dom.SHOWING
import org.w3c.dom.TextTrackMode
import org.w3c.dom.get

/**
 * Web video surface: the engine's `<video>` element, composed into the Compose
 * scene through [WebElementView].
 *
 * [WebElementView] (Compose Multiplatform's HTML interop, experimental) does the
 * layout half of the job: it parents the element into the interop container,
 * keeps its size and position in step with this node's layout bounds, and clips
 * it to whatever clips the composable — including a scroll parent. That is
 * everything an invisible element (the text field it was built for) needs.
 *
 * A `<video>` needs one thing more, and this file supplies it. Compose for web
 * draws into a single canvas; the interop container is appended *after* that
 * canvas and Compose draws nothing to make room for what goes in it. Left there,
 * the element paints over the entire scene: frames appear, and every control
 * drawn over them disappears. So the two halves of a hole are made here —
 * [setBelowCanvas] sends the element to the far side of the canvas with a
 * negative z-index, and a `BlendMode.Clear` rect erases the canvas across this
 * node's bounds so it shows through. The canvas is created with an alpha
 * channel, so the cleared region is genuinely transparent — including through
 * whatever the app itself painted there, which on this path is the player's own
 * [VideoSurfaceConfig.backgroundColor] moving from Compose onto the element.
 *
 * That ordering is what makes the shared chrome work unchanged in a browser:
 * everything drawn *before* this node (the player's own background, whatever the
 * app painted behind it) is erased, and everything drawn *after* — the content
 * and control overlays — paints over the video. Pointer events land on the
 * canvas rather than the element, so the tap layer keeps working too.
 *
 * [VideoSurfaceConfig.showNativeControls] inverts all of this: with the browser
 * drawing the transport, the element has to be on top and clickable, and
 * [FlexibleVideoPlayer] already draws no overlay in that mode.
 *
 * The element itself belongs to `HtmlVideoEngine` and outlives this composable;
 * like the Android and iOS actuals, this only borrows it for rendering. Compose
 * detaches it from the DOM on release, and re-inserts it if the surface
 * recomposes into a new node, so playback is unaffected.
 *
 * Of [config], everything except [VideoSurfaceConfig.allowsPip] and
 * [VideoSurfaceConfig.renderMode] maps onto something real — see [applyConfig].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun NativeVideoSurface(
    player: MediaPlayer<*, *>,
    config: VideoSurfaceConfig,
    modifier: Modifier,
) {
    // Renders nothing rather than crashing for a player with no <video> behind
    // it (a fake in a preview or test).
    val videoElement = player.videoElementOrNull() ?: return

    // Which of the two layerings below this surface runs in. Native controls are
    // the only reason to keep the element on top, and FlexibleVideoPlayer draws
    // no overlay in that mode, so the two are never both needed.
    val belowCanvas = !config.showNativeControls

    // Inert on web: Compose's keepScreenOn has no binding to the Screen Wake Lock
    // API behind it. Applied anyway so all four surfaces carry the request the
    // same way, and so it lights up if a binding lands.
    val surfaceModifier = modifier.then(keepScreenOnModifier(player, config))
    Box {
        WebElementView(
            factory = { videoElement },
            modifier = surfaceModifier.then(
                if (belowCanvas) Modifier.drawBehind { drawRect(Color.Transparent, blendMode = BlendMode.Clear) }
                else Modifier
            ),
            update = {
                videoElement.applyConfig(config)
                videoElement.setBelowCanvas(belowCanvas)
            },
            // Detach only. WebVideoPlayer owns the element and outlives this view,
            // so the styling is dropped rather than the element torn down; leaving
            // stale sizing on it would misrender the next surface to borrow it.
            onRelease = { videoElement.clearLayoutStyles() },
        )

        // `update` can run before Compose has parented the element, and the wrapper
        // it writes to is created by the interop container rather than by us. This
        // re-applies once the element is definitely placed.
        LaunchedEffect(videoElement, belowCanvas) { videoElement.setBelowCanvas(belowCanvas) }
    }
}

/**
 * Puts the element behind Compose's canvas, or back in front of it.
 *
 * This is the half of the layering that CSS has to do, and it is why the
 * composable above pairs it with a `BlendMode.Clear` rect. Compose's interop
 * container appends elements *after* the canvas and draws nothing to make room
 * for them, so an element left where it lands paints over the whole scene:
 * frames are visible, but every control drawn over them is not. A negative
 * z-index sends it to the other side of the canvas, where the cleared rect is
 * what lets it show through.
 *
 * Two details make it work, and both are load-bearing:
 *
 *  - **The z-index goes on the wrapper** Compose puts around the element, not on
 *    the element itself. The wrapper carries the `clip-path` that keeps the video
 *    inside its scroll parent, and `clip-path` opens a stacking context — so a
 *    z-index set inside it can only reorder against its siblings, never against
 *    the canvas.
 *  - **Compose's container is made a stacking context** with `isolation:
 *    isolate`. Without one, "behind the canvas" means the negative layer of the
 *    *page's* root stacking context, which is also behind the `<body>`
 *    background — so a page with any background colour at all (the sample's is
 *    black) hides the video completely. Isolating the container confines the
 *    negative layer to it: below the canvas, above everything the page painted.
 *    It is left in place when the element goes back on top, since creating a
 *    stacking context around Compose's own canvas changes nothing else.
 */
private fun HTMLVideoElement.setBelowCanvas(belowCanvas: Boolean) {
    val wrapper = parentElement as? HTMLElement ?: return
    if (!belowCanvas) {
        wrapper.style.removeProperty("z-index")
        return
    }
    composeContainerOrNull()?.style?.setProperty("isolation", "isolate")
    wrapper.style.setProperty("z-index", "-1")
}

/**
 * The element Compose renders its canvas into, found by walking up from the
 * video rather than from the document: `ComposeViewport` builds its scene inside
 * a shadow root, so the container is not reachable from `document` at all.
 */
private fun HTMLVideoElement.composeContainerOrNull(): HTMLElement? {
    var ancestor = parentElement as? HTMLElement
    while (ancestor != null) {
        if (ancestor.querySelector(":scope > canvas") != null) return ancestor
        ancestor = ancestor.parentElement as? HTMLElement
    }
    return null
}

/**
 * Applies the parts of [VideoSurfaceConfig] a media element can honour.
 *
 * Sizing and position are Compose's business — [WebElementView] writes them on
 * every layout pass — so this only sets what layout does not.
 */
private fun HTMLVideoElement.applyConfig(config: VideoSurfaceConfig) {
    // The element is stretched to the node's bounds, so scaling is object-fit's
    // job: it is the CSS control for how the frame fills a fixed-size box.
    style.setProperty("object-fit", config.scalingMode.toObjectFit())
    // Fills the letterbox bars, and the whole box while idle or buffering —
    // the same job the surface background does on Android and iOS.
    style.setProperty("background-color", config.backgroundColor.toCssColor())

    // Browser chrome instead of the Compose overlay. FlexibleVideoPlayer drops
    // its own controls and tap layer in this mode, but the Compose canvas is
    // still the topmost element and swallows pointer events by default, so the
    // element has to be made interactive to be usable at all.
    controls = config.showNativeControls
    style.setProperty("pointer-events", if (config.showNativeControls) "auto" else "none")

    // Captions are the browser's to draw, and the switch is per text track. Only
    // tracks the element knows about are reachable — a <track> child, or an
    // in-band track a native HLS pipeline surfaced — and `hidden` rather than
    // `disabled` is the off state so cues keep being parsed and the track can be
    // put back exactly as it was. A track the stream never selected stays off:
    // this is a visibility switch, not a track chooser.
    for (index in 0 until textTracks.length) {
        val track = textTracks[index] ?: continue
        when (track.mode.toString()) {
            "showing" -> if (!config.showNativeSubtitles) track.mode = TextTrackMode.HIDDEN
            "hidden" -> if (config.showNativeSubtitles) track.mode = TextTrackMode.SHOWING
        }
    }
}

/** Drops what [applyConfig] and Compose's layout wrote, so the element is inert again. */
private fun HTMLVideoElement.clearLayoutStyles() {
    controls = false
    for (property in listOf("object-fit", "background-color", "pointer-events")) {
        style.removeProperty(property)
    }
}

/** FIT letterboxes, CROP fills and clips, FILL stretches. */
private fun VideoScalingMode.toObjectFit(): String = when (this) {
    VideoScalingMode.FIT -> "contain"
    VideoScalingMode.CROP -> "cover"
    VideoScalingMode.FILL -> "fill"
}

/** Compose colours are sRGB floats; CSS wants 0-255 channels and a 0-1 alpha. */
private fun Color.toCssColor(): String {
    val channel = { value: Float -> (value * 255f + 0.5f).toInt().coerceIn(0, 255) }
    return "rgba(${channel(red)}, ${channel(green)}, ${channel(blue)}, $alpha)"
}

/**
 * Digs the `<video>` element out of the engine for rendering.
 *
 * `VideoPlayer()` returns a [KMediaManager] that delegates to the platform
 * player, so the handle is one level down; the direct case is handled too for
 * callers who built a [WebVideoPlayer] without the manager.
 */
private fun MediaPlayer<*, *>.videoElementOrNull(): HTMLVideoElement? = when (this) {
    is WebVideoPlayer -> videoElement
    is KMediaManager<*, *> -> (player as? WebVideoPlayer)?.videoElement
    else -> null
}
