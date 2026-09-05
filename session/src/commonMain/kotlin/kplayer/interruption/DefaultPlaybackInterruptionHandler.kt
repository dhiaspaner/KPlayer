package kplayer.interruption

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kplayer.core.MediaPlayer
import kplayer.core.audio.AudioSession
import kplayer.core.state.MediaSource
import kplayer.core.state.PlaybackState
import kplayer.core.state.PlaybackStatus
import kplayer.core.state.isPlaying

/**
 * Decides whether to pause/resume in response to interruptions, driven entirely
 * by the configured policies and the set of currently-active interruptions.
 *
 * The whole engine is two rules:
 *  - **Began**: if the cause's policy pauses, record it as active and pause.
 *  - **Ended**: drop it; auto-resume only once *no* interruption remains active,
 *    the player was playing when the chain started, and the (strictest) policy
 *    in the chain permits it.
 *
 * That single "resume only when the active set empties" rule is what makes
 * stacked interruptions correct: a call that ends while the app is still
 * backgrounded will not resume, because backgrounding is still active.
 *
 * There is a third rule for one case only — see [keepPlayingThrough], which
 * holds playback open across a headphone disconnect the policy said to ignore
 * on the platforms that pause anyway.
 *
 * @param scope where that recovery runs, and the only asynchronous thing this
 *   class does. `null` disables it: the handler then behaves exactly as its two
 *   rules describe, which is what a test not exercising the recovery wants.
 * @param keepPlayingWindowMs how long a headphone disconnect is allowed to be
 *   followed by a platform pause and still be recovered from. Short on purpose:
 *   a pause arriving later is far more likely to be the user's than the OS's,
 *   and undoing a deliberate pause is worse than leaving one route change
 *   unhandled.
 */
class DefaultPlaybackInterruptionHandler<S : PlaybackState>(
    private val config: StateFlow<InterruptionConfig> = MutableStateFlow(InterruptionConfig.StrictManualResume),
    private val player: MediaPlayer<MediaSource, S>,
    private val interruptions: InterruptionManager = InterruptionManager(),
    private val audioSession: AudioSession,
    private val scope: CoroutineScope? = null,
    private val keepPlayingWindowMs: Long = 500L,
) : PlaybackInterruptionHandler {

    /** True if the player was playing when the *current* chain of interruptions began. */
    private var wasPlayingBeforeChain = false

    /** Strictest resume policy seen across the current chain; reset when it clears. */
    private var chainResume = ResumePolicy.Always

    /** Volume captured when ducking began, restored when it ends; null when not ducked. */
    private var volumeBeforeDuck: Float? = null

    /** The in-flight [keepPlayingThrough] watch, if any. At most one runs at a time. */
    private var keepPlayingJob: Job? = null

    override fun onEvent(event: InterruptionEvent) {
        when (event) {
            is InterruptionEvent.Began -> onBegan(event.cause)
            is InterruptionEvent.Ended -> onEnded(event.cause, event.systemAllowsResume)
            InterruptionEvent.DuckBegan -> onDuckBegan()
            InterruptionEvent.DuckEnded -> onDuckEnded()
        }
    }

    private fun onDuckBegan() {
        val policy = config.value.duckPolicy
        // Ducking lowers volume; it never pauses and never touches the active set.
        if (policy is DuckPolicy.LowerVolume && volumeBeforeDuck == null) {
            volumeBeforeDuck = player.state.value.volume
            player.setVolume(policy.level)
        }
    }

    private fun onDuckEnded() {
        volumeBeforeDuck?.let { player.setVolume(it) }
        volumeBeforeDuck = null
    }

    private fun onBegan(cause: InterruptionCause) {
        val response = config.value.responseFor(cause)
        if (!response.pausesPlayback) { // e.g. Ignore / KeepState / ContinuePlayback
            keepPlayingThrough(cause)
            return
        }

        // This pause is ours and intended; nothing may undo it.
        keepPlayingJob?.cancel()

        if (interruptions.active.value.isEmpty()) {
            // First interruption of a new chain — capture the intent to restore.
            wasPlayingBeforeChain = player.isPlaying
            chainResume = ResumePolicy.Always
        }
        interruptions.begin(cause)
        // Stacked interruptions tighten the chain to the most restrictive policy.
        chainResume = maxOf(chainResume, response.resume)

        if (player.isPlaying) player.pause()
    }

    private fun onEnded(cause: InterruptionCause, systemAllowsResume: Boolean) {
        if (cause !in interruptions.active.value) return // never began, or already ended
        interruptions.end(cause)
        if (interruptions.active.value.isNotEmpty()) return // something else still holds us paused

        val resume = wasPlayingBeforeChain && when (chainResume) {
            ResumePolicy.Never -> false
            ResumePolicy.WhenSystemAllows -> systemAllowsResume
            ResumePolicy.Always -> true
        }
        // Re-acquire audio ownership first; if it's denied, stay paused.
        if (resume && audioSession.reacquire()) player.play()
    }

    /**
     * Keeps playback going across an interruption the policy chose to ignore,
     * on a platform that pauses regardless of what the policy says.
     *
     * `Ignore` and `ContinuePlayback` are answered by *not* pausing, which is
     * enough wherever the library is the only thing that would have paused —
     * Android's becoming-noisy broadcast and the web's route change are pure
     * notifications. iOS is not like that: `AVPlayer` stops itself when the
     * output device it was playing to disappears, so the policy's decision is
     * overruled a moment later by AVFoundation and the player lands in `Paused`
     * with no library call involved. This waits briefly for exactly that pause
     * and undoes it.
     *
     * Deliberately limited to [InterruptionCause.HeadphonesDisconnected]:
     *
     * - **`AudioFocusLoss`** — a pause there is another app or the OS taking the
     *   output, and playing over it is both rude and usually futile.
     * - **`AppBackgrounded`** — a pause there is the OS enforcing that the app
     *   may not play in the background, which no policy can overrule.
     *
     * Only a disconnect leaves the app genuinely able to keep playing, out of
     * the speaker, which is precisely what `ContinuePlayback` asks for.
     *
     * Nothing is issued if the platform does not pause: the window simply
     * expires. The audio session is not re-acquired either — a route change
     * never took it away, and re-activating it would add a second glitch to the
     * one the route switch already caused.
     */
    private fun keepPlayingThrough(cause: InterruptionCause) {
        if (cause != InterruptionCause.HeadphonesDisconnected) return
        val scope = scope ?: return
        // Nothing to hold open — a player that was not playing must not be
        // started by an interruption.
        if (!player.isPlaying) return

        keepPlayingJob?.cancel()
        keepPlayingJob = scope.launch {
            val platformPaused = withTimeoutOrNull(keepPlayingWindowMs) {
                player.state.first { it.status == PlaybackStatus.Paused }
            } != null

            // Something that *should* hold us paused arrived in the meantime —
            // a call, a backgrounding. That pause outranks this one.
            if (platformPaused && interruptions.active.value.isEmpty()) player.play()
        }
    }
}
