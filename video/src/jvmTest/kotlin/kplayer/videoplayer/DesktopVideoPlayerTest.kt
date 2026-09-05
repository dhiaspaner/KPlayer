package kplayer.videoplayer

import kotlinx.coroutines.flow.MutableStateFlow
import kplayer.core.audio.AudioSessionMode
import kplayer.interruption.InterruptionConfig
import kplayer.core.state.MediaSource
import kplayer.core.state.PlaybackStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole desktop stack, not just the engine: `DesktopVideoPlayer` →
 * `EngineMediaPlayer` → `PlaybackStateMachine` → a real `AVPlayer`.
 *
 * [AvFoundationVideoEngineTest] proves the native translation; this proves the
 * translation actually drives `VideoPlayerState`, which is the thing a caller
 * sees. Between them they cover acceptance criterion 2 of the desktop task —
 * everything except frames on screen, which needs a render surface that does not
 * exist yet.
 *
 * Skips on non-macOS for the same reason as the engine suite.
 */
class DesktopVideoPlayerTest {

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

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun withPlayer(body: (DesktopVideoPlayer, MediaSource) -> Unit) {
        if (!isMac) return println("skipped: not macOS")
        val file = mediaFile ?: return println("skipped: no system media file found")

        val player = DesktopVideoPlayer()
        try {
            body(player, MediaSource.FilePath(file.absolutePath))
        } finally {
            player.release()
        }
    }

    @Test
    fun `the desktop target no longer returns a rejecting stub`() {
        if (!isMac) return println("skipped: not macOS")

        // The stub this replaced satisfied every call by emitting Rejected. On a
        // machine with AVFoundation, availability must now be true.
        assertTrue(
            DesktopVideoEngines.isAvailable,
            "AVFoundation should be available on macOS: ${DesktopVideoEngines.unavailableReason}",
        )
    }

    /**
     * The regression that shipped a broken desktop app while every other test in
     * this file passed.
     *
     * Everything else here builds `DesktopVideoPlayer` directly, which skips
     * `KMediaManagerBuilder.build()` — and that is where `Dispatchers.Main` is
     * touched. On the JVM `Main` has no implementation unless something provides
     * one, so the real entry point threw "Module with the Main dispatcher is
     * missing" on its first call while the direct-construction tests stayed green.
     *
     * The stub that `VideoPlayer()` used to return never reached the builder
     * either, which is why the desktop sample only started crashing once there was
     * a real engine behind it.
     */
    @Test
    fun `the public entry point builds a player without a missing Main dispatcher`() {
        if (!isMac) return println("skipped: not macOS")

        val player = VideoPlayer(
            interruptionConfig = MutableStateFlow(InterruptionConfig.MediaPlayerDefault),
            audioSessionMode = AudioSessionMode.Movie,
        )
        try {
            // Reaching here at all is the assertion: build() dispatches its shared
            // config flow on Main.immediate, so a missing dispatcher throws above.
            assertEquals(PlaybackStatus.Idle, player.state.value.status)
        } finally {
            player.release()
        }
    }

    @Test
    fun `loading drives the state machine to ready with a duration`() = withPlayer { player, source ->
        player.load(source)

        assertTrue(
            waitFor { player.state.value.durationMs > 0 },
            "duration never reached the state: ${player.state.value}",
        )
    }

    @Test
    fun `play and pause move the reported status`() = withPlayer { player, source ->
        player.load(source)
        assertTrue(waitFor { player.state.value.durationMs > 0 })

        player.play()
        assertTrue(
            waitFor { player.state.value.status == PlaybackStatus.Playing },
            "never reached Playing: ${player.state.value.status}",
        )

        player.pause()
        assertTrue(
            waitFor { player.state.value.status == PlaybackStatus.Paused },
            "never reached Paused: ${player.state.value.status}",
        )
    }

    @Test
    fun `the reported position advances during playback`() = withPlayer { player, source ->
        player.load(source)
        assertTrue(waitFor { player.state.value.durationMs > 0 })

        player.play()
        assertTrue(
            waitFor { player.state.value.positionMs > 100 },
            "positionMs never advanced past 100: ${player.state.value.positionMs}",
        )
    }

    @Test
    fun `playing to the end reaches Completed and not Paused`() = withPlayer { player, source ->
        player.load(source)
        assertTrue(waitFor { player.state.value.durationMs > 0 })

        player.play()
        assertTrue(waitFor { player.state.value.status == PlaybackStatus.Playing })

        assertTrue(
            waitFor(timeoutMs = 20_000) { player.state.value.status == PlaybackStatus.Completed },
            "never reached Completed: ${player.state.value.status}",
        )
        assertEquals(PlaybackStatus.Completed, player.state.value.status)
    }
}
