package kplayer.videoplayer.frame

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one place a frame producer records why it produced nothing.
 *
 * Every engine that pulls pixels needs the same three properties, and all three
 * were re-derived per engine before this existed:
 *
 * - **First one wins.** A pump that fails on every tick fails sixty times a
 *   second; the first reason is the useful one and the rest are noise.
 * - **Logged as well as kept**, so a black surface says why in the console
 *   without a debugger attached.
 * - **Observable**, which is the half that was missing. A `@Volatile String?`
 *   can only be read by someone who already suspects there is a problem and
 *   knows where to look; a [StateFlow] can be put on screen — see `:ui`'s
 *   `rememberVideoFrameDiagnostics` and the sample's frame-output panel.
 *
 * @param log where the first failure goes. Defaulted to `println` because that
 *   is what desktop wants; iOS passes `NSLog` so the line lands in the device
 *   console with everything else.
 */
internal class FrameOutputFailures(private val log: (String) -> Unit = { println(it) }) {

    private val state = MutableStateFlow<String?>(null)

    /** The first failure since the last [clear], or `null` while all is well. */
    val failure: StateFlow<String?> = state.asStateFlow()

    fun report(reason: String) {
        if (state.value != null) return
        state.value = reason
        // Same `kplayer/frames` tag `:ui` logs the render half under, so one grep
        // finds the whole path — the decoder saying it produced nothing and the
        // renderer saying what it did with what it got.
        log("kplayer/frames: output failed: $reason")
    }

    /**
     * Forgets the current failure.
     *
     * Called when the item changes: a reason that belonged to the previous media
     * would otherwise sit on screen accusing the new one, and first-wins would
     * suppress whatever the new item actually hits.
     */
    fun clear() {
        state.value = null
    }
}
