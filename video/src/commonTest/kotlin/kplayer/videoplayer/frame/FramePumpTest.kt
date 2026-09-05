package kplayer.videoplayer.frame

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When the pump copies — the rule both Apple engines now share.
 *
 * Worth testing here rather than per platform because it is the same decision on
 * both, it is not obvious, and getting it wrong shows up as a black surface at
 * exactly the moment the user switches to it. Nothing below touches AVFoundation:
 * the source is a fake, so this runs on every target.
 */
class FramePumpTest {

    /** Stands in for an `AVPlayerItemVideoOutput`. */
    private class FakePixelSource : PixelSource {
        var attached = true
        var hasNew = false
        /** Null means the decoder has nothing for this time — a real, ordinary answer. */
        var frameToGive: Int? = 1

        var ensureAttachedCalls = 0
        var hasNewFrameCalls = 0
        var publishCalls = 0

        override fun ensureAttached(): Boolean {
            ensureAttachedCalls++
            return attached
        }

        override fun hasNewFrame(): Boolean {
            hasNewFrameCalls++
            return hasNew
        }

        override fun publishCurrentFrame(into: FrameBuffer): Boolean {
            publishCalls++
            val value = frameToGive ?: return false
            into.publish(width = 2, height = 2, rowBytes = 8) { it.fill(value.toByte()) }
            return true
        }
    }

    private fun pump(source: FakePixelSource, frames: FrameBuffer = FrameBuffer()) =
        FramePump(frames, source) to frames

    /**
     * The reported symptom, as a unit test. A paused player's time does not
     * advance, so `hasNewPixelBufferForItemTime:` says no — and a pump gated
     * purely on it never draws the frame that is already on screen.
     */
    @Test
    fun `the first frame is published even when nothing is new`() {
        val source = FakePixelSource().apply { hasNew = false }
        val (pump, frames) = pump(source)

        pump.tick()

        assertNotNull(
            frames.latest(),
            "a surface attached to a paused player must still show the current frame",
        )
    }

    @Test
    fun `once a frame exists the cheap gate takes over`() {
        val source = FakePixelSource().apply { hasNew = false }
        val (pump, _) = pump(source)
        pump.tick()
        val publishesAfterFirst = source.publishCalls

        repeat(5) { pump.tick() }

        // At 4K a frame is 33 MB; re-copying one the renderer already has, sixty
        // times a second, is the thing the gate exists to prevent.
        assertEquals(
            publishesAfterFirst,
            source.publishCalls,
            "nothing new should mean no copy once a frame is on screen",
        )
    }

    @Test
    fun `a new frame is taken`() {
        val source = FakePixelSource()
        val (pump, frames) = pump(source)
        pump.tick()

        source.hasNew = true
        source.frameToGive = 2
        pump.tick()

        val frame = assertNotNull(frames.latest())
        assertTrue(frame.pixels.all { it == 2.toByte() })
    }

    /**
     * Seeking while paused: the picture has to move, but the player's time is not
     * advancing so nothing is "new" and the cheap gate would hold the old frame
     * forever.
     */
    @Test
    fun `a requested refresh forces a copy with nothing new`() {
        val source = FakePixelSource()
        val (pump, frames) = pump(source)
        pump.tick()
        source.hasNew = false
        source.frameToGive = 7

        pump.requestRefresh()
        pump.tick()

        assertTrue(assertNotNull(frames.latest()).pixels.all { it == 7.toByte() })
    }

    @Test
    fun `a refresh applies once and then stops forcing`() {
        val source = FakePixelSource()
        val (pump, _) = pump(source)
        pump.tick()
        pump.requestRefresh()
        pump.tick()
        val publishes = source.publishCalls

        repeat(3) { pump.tick() }

        assertEquals(publishes, source.publishCalls, "the refresh should not latch on")
    }

    /**
     * A forced copy that finds nothing must stay forced. Otherwise a seek landing
     * between decoded frames is silently dropped and the surface keeps showing
     * the pre-seek picture.
     */
    @Test
    fun `a refresh that finds nothing is retried`() {
        val source = FakePixelSource()
        val (pump, _) = pump(source)
        pump.tick()

        source.hasNew = false
        source.frameToGive = null // decoder not ready for this time yet
        pump.requestRefresh()
        pump.tick()
        val publishesWhileEmpty = source.publishCalls

        source.frameToGive = 9
        pump.tick()

        assertTrue(
            source.publishCalls > publishesWhileEmpty,
            "the refresh should survive a tick that produced nothing",
        )
    }

    @Test
    fun `an unattached source ends the tick`() {
        val source = FakePixelSource().apply { attached = false }
        val (pump, frames) = pump(source)

        pump.tick()

        assertNull(frames.latest())
        // Not even asked: there is no output to ask.
        assertEquals(0, source.hasNewFrameCalls)
        assertEquals(0, source.publishCalls)
    }

    @Test
    fun `attachment is rechecked every tick`() {
        val source = FakePixelSource()
        val (pump, _) = pump(source)

        repeat(3) { pump.tick() }

        // The item can be replaced underneath — a load while playing orphans the
        // previous attachment — so this cannot be done once at startup.
        assertEquals(3, source.ensureAttachedCalls)
    }

    @Test
    fun `reset drops a pending refresh`() {
        val source = FakePixelSource()
        val (pump, _) = pump(source)
        pump.tick()
        val publishes = source.publishCalls

        pump.requestRefresh()
        pump.reset()
        source.hasNew = false
        pump.tick()

        assertEquals(publishes, source.publishCalls)
    }
}
