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
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [AvFoundationVideoEngine] against real media, on a real `AVPlayer`.
 *
 * There is no fake here on purpose. `EngineMediaPlayer`'s sequencing is already
 * covered against `FakeMediaEngine` in `:audio`; what is unproven about this
 * engine is precisely the part a fake cannot exercise — that JNA can drive the
 * Objective-C runtime at all, that a 24-byte `CMTime` survives the ABI in both
 * directions, and that polling reconstructs the callbacks AVFoundation never
 * sends us.
 *
 * **Skips on every OS but macOS**, rather than failing: the whole suite has to
 * stay green on CI machines that are not Macs. A skip prints, so a silently
 * empty run is visible.
 */
class AvFoundationVideoEngineTest {

    /**
     * A system sound, not a video: AIFF exercises exactly the same `AVPlayer`
     * path, and unlike anything under `Desktop Pictures` it is present on every
     * macOS install and short enough to play to completion inside a test.
     */
    private val mediaFile: File? =
        listOf(
            "/System/Library/Sounds/Submarine.aiff",
            "/System/Library/Sounds/Blow.aiff",
            "/System/Library/Sounds/Ping.aiff",
        ).map(::File).firstOrNull(File::exists)

    private val isMac: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().let {
            it.contains("mac") || it.contains("darwin")
        }

    /**
     * Collects the engine's events off its poll thread.
     *
     * Subscribing is [CoroutineStart.UNDISPATCHED] so it has happened by the time
     * the constructor returns: the engine's flow does not replay, and `prepare()`
     * reports buffering immediately.
     */
    private class Recorder(engine: MediaEngine) {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var durationMs: Long = -1
        @Volatile var error: String? = null

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                engine.events.collect { event ->
                    when (event) {
                        PlaybackEvent.PlaybackStarted -> events += "playing"
                        PlaybackEvent.PlaybackPaused -> events += "paused"
                        PlaybackEvent.BufferingStarted -> events += "buffering"
                        PlaybackEvent.BufferingEnded -> events += "buffered"
                        PlaybackEvent.PlaybackCompleted -> events += "completed"

                        is PlaybackEvent.Ready -> {
                            durationMs = event.durationMs
                            events += "ready"
                        }

                        is PlaybackEvent.Failure -> {
                            error = event.error.toDisplayMessage()
                            events += "error"
                        }

                        else -> Unit
                    }
                }
            }
        }

        fun stop() = scope.cancel()

        fun snapshot(): List<String> = synchronized(events) { events.toList() }
    }

    /**
     * Runs [body] against a prepared engine, or skips when this machine cannot
     * host one. Release happens in a `finally` so a failed assertion still tears
     * the poll thread and the native player down.
     */
    private fun withEngine(body: (AvFoundationVideoEngine, Recorder) -> Unit) {
        if (!isMac) return println("skipped: not macOS")
        val file = mediaFile ?: return println("skipped: no system media file found")

        val engine = AvFoundationVideoEngine()
        val recorder = Recorder(engine)
        try {
            assertTrue(
                engine.setSource(MediaSource.FilePath(file.absolutePath)),
                "engine rejected ${file.absolutePath}",
            )
            engine.prepare()
            body(engine, recorder)
        } finally {
            engine.release()
            recorder.stop()
        }
    }

    /** Spins until [condition] holds or [timeoutMs] elapses. */
    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return false
    }

    @Test
    fun `loading reports ready with a real duration`() = withEngine { _, recorder ->
        assertTrue(waitFor { recorder.durationMs >= 0 }, "never became ready: ${recorder.snapshot()}")

        // The load-bearing assertion of the whole engine: a non-zero duration
        // means a 24-byte CMTime came back through JNA intact. A wrong ABI gives
        // 0 or garbage, not a plausible number.
        assertTrue(
            recorder.durationMs in 1..600_000,
            "implausible duration ${recorder.durationMs}ms — suspect the CMTime ABI",
        )
        assertTrue("ready" in recorder.snapshot())
    }

    @Test
    fun `buffering is reported around the load and cleared when ready`() = withEngine { _, recorder ->
        assertTrue(waitFor { recorder.durationMs >= 0 })

        val events = recorder.snapshot()
        assertEquals("buffering", events.first(), "load should open with a buffering report")
        assertTrue(events.indexOf("buffered") < events.indexOf("ready") + 1, "buffering must clear by ready")
    }

    @Test
    fun `playing is reported only after the player actually starts`() = withEngine { engine, recorder ->
        assertTrue(waitFor { recorder.durationMs >= 0 })

        // Nothing may claim playback before play() is called — the engine must
        // report facts, never the intention it was handed.
        assertFalse("playing" in recorder.snapshot(), "reported playing before play()")

        engine.play()
        assertTrue(waitFor { "playing" in recorder.snapshot() }, "never reported playing: ${recorder.snapshot()}")
    }

    @Test
    fun `the position advances while playing`() = withEngine { engine, recorder ->
        assertTrue(waitFor { recorder.durationMs >= 0 })
        engine.play()
        assertTrue(waitFor { engine.currentPositionMs() > 100 }, "position never advanced")
    }

    @Test
    fun `pausing is reported`() = withEngine { engine, recorder ->
        assertTrue(waitFor { recorder.durationMs >= 0 })
        engine.play()
        assertTrue(waitFor { "playing" in recorder.snapshot() })

        engine.pause()
        assertTrue(waitFor { "paused" in recorder.snapshot() }, "never reported paused: ${recorder.snapshot()}")
    }

    /** Proves a `CMTime` survives the ABI as an *argument*, not just as a return. */
    @Test
    fun `seeking moves the position`() = withEngine { engine, recorder ->
        assertTrue(waitFor { recorder.durationMs >= 0 })
        val target = recorder.durationMs / 2

        engine.seekTo(target)

        assertTrue(
            waitFor { kotlin.math.abs(engine.currentPositionMs() - target) < 250 },
            "seek to ${target}ms landed at ${engine.currentPositionMs()}ms",
        )
    }

    /**
     * The wart this engine exists to hide. AVFoundation stops at the end of an
     * item by dropping `timeControlStatus` to paused — indistinguishable from a
     * real pause — so a naive engine reports `paused` and *then* `completed`, and
     * the player visibly steps through `Paused` on its way to `Completed`.
     */
    @Test
    fun `reaching the end completes without first reporting a pause`() = withEngine { engine, recorder ->
        assertTrue(waitFor { recorder.durationMs >= 0 })
        engine.play()
        assertTrue(waitFor { "playing" in recorder.snapshot() })

        assertTrue(
            waitFor(timeoutMs = 15_000) { "completed" in recorder.snapshot() },
            "never completed: ${recorder.snapshot()}",
        )

        val events = recorder.snapshot()
        val completed = events.indexOf("completed")
        val playingStarted = events.indexOf("playing")
        assertFalse(
            events.subList(playingStarted, completed).contains("paused"),
            "reported a pause between play and completion: $events",
        )
    }

    @Test
    fun `an unopenable source reports an error`() {
        if (!isMac) return println("skipped: not macOS")

        val engine = AvFoundationVideoEngine()
        val recorder = Recorder(engine)
        try {
            assertTrue(engine.setSource(MediaSource.FilePath("/nonexistent/kplayer-missing.mp4")))
            engine.prepare()

            assertTrue(waitFor { recorder.error != null }, "no error for a missing file")
            assertFalse(recorder.error.isNullOrBlank(), "error message was empty")
        } finally {
            engine.release()
            recorder.stop()
        }
    }

    @Test
    fun `a blank source is rejected outright`() {
        if (!isMac) return println("skipped: not macOS")

        val engine = AvFoundationVideoEngine()
        val recorder = Recorder(engine)
        try {
            // false, not an exception and not a silent no-op: the caller turns it
            // into a failure.
            assertFalse(engine.setSource(MediaSource.Url("")))
        } finally {
            engine.release()
            recorder.stop()
        }
    }
}
