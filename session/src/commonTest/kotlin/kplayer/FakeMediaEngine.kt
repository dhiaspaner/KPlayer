package kplayer

import kplayer.core.event.PlaybackEvent
import kplayer.core.player.AbstractMediaEngine
import kplayer.core.player.MediaEngine
import kplayer.core.state.MediaSource
import kplayer.core.state.PlaybackError

/**
 * A [MediaEngine] with no media behind it, for wiring a real
 * [kplayer.core.player.EngineMediaPlayer] up to a [kplayer.engine.KMediaManager] on the
 * JVM.
 *
 * The `emit…` calls are public wrappers over the protected `report…` ones a real
 * engine makes from its native callbacks — same events, same route, chosen by the
 * test instead of by ExoPlayer or AVFoundation.
 *
 * `:audio` has a richer fake of its own for testing the backend itself; this one
 * exists because that one is `internal` to `:audio`, and because the question here
 * is only whether facts survive the trip up to the manager.
 */
class FakeMediaEngine : AbstractMediaEngine() {

    /** Every call made on this engine, in order, as a readable name. */
    val calls = mutableListOf<String>()

    /** What [currentPositionMs] reports; a test moves this as playback "advances". */
    var positionMs: Long = 0L

    // ── The native player's part, played by the test ──────────────────────────

    fun emitPlaying(isPlaying: Boolean) = reportPlaying(isPlaying)
    fun emitBuffering(isBuffering: Boolean) = reportBuffering(isBuffering)
    fun emitReady(durationMs: Long) = reportReady(durationMs)
    fun emitCompleted() = reportCompleted()
    fun emitError(error: PlaybackError) = reportError(error)
    fun emitCustom(event: PlaybackEvent) = report(event)

    // ── MediaEngine ───────────────────────────────────────────────────────────

    override fun setSource(source: MediaSource): Boolean {
        calls += "setSource($source)"
        return true
    }

    override fun prepare() { calls += "prepare" }
    override fun play() { calls += "play" }
    override fun pause() { calls += "pause" }

    override fun seekTo(positionMs: Long) {
        calls += "seekTo($positionMs)"
        this.positionMs = positionMs
    }

    override fun setSpeed(speed: Float) { calls += "setSpeed($speed)" }
    override fun setVolume(volume: Float) { calls += "setVolume($volume)" }

    override fun currentPositionMs(): Long = positionMs

    override fun release() { calls += "release" }
}
