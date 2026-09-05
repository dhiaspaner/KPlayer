package kplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kplayer.core.MediaPlayer
import kplayer.videoplayer.frame.VideoFrameSource

/**
 * Everything that can be known about why [VideoRenderMode.TEXTURE] is or is not
 * showing a picture.
 *
 * The drawn surface has four places to fail and no error path out of any of them
 * — frames are dropped rather than reported, because one missing frame is not a
 * playback failure — so "black rectangle" is the only symptom all four share.
 * This separates them:
 *
 * | Symptom | Diagnosis |
 * |---|---|
 * | [frameSourceAvailable] is false | this engine hands frames to the compositor instead (Android, the DIRECT path, Media Foundation) — nothing here applies |
 * | [outputEnabled] is false | no surface asked for pixels; the render mode is probably not `TEXTURE` |
 * | [outputFailure] is set | the decoder side gave up, and says why |
 * | [decoded] is null and nothing failed | the decoder has simply not produced a frame yet — a load in flight, or a source that never opened |
 * | [decoded] advances but [drawnFrames] does not | frames arrive and the renderer is not consuming them |
 * | [renderFailure] is set | frames arrive and Skia refuses to make a bitmap of them |
 *
 * The last one is not hypothetical: a test JVM with no skiko natives failed every
 * `makeRaster` in its static initialiser, and the only visible symptom was a null
 * bitmap.
 *
 * @see rememberVideoFrameDiagnostics
 */
data class VideoFrameDiagnostics(
    /** Whether the player's engine produces frames in memory at all. */
    val frameSourceAvailable: Boolean = false,
    /** Whether a surface has asked the engine to produce them. */
    val outputEnabled: Boolean = false,
    /** The decoder side's first failure; see [VideoFrameSource.frameOutputFailure]. */
    val outputFailure: String? = null,
    /** The most recent frame the engine published, or `null` if there has been none. */
    val decoded: DecodedFrameInfo? = null,
    /** How many frames the drawn surface has turned into a bitmap. */
    val drawnFrames: Long = 0,
    /** The renderer's first bitmap-conversion failure. */
    val renderFailure: String? = null,
) {

    /** True when a picture should be on screen — everything worked. */
    val isRendering: Boolean
        get() = frameSourceAvailable && outputEnabled && drawnFrames > 0 &&
            outputFailure == null && renderFailure == null
}

/** Geometry of one published frame. [rowBytes] is padded past `width * 4` on aligned decoders. */
data class DecodedFrameInfo(
    val width: Int,
    val height: Int,
    val rowBytes: Int,
    /** Monotonic per source, so a stalled decoder is a sequence that stops moving. */
    val sequence: Long,
)

/**
 * Observes [player]'s frame path.
 *
 * The decoder half is a [StateFlow] and costs nothing to watch; the frame
 * geometry is polled, because `latestFrame()` is a plain read with no
 * notification behind it and a diagnostics panel does not need 60fps.
 *
 * ```kotlin
 * val frames = rememberVideoFrameDiagnostics(player)
 * frames.outputFailure?.let { Text("no picture: $it") }
 * ```
 *
 * Safe for any player on any platform: one with no frame path reports
 * [VideoFrameDiagnostics.frameSourceAvailable] false rather than failing.
 *
 * @param pollIntervalMs how often the latest frame is sampled.
 */
@Composable
fun rememberVideoFrameDiagnostics(
    player: MediaPlayer<*, *>,
    pollIntervalMs: Long = 500L,
): VideoFrameDiagnostics {
    val source = remember(player) { player.videoFrameSourceOrNull() }

    // Branches rather than a safe call, so neither the collect nor the poll is
    // set up for a player that has no frames to report.
    val outputFailure = if (source != null) source.frameOutputFailure.collectAsState().value else null
    val render = if (source != null) {
        VideoFrameRenderReports.of(source).collectAsState().value
    } else {
        VideoFrameRenderReport()
    }

    val decoded by produceState<DecodedFrameInfo?>(null, source, pollIntervalMs) {
        if (source == null) return@produceState
        while (true) {
            value = source.latestFrame()?.let {
                DecodedFrameInfo(it.width, it.height, it.rowBytes, it.sequence)
            }
            delay(pollIntervalMs)
        }
    }

    // Logged as well as returned, so a failure is in the console for a run nobody
    // was watching this panel during — and so it is *reachable*: the engine logs
    // its reason at the moment it happens, which on a load that fails before any
    // surface attaches is long before anything could have observed the flow.
    // Keyed on the value, so this is one line per distinct failure, not per frame.
    if (source != null) {
        LaunchedEffect(source, outputFailure) {
            if (outputFailure != null) logFrames("output failure: $outputFailure")
        }
        LaunchedEffect(source, render.failure) {
            if (render.failure != null) logFrames("render failure: ${render.failure}")
        }
    }

    return VideoFrameDiagnostics(
        frameSourceAvailable = source != null,
        outputEnabled = render.outputEnabled,
        outputFailure = outputFailure,
        decoded = decoded,
        drawnFrames = render.drawnFrames,
        renderFailure = render.failure,
    )
}

/**
 * The frames a player decodes into memory, or `null` for one that renders through
 * the system compositor instead.
 *
 * Not derivable in common code: the frame path hangs off the platform backend
 * (`DesktopVideoPlayer`, `IosVideoPlayer`), and Android and the web have none at
 * all — a `SurfaceView` and a `<video>` element both keep their pixels to
 * themselves.
 */
internal expect fun MediaPlayer<*, *>.videoFrameSourceOrNull(): VideoFrameSource?

/**
 * One tagged line about the frame path, on every platform.
 *
 * `println` rather than a per-platform logger: it reaches stdout on desktop, the
 * device console on iOS (Kotlin/Native routes it there), logcat on Android and
 * the browser console on the web, which is every place someone debugging a black
 * surface would look. The tag is what makes it greppable — `frames` is the only
 * word in the line that never varies.
 *
 * Everything logged through this is an **edge**: a failure appearing, output
 * being turned on or off, the first frame landing. Nothing per-frame, ever — at
 * 60fps that is a log nobody can read and a measurable cost in the draw loop.
 */
internal fun logFrames(message: String) {
    println("kplayer/frames: $message")
}

/** What the drawing half of the pipeline has managed to do with the frames. */
internal data class VideoFrameRenderReport(
    val outputEnabled: Boolean = false,
    val drawnFrames: Long = 0,
    val failure: String? = null,
)

/**
 * Where [ComposeVideoFrameSurface] publishes what it managed to draw, keyed by the
 * source it was drawing.
 *
 * Keyed rather than global because a screen can hold several players — the reels
 * demo holds one per page — and a single set of counters would blend them into a
 * number that describes nobody. Identity keys: no `VideoFrameSource` implements
 * `equals`, and two engines are never the same source.
 *
 * Not synchronised, and does not need to be: every write comes from the surface's
 * frame loop and every read from a composition, both on the UI thread.
 */
internal object VideoFrameRenderReports {

    private val reports = mutableMapOf<VideoFrameSource, MutableStateFlow<VideoFrameRenderReport>>()

    fun of(source: VideoFrameSource): StateFlow<VideoFrameRenderReport> = flowFor(source).asStateFlow()

    fun update(source: VideoFrameSource, transform: (VideoFrameRenderReport) -> VideoFrameRenderReport) {
        val flow = flowFor(source)
        flow.value = transform(flow.value)
    }

    /**
     * Drops a source's counters when its surface leaves the composition.
     *
     * The entry is what keeps the engine reachable from this map, so forgetting it
     * is also what keeps a released player collectable.
     */
    fun forget(source: VideoFrameSource) {
        reports.remove(source)
    }

    private fun flowFor(source: VideoFrameSource): MutableStateFlow<VideoFrameRenderReport> =
        reports.getOrPut(source) { MutableStateFlow(VideoFrameRenderReport()) }
}
