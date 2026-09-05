package kplayer.videoplayer.frame

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [FrameBuffer]'s two jobs: never make the producer wait, and never hand the
 * consumer an array that is about to be overwritten.
 *
 * Runs on every OS — this is the one piece of the desktop frame path with no
 * native dependency at all, which is exactly why the recycling policy lives here
 * rather than inside each engine.
 */
class FrameBufferTest {

    private fun FrameBuffer.publishFilled(width: Int, height: Int, rowBytes: Int, fillByte: Byte) {
        publish(width, height, rowBytes) { it.fill(fillByte) }
    }

    @Test
    fun `there is no frame before anything is published`() {
        assertNull(FrameBuffer().latest())
    }

    @Test
    fun `a published frame carries its geometry and pixels`() {
        val buffer = FrameBuffer()

        buffer.publishFilled(width = 4, height = 2, rowBytes = 16, fillByte = 7)

        val frame = assertNotNull(buffer.latest())
        assertEquals(4, frame.width)
        assertEquals(2, frame.height)
        assertEquals(16, frame.rowBytes)
        assertEquals(32, frame.pixels.size, "should allocate rowBytes * height")
        assertTrue(frame.pixels.all { it == 7.toByte() })
    }

    /**
     * Stride is not width * 4 — hardware decoders align rows — so the buffer must
     * size from [rowBytes] and a reader must step by it.
     */
    @Test
    fun `padding in the stride is allocated for`() {
        val buffer = FrameBuffer()

        // 100px wide, but the decoder aligned rows to 512 bytes rather than 400.
        buffer.publishFilled(width = 100, height = 10, rowBytes = 512, fillByte = 1)

        assertEquals(5120, assertNotNull(buffer.latest()).pixels.size)
    }

    @Test
    fun `the sequence advances so a renderer can skip a frame it already drew`() {
        val buffer = FrameBuffer()

        buffer.publishFilled(2, 2, 8, 1)
        val first = assertNotNull(buffer.latest()).sequence
        buffer.publishFilled(2, 2, 8, 2)
        val second = assertNotNull(buffer.latest()).sequence

        assertTrue(second > first, "sequence must be monotonic: $first then $second")
    }

    @Test
    fun `latest wins and nothing queues`() {
        val buffer = FrameBuffer()

        // Ten frames published with no reader in between: a queue would hold all
        // ten, which at 4K would be 330 MB. Only the newest may survive.
        repeat(10) { i -> buffer.publishFilled(2, 2, 8, i.toByte()) }

        val frame = assertNotNull(buffer.latest())
        assertTrue(frame.pixels.all { it == 9.toByte() }, "should hold only the newest frame")
    }

    /**
     * The recycling contract. Arrays are reused — 33 MB per 4K frame makes
     * allocating per frame untenable — but a slot must not come back around while
     * the renderer could still be reading it.
     */
    @Test
    fun `arrays are recycled but not before three more frames have passed`() {
        val buffer = FrameBuffer()

        buffer.publishFilled(2, 2, 8, 0)
        val first = assertNotNull(buffer.latest()).pixels

        // The next two publishes must land in different arrays, so a renderer
        // holding `first` keeps reading intact pixels.
        buffer.publishFilled(2, 2, 8, 1)
        val second = assertNotNull(buffer.latest()).pixels
        buffer.publishFilled(2, 2, 8, 2)
        val third = assertNotNull(buffer.latest()).pixels

        assertTrue(first !== second && second !== third && first !== third, "slots must rotate")
        assertTrue(first.all { it == 0.toByte() }, "the held frame was overwritten too early")

        // The fourth returns to the first slot — by then the renderer has had two
        // whole frames to stop reading it.
        buffer.publishFilled(2, 2, 8, 3)
        assertSame(first, assertNotNull(buffer.latest()).pixels, "slot 0 should be reused on the 4th frame")
    }

    @Test
    fun `a geometry change reallocates rather than reusing a wrong-sized array`() {
        val buffer = FrameBuffer()

        buffer.publishFilled(2, 2, 8, 1)
        buffer.publishFilled(64, 64, 256, 2)

        val frame = assertNotNull(buffer.latest())
        assertEquals(256 * 64, frame.pixels.size)
    }

    @Test
    fun `clearing drops the frame`() {
        val buffer = FrameBuffer()
        buffer.publishFilled(2, 2, 8, 1)

        buffer.clear()

        // A stopped player must not keep showing the last frame of what it played.
        assertNull(buffer.latest())
    }

    @Test
    fun `a degenerate frame is ignored rather than throwing`() {
        val buffer = FrameBuffer()

        // A decoder reporting zero geometry mid-teardown must not take the pump
        // down with a negative array size.
        buffer.publish(0, 0, 0) { }
        buffer.publish(-1, 10, 40) { }

        assertNull(buffer.latest())
    }
}
