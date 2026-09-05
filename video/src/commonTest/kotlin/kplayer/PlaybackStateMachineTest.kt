package kplayer

import kplayer.core.event.PlaybackEvent
import kplayer.core.state.PlaybackStatus
import kplayer.videoplayer.VideoPlayerStateMachine
import kplayer.core.state.MediaSource

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackStateMachineTest {

    @Test
    fun `load from idle enters buffering`() {
        val machine = VideoPlayerStateMachine()

        val source = MediaSource.Url("video.mp4")

        machine.onEvent(PlaybackEvent.LoadRequested(source))

        val state = machine.state.value

        assertEquals(PlaybackStatus.Buffering, state.status)
        assertEquals(source, state.source)
        assertEquals(0L, state.positionMs)
    }

    @Test
    fun `ready after buffering enters ready and records duration`() {
        val machine = VideoPlayerStateMachine()

        machine.onEvent(PlaybackEvent.LoadRequested(MediaSource.Url("video.mp4")))
        machine.onEvent(PlaybackEvent.Ready(durationMs = 100_000))

        val state = machine.state.value

        // State machine lands on Ready; platform drives the subsequent play() call
        assertEquals(PlaybackStatus.Ready, state.status)
        assertEquals(100_000, state.durationMs)
    }

    @Test
    fun `platform play after ready enters playing`() {
        val machine = VideoPlayerStateMachine()

        machine.onEvent(PlaybackEvent.LoadRequested(MediaSource.Url("video.mp4")))
        machine.onEvent(PlaybackEvent.Ready(durationMs = 100_000))
        machine.onEvent(PlaybackEvent.PlaybackStarted)

        assertEquals(PlaybackStatus.Playing, machine.state.value.status)
    }

    @Test
    fun `pause while playing enters paused`() {
        val machine = VideoPlayerStateMachine()

        machine.onEvent(PlaybackEvent.LoadRequested(MediaSource.Url("video.mp4")))
        machine.onEvent(PlaybackEvent.Ready(100_000))
        machine.onEvent(PlaybackEvent.PlaybackStarted)
        machine.onEvent(PlaybackEvent.PlaybackPaused)

        assertEquals(PlaybackStatus.Paused, machine.state.value.status)
    }

    @Test
    fun `play while paused resumes playback`() {
        val machine = VideoPlayerStateMachine()

        machine.onEvent(PlaybackEvent.LoadRequested(MediaSource.Url("video.mp4")))
        machine.onEvent(PlaybackEvent.Ready(100_000))
        machine.onEvent(PlaybackEvent.PlaybackStarted)
        machine.onEvent(PlaybackEvent.PlaybackPaused)
        machine.onEvent(PlaybackEvent.PlaybackStarted)

        assertEquals(PlaybackStatus.Playing, machine.state.value.status)
    }

    @Test
    fun `stop resets position`() {
        val machine = VideoPlayerStateMachine()

        machine.onEvent(PlaybackEvent.LoadRequested(MediaSource.Url("video.mp4")))
        machine.onEvent(PlaybackEvent.Ready(100_000))
        machine.onEvent(PlaybackEvent.PlaybackStarted)
        machine.onEvent(PlaybackEvent.PositionSynced(5000))
        machine.onEvent(PlaybackEvent.StopRequested)

        val state = machine.state.value

        assertEquals(PlaybackStatus.Stopped, state.status)
        assertEquals(0L, state.positionMs)
    }

    @Test
    fun `position changes only while playing`() {
        val machine = VideoPlayerStateMachine()

        machine.onEvent(PlaybackEvent.LoadRequested(MediaSource.Url("video.mp4")))
        machine.onEvent(PlaybackEvent.PositionSynced(5000))

        assertEquals(0L, machine.state.value.positionMs)

        machine.onEvent(PlaybackEvent.Ready(100_000))
        machine.onEvent(PlaybackEvent.PlaybackStarted)
        machine.onEvent(PlaybackEvent.PositionSynced(5000))

        assertEquals(5000L, machine.state.value.positionMs)
    }

    @Test
    fun `playback completed enters completed state`() {
        val machine = VideoPlayerStateMachine()

        machine.onEvent(PlaybackEvent.LoadRequested(MediaSource.Url("video.mp4")))
        machine.onEvent(PlaybackEvent.Ready(100_000))
        machine.onEvent(PlaybackEvent.PlaybackStarted)
        machine.onEvent(PlaybackEvent.PlaybackCompleted)

        assertEquals(PlaybackStatus.Completed, machine.state.value.status)
    }

    @Test
    fun `completion arriving after a pause at end of media still enters completed`() {
        val machine = VideoPlayerStateMachine()

        machine.onEvent(PlaybackEvent.LoadRequested(MediaSource.Url("video.mp4")))
        machine.onEvent(PlaybackEvent.Ready(100_000))
        machine.onEvent(PlaybackEvent.PlaybackStarted)

        // Native players flip to "not playing" before signalling completion; the
        // pause must not strand us in Paused.
        machine.onEvent(PlaybackEvent.PlaybackPaused)
        machine.onEvent(PlaybackEvent.PlaybackCompleted)

        assertEquals(PlaybackStatus.Completed, machine.state.value.status)
    }

    @Test
    fun `failure enters error state`() {
        val machine = VideoPlayerStateMachine()

        machine.onEvent(PlaybackEvent.Failure("Network error"))

        val state = machine.state.value

        assertEquals(PlaybackStatus.Error, state.status)
        assertEquals("Network error", state.errorMessage)
    }

    @Test
    fun `release enters released state`() {
        val machine = VideoPlayerStateMachine()

        machine.onEvent(PlaybackEvent.ReleaseRequested)

        assertEquals(PlaybackStatus.Released, machine.state.value.status)
    }

    @Test
    fun `buffering interrupts playback in middle`() {
        val machine = VideoPlayerStateMachine()

        machine.onEvent(PlaybackEvent.LoadRequested(MediaSource.Url("video.mp4")))
        machine.onEvent(PlaybackEvent.Ready(100_000))
        machine.onEvent(PlaybackEvent.PlaybackStarted)
        machine.onEvent(PlaybackEvent.PositionSynced(50_000))
        machine.onEvent(PlaybackEvent.BufferingStarted)

        val state = machine.state.value

        assertEquals(PlaybackStatus.Buffering, state.status)
        assertEquals(50_000, state.positionMs)
    }

    @Test
    fun `buffering resumes playback in middle`() {
        val machine = VideoPlayerStateMachine()

        machine.onEvent(PlaybackEvent.LoadRequested(MediaSource.Url("video.mp4")))
        machine.onEvent(PlaybackEvent.Ready(100_000))
        machine.onEvent(PlaybackEvent.PlaybackStarted)
        machine.onEvent(PlaybackEvent.PositionSynced(50_000))

        machine.onEvent(PlaybackEvent.BufferingStarted)
        machine.onEvent(PlaybackEvent.Ready(100_000))
        machine.onEvent(PlaybackEvent.PlaybackStarted)

        val state = machine.state.value

        assertEquals(PlaybackStatus.Playing, state.status)
        assertEquals(50_000, state.positionMs)
    }

    @Test
    fun `policy pauses on background when playing`() {
        val machine = VideoPlayerStateMachine()
        machine.onEvent(PlaybackEvent.LoadRequested(MediaSource.Url("video.mp4")))
        machine.onEvent(PlaybackEvent.Ready(100_000))
        machine.onEvent(PlaybackEvent.PlaybackStarted)

        // PolicyController calls pause() → PlaybackPaused arrives at state machine
        machine.onEvent(PlaybackEvent.PlaybackPaused)

        assertEquals(PlaybackStatus.Paused, machine.state.value.status)
    }

    @Test
    fun `policy resumes after background if was playing`() {
        val machine = VideoPlayerStateMachine()
        machine.onEvent(PlaybackEvent.LoadRequested(MediaSource.Url("video.mp4")))
        machine.onEvent(PlaybackEvent.Ready(100_000))
        machine.onEvent(PlaybackEvent.PlaybackStarted)

        // Background → policy pauses
        machine.onEvent(PlaybackEvent.PlaybackPaused)
        assertEquals(PlaybackStatus.Paused, machine.state.value.status)

        // Foreground → policy plays → native fires PlaybackStarted
        machine.onEvent(PlaybackEvent.PlaybackStarted)
        assertEquals(PlaybackStatus.Playing, machine.state.value.status)
    }
}
