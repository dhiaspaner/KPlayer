package kplayer.audioplayer

import kplayer.core.player.AbstractMediaEngine
import kplayer.core.player.MediaEngine
import kplayer.core.state.MediaSource
import kplayer.core.state.PlaybackError

/**
 * A [MediaEngine] with no media behind it.
 *
 * Records what [EngineMediaPlayer] asked of it, and lets a test play the native
 * player's part through the `emit…` calls below. That is the whole point: the
 * backend's sequencing is exercised on the JVM, with facts arriving in whatever
 * order the test wants — including orders a real engine produces only rarely,
 * like a failure mid-buffer.
 *
 * @param rejectSources sources this engine claims it cannot represent, so the
 *   "invalid source" path is reachable without an unparseable URL.
 */
internal class FakeMediaEngine(
    private val rejectSources: Set<MediaSource> = emptySet(),
) : AbstractMediaEngine() {

    /** Every call made on this engine, in order, as a readable name. */
    val calls = mutableListOf<String>()

    var source: MediaSource? = null
        private set
    var prepareCount = 0
        private set
    var playCount = 0
        private set
    var pauseCount = 0
        private set
    var released = false
        private set
    var speed: Float = 1f
        private set
    var volume: Float = 1f
        private set
    var lastSeekMs: Long? = null
        private set

    /** What [currentPositionMs] reports; a test moves this as playback "advances". */
    var positionMs: Long = 0L

    /**
     * Method names — `"play"`, `"prepare"`, `"setSource"` … — that throw instead of
     * succeeding, which is how a real native player fails synchronously: a released
     * `MediaCodec`, an `AVPlayer` touched after teardown. Mutable during a test so a
     * retry can be made to succeed on its second attempt.
     */
    val throwingCalls = mutableSetOf<String>()

    /**
     * What an armed call throws, given its name. Replaceable so a test can raise
     * something the boundary must treat differently — a `CancellationException`,
     * which is not a playback failure however much it looks like one to
     * `runCatching`.
     */
    var throwableFor: (String) -> Throwable = { IllegalStateException("$it failed") }

    /** Records the attempt in [calls] first, so a failed call is still visible there. */
    private fun failIfArmed(call: String) {
        if (call in throwingCalls) throw throwableFor(call)
    }

    // ── The native player's part, played by the test ──────────────────────────
    //
    // Public wrappers over the protected `report…` calls a real engine makes from
    // its native callbacks: same events, same route, chosen by the test instead of
    // by ExoPlayer or AVFoundation.

    fun emitPlaying(isPlaying: Boolean) = reportPlaying(isPlaying)

    fun emitBuffering(isBuffering: Boolean) = reportBuffering(isBuffering)

    fun emitReady(durationMs: Long) = reportReady(durationMs)

    fun emitCompleted() = reportCompleted()

    fun emitError(error: PlaybackError) = reportError(error)

    fun emitError(message: String) = reportError(message)

    // ── MediaEngine ───────────────────────────────────────────────────────────

    override fun setSource(source: MediaSource): Boolean {
        calls += "setSource($source)"
        failIfArmed("setSource")
        if (source in rejectSources) return false
        this.source = source
        return true
    }

    override fun prepare() {
        calls += "prepare"
        failIfArmed("prepare")
        prepareCount++
    }

    override fun play() {
        calls += "play"
        failIfArmed("play")
        playCount++
    }

    override fun pause() {
        calls += "pause"
        failIfArmed("pause")
        pauseCount++
    }

    override fun seekTo(positionMs: Long) {
        calls += "seekTo($positionMs)"
        failIfArmed("seekTo")
        lastSeekMs = positionMs
        this.positionMs = positionMs
    }

    override fun setSpeed(speed: Float) {
        calls += "setSpeed($speed)"
        failIfArmed("setSpeed")
        this.speed = speed
    }

    override fun setVolume(volume: Float) {
        calls += "setVolume($volume)"
        failIfArmed("setVolume")
        this.volume = volume
    }

    override fun currentPositionMs(): Long = positionMs

    override fun release() {
        calls += "release"
        failIfArmed("release")
        released = true
    }
}
