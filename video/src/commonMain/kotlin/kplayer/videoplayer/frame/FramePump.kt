package kplayer.videoplayer.frame

import kotlin.concurrent.Volatile

/**
 * The platform half of a pull-based frame pump — an `AVPlayerItemVideoOutput`, on
 * both Apple platforms.
 *
 * The AVFoundation calls themselves cannot be shared: iOS reaches them through
 * Kotlin/Native cinterop and desktop through `objc_msgSend` over JNA, which are
 * different runtimes with nothing in common at the call site. What *can* be
 * shared is the decision of **when to copy**, which is where the interesting
 * behaviour — and the bug — lives. That is [FramePump]; this is the seam it
 * drives.
 *
 * Implementations do no sequencing of their own: they attach, answer questions,
 * and copy when told.
 */
internal interface PixelSource {

    /**
     * Makes sure an output is attached to the item currently being played.
     *
     * Called every tick because the item can be replaced underneath — a load
     * while playing orphans the previous attachment, since outputs belong to the
     * `AVPlayerItem` rather than to the player.
     *
     * @return `false` when there is nothing to pull from yet (no item, or the
     *   output could not be created), which ends the tick.
     */
    fun ensureAttached(): Boolean

    /**
     * Whether a frame newer than the one already taken is available —
     * `hasNewPixelBufferForItemTime:`.
     *
     * Cheap, and the reason a tick that has nothing new costs one message send
     * instead of a whole-frame copy.
     */
    fun hasNewFrame(): Boolean

    /**
     * Copies the frame at the current time into [into], whatever
     * [hasNewFrame] would have said.
     *
     * @return `true` if a frame was published. `false` is ordinary — the decoder
     *   may simply have nothing for this time yet — and the pump will ask again.
     */
    fun publishCurrentFrame(into: FrameBuffer): Boolean
}

/**
 * Decides when a [PixelSource] is asked to copy.
 *
 * Shared by the iOS and desktop AVFoundation engines so the two cannot disagree
 * about it, and unit-tested against a fake source — which is the point, because
 * the rule below is not obvious and was wrong once already.
 *
 * ### The rule
 *
 * Steady state is gated on [PixelSource.hasNewFrame]: at 4K a frame is 33 MB, so
 * copying one the renderer already has is pure waste.
 *
 * But that gate alone leaves the surface **black at exactly the moment it
 * appears**. `hasNewPixelBufferForItemTime:` answers "is there something newer
 * than what you last took?", and for a paused player — or one that has loaded but
 * never played — time is not advancing, so the honest answer is no and nothing is
 * ever published. Switching render mode mid-playback, or attaching a surface to a
 * paused player, would show nothing until the user pressed play.
 *
 * So the pump forces a copy whenever it has no frame to show, and whenever
 * something has invalidated the one it has ([requestRefresh]). Both are
 * self-clearing: once a frame lands, the cheap gate takes over again.
 */
internal class FramePump(
    private val frames: FrameBuffer,
    private val source: PixelSource,
) {

    /**
     * Set when the frame on screen is known to be stale even though no new one
     * has been produced — a seek while paused, or a fresh item.
     *
     * `@Volatile` because it is set from whichever thread issued the seek and
     * read on the pump's thread.
     */
    @Volatile
    private var refreshRequested = false

    /**
     * Forces the next tick to copy regardless of [PixelSource.hasNewFrame].
     *
     * Seeking while paused is the case that needs it: the picture must move to
     * the new position, but the player's time is not advancing, so nothing is
     * "new" and the cheap gate would hold the old frame forever.
     */
    fun requestRefresh() {
        refreshRequested = true
    }

    /** Drops any pending refresh — for a pump being stopped or re-pointed. */
    fun reset() {
        refreshRequested = false
    }

    /**
     * One tick. Safe to call at any cadence; the source decides whether there is
     * anything to give.
     */
    fun tick() {
        if (!source.ensureAttached()) return

        // Nothing on screen yet is the common case for "I just attached a
        // surface to a player that is not moving" — the whole reason this is not
        // simply `if (!hasNewFrame()) return`.
        val forced = refreshRequested || frames.latest() == null
        if (!forced && !source.hasNewFrame()) return

        if (source.publishCurrentFrame(frames)) {
            // Cleared only on success: a forced copy that found nothing must stay
            // forced, or a seek landing between frames would be dropped.
            refreshRequested = false
        }
    }
}
