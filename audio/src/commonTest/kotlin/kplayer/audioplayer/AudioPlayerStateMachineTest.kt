package kplayer.audioplayer

import kplayer.core.event.PlaybackEvent
import kplayer.core.player.PlaybackStateMachine
import kplayer.core.state.MediaSource
import kplayer.core.state.PlaybackStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The audio counterpart of `:video`'s `PlaybackStateMachineTest`.
 *
 * These run on a plain JVM test runtime with no `Dispatchers.setMain()`, and with no
 * dispatcher configured on the machine at all: transitions run inline on the calling
 * thread under the graph's re-entrant lock. Every assertion reads `state.value` on the
 * line after `onEvent`, so they also pin down that `onEvent` is synchronous.
 */
class AudioPlayerStateMachineTest {

    private val source = MediaSource.Url("episode.mp3")

    private fun playingMachine(durationMs: Long = 100_000L) =
        PlaybackStateMachine(AudioPlayerState()).apply {
            onEvent(PlaybackEvent.LoadRequested(source))
            onEvent(PlaybackEvent.Ready(durationMs))
            onEvent(PlaybackEvent.PlaybackStarted)
        }

    @Test
    fun `starts idle`() {
        assertEquals(PlaybackStatus.Idle, PlaybackStateMachine(AudioPlayerState()).state.value.status)
    }

    @Test
    fun `load from idle enters buffering and records the source`() {
        val machine = PlaybackStateMachine(AudioPlayerState())

        machine.onEvent(PlaybackEvent.LoadRequested(source))

        val state = machine.state.value
        assertEquals(PlaybackStatus.Buffering, state.status)
        assertEquals(source, state.source)
        assertEquals(0L, state.positionMs)
    }

    @Test
    fun `ready after buffering enters ready and records duration`() {
        val machine = PlaybackStateMachine(AudioPlayerState())

        machine.onEvent(PlaybackEvent.LoadRequested(source))
        machine.onEvent(PlaybackEvent.Ready(durationMs = 100_000))

        val state = machine.state.value
        // Lands on Ready; the platform drives the subsequent play() call.
        assertEquals(PlaybackStatus.Ready, state.status)
        assertEquals(100_000L, state.durationMs)
    }

    @Test
    fun `platform play after ready enters playing`() {
        assertEquals(PlaybackStatus.Playing, playingMachine().state.value.status)
    }

    @Test
    fun `pause while playing enters paused`() {
        val machine = playingMachine()

        machine.onEvent(PlaybackEvent.PlaybackPaused)

        assertEquals(PlaybackStatus.Paused, machine.state.value.status)
    }

    @Test
    fun `play while paused resumes playback`() {
        val machine = playingMachine()

        machine.onEvent(PlaybackEvent.PlaybackPaused)
        machine.onEvent(PlaybackEvent.PlaybackStarted)

        assertEquals(PlaybackStatus.Playing, machine.state.value.status)
    }

    @Test
    fun `stop resets position`() {
        val machine = playingMachine()

        machine.onEvent(PlaybackEvent.PositionSynced(5_000))
        machine.onEvent(PlaybackEvent.StopRequested)

        val state = machine.state.value
        assertEquals(PlaybackStatus.Stopped, state.status)
        assertEquals(0L, state.positionMs)
    }

    @Test
    fun `position applies while playing or paused but not while buffering a load`() {
        val machine = PlaybackStateMachine(AudioPlayerState())

        machine.onEvent(PlaybackEvent.LoadRequested(source))
        machine.onEvent(PlaybackEvent.PositionSynced(5_000))
        assertEquals(0L, machine.state.value.positionMs)

        machine.onEvent(PlaybackEvent.Ready(100_000))
        machine.onEvent(PlaybackEvent.PlaybackStarted)
        machine.onEvent(PlaybackEvent.PositionSynced(5_000))
        assertEquals(5_000L, machine.state.value.positionMs)

        // Still applied while paused, so a seek made from a paused player shows up.
        machine.onEvent(PlaybackEvent.PlaybackPaused)
        machine.onEvent(PlaybackEvent.PositionSynced(7_000))
        assertEquals(7_000L, machine.state.value.positionMs)
    }

    @Test
    fun `buffering mid-playback keeps the position`() {
        val machine = playingMachine()

        machine.onEvent(PlaybackEvent.PositionSynced(50_000))
        machine.onEvent(PlaybackEvent.BufferingStarted)

        val state = machine.state.value
        assertEquals(PlaybackStatus.Buffering, state.status)
        assertEquals(50_000L, state.positionMs)
    }

    @Test
    fun `recovering from a mid-playback stall resumes at the same position`() {
        val machine = playingMachine()

        machine.onEvent(PlaybackEvent.PositionSynced(50_000))
        machine.onEvent(PlaybackEvent.BufferingStarted)
        machine.onEvent(PlaybackEvent.Ready(100_000))
        machine.onEvent(PlaybackEvent.PlaybackStarted)

        val state = machine.state.value
        assertEquals(PlaybackStatus.Playing, state.status)
        assertEquals(50_000L, state.positionMs)
    }

    @Test
    fun `playback completed enters completed`() {
        val machine = playingMachine()

        machine.onEvent(PlaybackEvent.PlaybackCompleted)

        assertEquals(PlaybackStatus.Completed, machine.state.value.status)
    }

    @Test
    fun `completion arriving after a pause at end of media still enters completed`() {
        val machine = playingMachine()

        // Native players flip to "not playing" before signalling completion; the
        // pause must not strand us in Paused.
        machine.onEvent(PlaybackEvent.PlaybackPaused)
        machine.onEvent(PlaybackEvent.PlaybackCompleted)

        assertEquals(PlaybackStatus.Completed, machine.state.value.status)
    }

    @Test
    fun `completion snaps the position to the end of the media`() {
        val machine = playingMachine(durationMs = 100_000)

        // Where a 500ms sync loop typically leaves it: just short of the end.
        machine.onEvent(PlaybackEvent.PositionSynced(99_600))
        machine.onEvent(PlaybackEvent.PlaybackCompleted)

        assertEquals(100_000L, machine.state.value.positionMs)
    }

    @Test
    fun `completion leaves a live stream position alone`() {
        val machine = playingMachine(durationMs = 0)

        machine.onEvent(PlaybackEvent.PositionSynced(99_600))
        machine.onEvent(PlaybackEvent.PlaybackCompleted)

        // No duration means no end to snap to.
        assertEquals(99_600L, machine.state.value.positionMs)
    }

    @Test
    fun `seeking back from completed moves the position`() {
        val machine = playingMachine()

        machine.onEvent(PlaybackEvent.PlaybackCompleted)
        machine.onEvent(PlaybackEvent.PositionSynced(0))

        // Dropping this is what left the seek bar pinned at the end after a restart.
        assertEquals(0L, machine.state.value.positionMs)
        assertEquals(PlaybackStatus.Completed, machine.state.value.status)
    }

    @Test
    fun `playing again from completed re-enters playing`() {
        val machine = playingMachine()

        machine.onEvent(PlaybackEvent.PlaybackCompleted)
        machine.onEvent(PlaybackEvent.PositionSynced(0))
        machine.onEvent(PlaybackEvent.PlaybackStarted)

        assertEquals(PlaybackStatus.Playing, machine.state.value.status)

        machine.onEvent(PlaybackEvent.PositionSynced(3_000))
        assertEquals(3_000L, machine.state.value.positionMs)
    }

    @Test
    fun `rebuffering a replay from completed enters buffering`() {
        val machine = playingMachine()

        machine.onEvent(PlaybackEvent.PlaybackCompleted)
        machine.onEvent(PlaybackEvent.BufferingStarted)

        assertEquals(PlaybackStatus.Buffering, machine.state.value.status)
    }

    @Test
    fun `failure enters error and records the message`() {
        val machine = PlaybackStateMachine(AudioPlayerState())

        machine.onEvent(PlaybackEvent.Failure("Network error"))

        val state = machine.state.value
        assertEquals(PlaybackStatus.Error, state.status)
        assertEquals("Network error", state.errorMessage)
    }

    @Test
    fun `failure is accepted from any state`() {
        val machine = playingMachine()

        machine.onEvent(PlaybackEvent.Failure("Decoder died"))

        assertEquals(PlaybackStatus.Error, machine.state.value.status)
    }

    @Test
    fun `loading again clears a previous error`() {
        val machine = PlaybackStateMachine(AudioPlayerState())

        machine.onEvent(PlaybackEvent.Failure("Network error"))
        machine.onEvent(PlaybackEvent.LoadRequested(MediaSource.Url("other.mp3")))

        val state = machine.state.value
        assertEquals(PlaybackStatus.Buffering, state.status)
        assertNull(state.errorMessage)
        assertEquals(MediaSource.Url("other.mp3"), state.source)
    }

    @Test
    fun `release enters released from any state`() {
        val machine = playingMachine()

        machine.onEvent(PlaybackEvent.ReleaseRequested)

        assertEquals(PlaybackStatus.Released, machine.state.value.status)
    }

    @Test
    fun `speed and volume are recorded without changing status`() {
        val machine = playingMachine()

        machine.onEvent(PlaybackEvent.SpeedChanged(1.5f))
        machine.onEvent(PlaybackEvent.VolumeChanged(0.25f))

        val state = machine.state.value
        assertEquals(PlaybackStatus.Playing, state.status)
        assertEquals(1.5f, state.playbackSpeed)
        assertEquals(0.25f, state.volume)
    }

    @Test
    fun `subtitle cues are absorbed rather than dispatched`() {
        val machine = playingMachine()

        // Cannot happen without a surface, but the event type is shared with video
        // — it must not disturb the graph if something does emit it.
        machine.onEvent(PlaybackEvent.SubtitleCueChanged("unexpected"))

        assertEquals(PlaybackStatus.Playing, machine.state.value.status)
    }

    // ── Synchrony ─────────────────────────────────────────────────────────────

    /**
     * What the injected-scope tests here used to guard, now that there is no scope to
     * inject: the machine takes no dispatcher at all, so no dispatcher choice can
     * defer a transition or leave `state.value` stale behind one.
     */
    @Test
    fun `every event applies before onEvent returns`() {
        val machine = PlaybackStateMachine(AudioPlayerState())

        machine.onEvent(PlaybackEvent.LoadRequested(source))
        assertEquals(PlaybackStatus.Buffering, machine.state.value.status)

        machine.onEvent(PlaybackEvent.Ready(100_000))
        assertEquals(PlaybackStatus.Ready, machine.state.value.status)

        machine.onEvent(PlaybackEvent.PlaybackStarted)
        assertEquals(PlaybackStatus.Playing, machine.state.value.status)

        machine.onEvent(PlaybackEvent.PlaybackCompleted)
        assertEquals(PlaybackStatus.Completed, machine.state.value.status)
    }

}
