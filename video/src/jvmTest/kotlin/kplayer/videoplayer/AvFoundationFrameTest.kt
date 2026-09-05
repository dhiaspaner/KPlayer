package kplayer.videoplayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kplayer.core.event.PlaybackEvent
import kplayer.core.player.MediaEngine
import kplayer.core.state.MediaSource
import kplayer.core.state.toDisplayMessage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real decoded frames out of a real `AVPlayer`.
 *
 * The rest of the engine can be judged from the state it reports; this cannot —
 * a frame path that produces the right *shape* and garbage pixels looks identical
 * from the outside. So these assert on the bytes: correct stride relative to
 * width, and a picture that is not uniformly one value.
 *
 * Needs an actual video file, so unlike [AvFoundationVideoEngineTest] the system
 * sounds are no use. **Skips when no video is found**, which includes every
 * non-macOS machine.
 */
class AvFoundationFrameTest {

    /**
     * The Sonoma wallpapers are the one video file present on a stock macOS
     * install. They are 4K H.264, which also makes this an honest test of the
     * copy cost: 33 MB per frame.
     */
    private val videoFile: File? =
        File("/System/Library/Desktop Pictures/.wallpapers/Sonoma")
            .takeIf(File::isDirectory)
            ?.listFiles { f: File -> f.name.endsWith(".mov") }
            ?.minByOrNull(File::length)
            ?: File("/System/Library/Desktop Pictures")
                .takeIf(File::isDirectory)
                ?.listFiles { f: File -> f.name.endsWith(".mov") }
                ?.minByOrNull(File::length)

    private val isMac: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().let {
            it.contains("mac") || it.contains("darwin")
        }

    /**
     * Only readiness and failure matter here; the frames are read off the engine
     * directly. Subscribing is [CoroutineStart.UNDISPATCHED] so it has happened by
     * the time the constructor returns — the engine's flow does not replay.
     */
    private class Recorder(engine: MediaEngine) {
        @Volatile var durationMs: Long = -1
        @Volatile var error: String? = null

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                engine.events.collect { event ->
                    when (event) {
                        is PlaybackEvent.Ready -> durationMs = event.durationMs
                        is PlaybackEvent.Failure -> error = event.error.toDisplayMessage()
                        else -> Unit
                    }
                }
            }
        }

        fun stop() = scope.cancel()
    }

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun withPlayingEngine(body: (AvFoundationVideoEngine, Recorder) -> Unit) {
        if (!isMac) return println("skipped: not macOS")
        val file = videoFile ?: return println("skipped: no system video file found")

        val engine = AvFoundationVideoEngine()
        val recorder = Recorder(engine)
        try {
            assertTrue(engine.setSource(MediaSource.FilePath(file.absolutePath)))
            engine.prepare()
            assertTrue(waitFor { recorder.durationMs >= 0 }, "never became ready: ${recorder.error}")
            engine.play()
            body(engine, recorder)
        } finally {
            engine.release()
            recorder.stop()
        }
    }

    @Test
    fun `no frame is published before decoding starts`() {
        if (!isMac) return println("skipped: not macOS")

        val engine = AvFoundationVideoEngine()
        val recorder = Recorder(engine)
        try {
            // A fresh engine has no output attached and nothing decoded, and must
            // say so rather than hand back an empty frame.
            assertNull(engine.latestFrame())
        } finally {
            engine.release()
            recorder.stop()
        }
    }

    @Test
    fun `frames arrive while playing`() = withPlayingEngine { engine, _ ->
        assertTrue(
            waitFor { engine.latestFrame() != null },
            "no frame was ever decoded; pump error = ${engine.framePumpError}",
        )
    }

    @Test
    fun `a decoded frame has a plausible geometry`() = withPlayingEngine { engine, _ ->
        assertTrue(waitFor { engine.latestFrame() != null })
        val frame = assertNotNull(engine.latestFrame())

        assertTrue(frame.width in 16..8192, "implausible width ${frame.width}")
        assertTrue(frame.height in 16..8192, "implausible height ${frame.height}")

        // BGRA is 4 bytes per pixel, and the stride can only be padding *above*
        // that. Below it would mean the layout is not what we asked for.
        assertTrue(
            frame.rowBytes >= frame.width * 4,
            "stride ${frame.rowBytes} is short for a ${frame.width}px BGRA row",
        )
        assertEquals(frame.rowBytes * frame.height, frame.pixels.size)
    }

    /**
     * The assertion that separates "the pump runs" from "the pump works": a wrong
     * pixel format, an unlocked base address or a bad stride all still produce a
     * correctly-sized array. Only real pixels vary.
     */
    @Test
    fun `a decoded frame contains an actual picture`() = withPlayingEngine { engine, _ ->
        assertTrue(waitFor { engine.latestFrame() != null })
        val frame = assertNotNull(engine.latestFrame())

        val distinct = frame.pixels.take(4096).distinct().size
        assertTrue(
            distinct > 1,
            "every one of the first 4096 bytes was identical — suspect an unlocked " +
                "base address or the wrong pixel format",
        )
    }

    @Test
    fun `the frame sequence advances as playback continues`() = withPlayingEngine { engine, _ ->
        assertTrue(waitFor { engine.latestFrame() != null })
        val first = assertNotNull(engine.latestFrame()).sequence

        assertTrue(
            waitFor { (engine.latestFrame()?.sequence ?: 0) > first },
            "the frame never changed — the pump published once and stopped",
        )
    }

    /**
     * The user-reported iOS symptom, reproduced on the engine that shares the
     * same pump: switching *to* the drawn render mode must show the frame that is
     * already on screen, not wait for the next one.
     *
     * A paused player's time does not advance, so `hasNewPixelBufferForItemTime:`
     * has nothing new to report and a pump gated purely on it never publishes
     * anything — the surface stays black until the user presses play.
     */
    @Test
    fun `enabling frames on a paused player still publishes the current frame`() {
        if (!isMac) return println("skipped: not macOS")
        val file = videoFile ?: return println("skipped: no system video file found")

        val engine = AvFoundationVideoEngine()
        val recorder = Recorder(engine)
        try {
            // Frames off, exactly as they are while a view controller is drawing.
            engine.setFrameOutputEnabled(false)
            assertTrue(engine.setSource(MediaSource.FilePath(file.absolutePath)))
            engine.prepare()
            assertTrue(waitFor { recorder.durationMs >= 0 }, "never became ready")

            engine.play()
            assertTrue(waitFor { engine.currentPositionMs() > 50 }, "never started playing")
            engine.pause()
            Thread.sleep(300)

            // The user switches render mode here, with playback stopped.
            engine.setFrameOutputEnabled(true)

            assertTrue(
                waitFor(timeoutMs = 5_000) { engine.latestFrame() != null },
                "no frame while paused — the current frame never reached the surface",
            )
        } finally {
            engine.release()
            recorder.stop()
        }
    }

    /** The same, before playback has ever started. */
    @Test
    fun `enabling frames before playing publishes the first frame`() {
        if (!isMac) return println("skipped: not macOS")
        val file = videoFile ?: return println("skipped: no system video file found")

        val engine = AvFoundationVideoEngine()
        val recorder = Recorder(engine)
        try {
            engine.setFrameOutputEnabled(false)
            assertTrue(engine.setSource(MediaSource.FilePath(file.absolutePath)))
            engine.prepare()
            assertTrue(waitFor { recorder.durationMs >= 0 }, "never became ready")

            engine.setFrameOutputEnabled(true)

            assertTrue(
                waitFor(timeoutMs = 5_000) { engine.latestFrame() != null },
                "no frame for a loaded but never-played item",
            )
        } finally {
            engine.release()
            recorder.stop()
        }
    }

    /**
     * Seeking while paused moves the picture without advancing the player's time,
     * so `hasNewPixelBufferForItemTime:` reports nothing new and a pump gated
     * purely on it would keep showing the pre-seek frame. The shared pump forces
     * one copy after a seek; this proves the platform half honours it.
     */
    @Test
    fun `seeking while paused updates the drawn frame`() {
        if (!isMac) return println("skipped: not macOS")
        val file = videoFile ?: return println("skipped: no system video file found")

        val engine = AvFoundationVideoEngine()
        val recorder = Recorder(engine)
        try {
            assertTrue(engine.setSource(MediaSource.FilePath(file.absolutePath)))
            engine.prepare()
            assertTrue(waitFor { recorder.durationMs >= 0 }, "never became ready")
            assertTrue(waitFor { engine.latestFrame() != null }, "no first frame")

            val before = assertNotNull(engine.latestFrame()).sequence

            // Never played: the item is parked at zero and time is not moving.
            engine.seekTo(recorder.durationMs / 2)

            assertTrue(
                waitFor(timeoutMs = 5_000) {
                    (engine.latestFrame()?.sequence ?: 0) > before
                },
                "the frame never changed after a seek on a paused player",
            )
        } finally {
            engine.release()
            recorder.stop()
        }
    }

    /**
     * The design the drawn render mode rests on: **the player renders the audio,
     * Compose renders the picture**. There is no layer and no view controller
     * attached in that mode — the `AVPlayer` plays with nothing to draw into, and
     * an `AVPlayerItemVideoOutput` is what keeps video decoding at all, since it
     * is the only consumer asking for frames.
     *
     * So pulling frames must be completely invisible to playback. If attaching an
     * output stalled, re-buffered or re-timed the player, the mode would trade a
     * blur for an audio glitch.
     */
    @Test
    fun `pulling frames does not disturb playback`() {
        if (!isMac) return println("skipped: not macOS")
        val file = videoFile ?: return println("skipped: no system video file found")

        val engine = AvFoundationVideoEngine()
        val recorder = Recorder(engine)
        try {
            assertTrue(engine.setSource(MediaSource.FilePath(file.absolutePath)))
            engine.prepare()
            assertTrue(waitFor { recorder.durationMs >= 0 }, "never became ready")

            engine.play()
            assertTrue(waitFor { engine.currentPositionMs() > 100 }, "never started playing")
            assertTrue(waitFor { engine.latestFrame() != null }, "no frames while playing")

            // Frames and playback advancing together is the whole claim.
            val positionWithFrames = engine.currentPositionMs()
            val sequenceWithFrames = assertNotNull(engine.latestFrame()).sequence
            assertTrue(
                waitFor { engine.currentPositionMs() > positionWithFrames + 100 },
                "position stopped advancing while frames were being pulled",
            )
            assertTrue(
                waitFor { (engine.latestFrame()?.sequence ?: 0) > sequenceWithFrames },
                "frames stopped arriving while playing",
            )
            assertNull(engine.framePumpError, "the pump reported a failure")
        } finally {
            engine.release()
            recorder.stop()
        }
    }

    /**
     * Switching render mode mid-playback is a UI decision, and the audio must not
     * notice: turning the frame output off is what the surface does when it
     * leaves the composition or the app switches back to the native view.
     */
    @Test
    fun `turning frame output off leaves playback running`() {
        if (!isMac) return println("skipped: not macOS")
        val file = videoFile ?: return println("skipped: no system video file found")

        val engine = AvFoundationVideoEngine()
        val recorder = Recorder(engine)
        try {
            assertTrue(engine.setSource(MediaSource.FilePath(file.absolutePath)))
            engine.prepare()
            assertTrue(waitFor { recorder.durationMs >= 0 })
            engine.play()
            assertTrue(waitFor { engine.latestFrame() != null }, "no frames to begin with")

            engine.setFrameOutputEnabled(false)
            val positionAfterDetach = engine.currentPositionMs()

            // Frames stop — that is the point of disabling — but the player does not.
            assertNull(engine.latestFrame(), "frames should be dropped once disabled")
            assertTrue(
                waitFor { engine.currentPositionMs() > positionAfterDetach + 100 },
                "detaching the video output stopped playback",
            )
        } finally {
            engine.release()
            recorder.stop()
        }
    }

    /**
     * `addOutput:` returns nothing and fails silently, so calling it and it
     * *taking effect* are separate claims. This asks the item what it actually
     * holds rather than trusting the call.
     */
    @Test
    fun `the video output is really attached to the item`() {
        if (!isMac) return println("skipped: not macOS")
        val file = videoFile ?: return println("skipped: no system video file found")

        val engine = AvFoundationVideoEngine()
        val recorder = Recorder(engine)
        try {
            assertEquals(0, engine.attachedOutputCount(), "nothing to attach to before prepare")

            assertTrue(engine.setSource(MediaSource.FilePath(file.absolutePath)))
            engine.prepare()
            assertTrue(waitFor { recorder.durationMs >= 0 }, "never became ready")

            assertEquals(
                1,
                engine.attachedOutputCount(),
                "prepare should have attached exactly one video output",
            )
        } finally {
            engine.release()
            recorder.stop()
        }
    }

    @Test
    fun `disabling detaches the output and re-enabling attaches one again`() {
        if (!isMac) return println("skipped: not macOS")
        val file = videoFile ?: return println("skipped: no system video file found")

        val engine = AvFoundationVideoEngine()
        val recorder = Recorder(engine)
        try {
            assertTrue(engine.setSource(MediaSource.FilePath(file.absolutePath)))
            engine.prepare()
            assertTrue(waitFor { recorder.durationMs >= 0 })
            assertEquals(1, engine.attachedOutputCount())

            engine.setFrameOutputEnabled(false)
            assertEquals(
                0,
                engine.attachedOutputCount(),
                "removeOutput: did not take effect — the item still holds an output",
            )

            engine.setFrameOutputEnabled(true)
            assertEquals(
                1,
                engine.attachedOutputCount(),
                "re-enabling should attach exactly one, not stack a second",
            )
        } finally {
            engine.release()
            recorder.stop()
        }
    }

    /**
     * Enabling twice must not attach twice. A duplicated output would have both
     * competing for the same pixel buffers, and only one of them would ever be
     * detached.
     */
    @Test
    fun `enabling twice attaches only one output`() {
        if (!isMac) return println("skipped: not macOS")
        val file = videoFile ?: return println("skipped: no system video file found")

        val engine = AvFoundationVideoEngine()
        val recorder = Recorder(engine)
        try {
            assertTrue(engine.setSource(MediaSource.FilePath(file.absolutePath)))
            engine.prepare()
            assertTrue(waitFor { recorder.durationMs >= 0 })

            engine.setFrameOutputEnabled(true)
            engine.setFrameOutputEnabled(true)

            assertEquals(1, engine.attachedOutputCount())
        } finally {
            engine.release()
            recorder.stop()
        }
    }

    @Test
    fun `releasing drops the frame`() {
        if (!isMac) return println("skipped: not macOS")
        val file = videoFile ?: return println("skipped: no system video file found")

        val engine = AvFoundationVideoEngine()
        val recorder = Recorder(engine)
        assertTrue(engine.setSource(MediaSource.FilePath(file.absolutePath)))
        engine.prepare()
        assertTrue(waitFor { recorder.durationMs >= 0 })
        engine.play()
        assertTrue(waitFor { engine.latestFrame() != null })

        engine.release()
        recorder.stop()

        // ~100 MB of pixel buffers must not outlive the player that made them.
        assertNull(engine.latestFrame())
    }
}
