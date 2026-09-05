package kplayer.ui.model

/**
 * How the video frame is fitted into the surface bounds.
 *
 * Each platform maps this to its own native concept:
 * - Android: `PlayerView.resizeMode` (`RESIZE_MODE_FIT` / `ZOOM` / `FILL`)
 * - iOS: `AVPlayerLayer.videoGravity` (`ResizeAspect` / `ResizeAspectFill` / `Resize`)
 *
 * In `kplayer.ui.model` because it is a plain value with a rule attached
 * ([next]), shared by every UI that offers a scaling button — the Compose
 * surfaces in this module and anything a Swift consumer builds over them.
 */
enum class VideoScalingMode {
    /** Letterbox — whole frame visible, aspect ratio preserved. */
    FIT,

    /** Crop — fills the surface, aspect ratio preserved, edges clipped. */
    CROP,

    /** Stretch — fills the surface, aspect ratio *not* preserved. */
    FILL;

    /** Cycles through the modes, for a single "toggle scaling" button. */
    fun next(): VideoScalingMode = entries[(ordinal + 1) % entries.size]
}
