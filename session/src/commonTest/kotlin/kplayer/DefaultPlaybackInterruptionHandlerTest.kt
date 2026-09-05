package kplayer

import kotlinx.coroutines.flow.MutableStateFlow
import kplayer.interruption.AudioFocusPolicy
import kplayer.interruption.BackgroundPolicy
import kplayer.interruption.DefaultPlaybackInterruptionHandler
import kplayer.interruption.HeadphonesPolicy
import kplayer.interruption.InterruptionCause
import kplayer.interruption.InterruptionConfig
import kplayer.interruption.InterruptionEvent
import kplayer.interruption.InterruptionManager
import kplayer.interruption.PlaybackInterruptionHandler
import kplayer.core.state.PlaybackStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultPlaybackInterruptionHandlerTest {

    private fun handler(
        config: InterruptionConfig,
        player: FakePlayer,
        interruptions: InterruptionManager = InterruptionManager(),
        audioSession: FakeAudioSession = FakeAudioSession(),
    ) = DefaultPlaybackInterruptionHandler(
        config = MutableStateFlow(config),
        player = player,
        interruptions = interruptions,
        audioSession = audioSession,
    )

    // Readable event helpers mapping the domain action → Began/Ended(cause).
    private fun PlaybackInterruptionHandler.background() =
        onEvent(InterruptionEvent.Began(InterruptionCause.AppBackgrounded))

    private fun PlaybackInterruptionHandler.foreground() =
        onEvent(InterruptionEvent.Ended(InterruptionCause.AppBackgrounded))

    private fun PlaybackInterruptionHandler.focusLost() =
        onEvent(InterruptionEvent.Began(InterruptionCause.AudioFocusLoss))

    private fun PlaybackInterruptionHandler.focusRegained(systemAllowsResume: Boolean = true) =
        onEvent(InterruptionEvent.Ended(InterruptionCause.AudioFocusLoss, systemAllowsResume))

    private fun PlaybackInterruptionHandler.headphonesOut() =
        onEvent(InterruptionEvent.Began(InterruptionCause.HeadphonesDisconnected))

    private fun PlaybackInterruptionHandler.headphonesIn() =
        onEvent(InterruptionEvent.Ended(InterruptionCause.HeadphonesDisconnected))

    // ── AppBackgrounded ───────────────────────────────────────────────────────

    @Test
    fun `AppBackgrounded pauses a playing player when backgroundPolicy pauses`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.StrictManualResume, player)

        h.background()

        assertEquals(1, player.playCallCount)
        assertEquals(1, player.pauseCallCount)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)
    }

    @Test
    fun `AppBackgrounded leaves a playing player untouched when backgroundPolicy is KeepState`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.Uninterruptible, player)

        h.background()

        assertEquals(1, player.playCallCount)
        assertEquals(0, player.pauseCallCount)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
    }

    @Test
    fun `AppBackgrounded leaves a paused player untouched when backgroundPolicy is KeepState`() {
        val player = FakePlayer().also { it.loadAndPause() }

        val h = handler(InterruptionConfig.Uninterruptible, player)

        h.background()

        assertEquals(1, player.playCallCount)
        assertEquals(0, player.pauseCallCount)

        assertEquals(PlaybackStatus.Paused, player.state.value.status)
    }

    // ── AppForegrounded ───────────────────────────────────────────────────────

    @Test
    fun `AppForegrounded restore the previous state of player when backgroundPolicy is PauseAndRestore`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.VideoLesson, player)

        h.background()

        assertEquals(1, player.playCallCount)
        assertEquals(1, player.pauseCallCount)


        h.foreground()

        assertEquals(2, player.playCallCount)
        assertEquals(1, player.pauseCallCount)


        assertEquals(PlaybackStatus.Playing, player.state.value.status)

        player.pause()

        assertEquals(2, player.playCallCount)
        assertEquals(2, player.pauseCallCount)

        h.background()
        h.foreground()

        assertEquals(2, player.playCallCount)
        assertEquals(2, player.pauseCallCount)

        assertEquals(PlaybackStatus.Paused, player.state.value.status)

    }


    @Test
    fun `AppForegrounded leaves a paused player untouched when backgroundPolicy is PauseAndStayPaused`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.StrictManualResume, player)

        h.background()
        h.foreground()

        assertEquals(1, player.playCallCount) // no additional play() call
        assertEquals(1, player.pauseCallCount)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)
    }

    // ── AudioFocusLost ────────────────────────────────────────────────────────

    @Test
    fun `AudioFocusLost pauses a playing player unless policy is Ignore`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.StrictManualResume, player)

        h.focusLost()

        assertEquals(1, player.playCallCount)
        assertEquals(1, player.pauseCallCount)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)
    }

    @Test
    fun `AudioFocusLost leaves a playing player untouched when audioFocusPolicy is Ignore`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.Uninterruptible, player)

        h.focusLost()

        assertEquals(1, player.playCallCount)
        assertEquals(0, player.pauseCallCount)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
    }

    // ── AudioFocusRegained ────────────────────────────────────────────────────

    @Test
    fun `AudioFocusRegained resumes a paused player when RestoreIfPlayingBefore and shouldResume is true`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.VideoLesson, player)

        h.focusLost()
        assertEquals(1, player.pauseCallCount)

        h.focusRegained(systemAllowsResume = true)

        assertEquals(2, player.playCallCount)
        assertEquals(1, player.pauseCallCount)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
    }

    @Test
    fun `AudioFocusRegained leaves player paused when shouldResume is false even under RestoreIfPlayingBefore`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.VideoLesson, player)

        h.focusLost()
        h.focusRegained(systemAllowsResume = false)

        assertEquals(1, player.playCallCount) // no additional play() call
        assertEquals(1, player.pauseCallCount)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)
    }

    @Test
    fun `AudioFocusRegained leaves player paused when audioFocusPolicy is PauseAndStayPaused`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.StrictManualResume, player) // PauseAndStayPaused

        h.focusLost()
        h.focusRegained(systemAllowsResume = true)

        assertEquals(1, player.playCallCount)
        assertEquals(1, player.pauseCallCount)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)
    }

    // ── HeadphonesDisconnected ────────────────────────────────────────────────

    @Test
    fun `HeadphonesDisconnected pauses a playing player unless policy ignores or continues`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.StrictManualResume, player)

        h.headphonesOut()

        assertEquals(1, player.playCallCount)
        assertEquals(1, player.pauseCallCount)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)
    }

    @Test
    fun `HeadphonesDisconnected leaves a playing player untouched when headphonesPolicy is Ignore`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.Uninterruptible, player)

        h.headphonesOut()

        assertEquals(1, player.playCallCount)
        assertEquals(0, player.pauseCallCount)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
    }

    // ── HeadphonesConnected ───────────────────────────────────────────────────

    @Test
    fun `HeadphonesConnected resumes only if it caused the pause and policy is PauseAndRestoreOnReconnect`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.VideoLesson, player)

        h.headphonesOut()
        assertEquals(1, player.pauseCallCount)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)

        h.headphonesIn()

        assertEquals(2, player.playCallCount)
        assertEquals(1, player.pauseCallCount)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
    }

    @Test
    fun `HeadphonesConnected is a no-op when nothing was paused by headphones`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.VideoLesson, player)

        h.headphonesIn()

        assertEquals(1, player.playCallCount) // no additional play() call
        assertEquals(0, player.pauseCallCount)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
    }

    // ── Reactive config ───────────────────────────────────────────────────────

    @Test
    fun `config change is respected on next event`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val configFlow = MutableStateFlow(InterruptionConfig.StrictManualResume) // pauses on background
        val h = DefaultPlaybackInterruptionHandler(
            config = configFlow, player = player, audioSession = FakeAudioSession(),
        )

        h.background()
        assertEquals(1, player.playCallCount)
        assertEquals(1, player.pauseCallCount)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)

        player.play() // manual resume, outside the handler
        assertEquals(2, player.playCallCount)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)

        configFlow.value = InterruptionConfig.Uninterruptible
        h.background()

        assertEquals(2, player.playCallCount)
        assertEquals(1, player.pauseCallCount) // no new pause() call
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
    }

    // ── Full scenario — Default preset ────────────────────────────────────────

    @Test
    fun `Default preset pauses on all events and never auto-resumes`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.StrictManualResume, player)

        h.background()
        assertEquals(1, player.pauseCallCount)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)

        h.foreground()
        h.focusRegained(systemAllowsResume = true)

        assertEquals(1, player.playCallCount) // never resumed
        assertEquals(1, player.pauseCallCount)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)
    }

    // ── Full scenario — VideoLesson preset ───────────────────────────────────

    @Test
    fun `VideoLesson preset resumes after background interruption`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.VideoLesson, player)

        h.background()
        assertEquals(1, player.pauseCallCount)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)

        h.foreground()

        assertEquals(2, player.playCallCount)
        assertEquals(1, player.pauseCallCount)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
    }

    @Test
    fun `VideoLesson preset resumes after audio focus loss`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.VideoLesson, player)

        h.focusLost()
        assertEquals(1, player.pauseCallCount)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)

        h.focusRegained(systemAllowsResume = true)

        assertEquals(2, player.playCallCount)
        assertEquals(1, player.pauseCallCount)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
    }

    @Test
    fun `VideoLesson preset only resumes via the event that caused the pause`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.VideoLesson, player)

        // Headphones disconnect pauses playback
        h.headphonesOut()
        assertEquals(1, player.pauseCallCount)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)

        // A background/foreground cycle while headphones are still out does NOT
        // resume: headphones remains active, so the set never empties on foreground.
        h.background()
        h.foreground()
        assertEquals(1, player.playCallCount)
        assertEquals(1, player.pauseCallCount)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)

        // Only reconnecting headphones empties the active set and resumes.
        h.headphonesIn()

        assertEquals(2, player.playCallCount)
        assertEquals(1, player.pauseCallCount)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
    }


    @Test
    fun `HeadphonesDisconnected does not pause when headphonesPolicy is ContinuePlayback`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.AutoPlay, player) // headphonesPolicy = ContinuePlayback

        val pauseCountBefore = player.pauseCallCount

        h.headphonesOut()

        assertEquals(pauseCountBefore, player.pauseCallCount)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
    }

    // ── An output route change under Ignore ───────────────────────────────────
    // The iOS regression these describe: an unplug used to reach the handler
    // twice — as HeadphonesDisconnected from the hardware observer, and as
    // AudioFocusLoss from the audio session, which observed the same route
    // change. The focus policy pauses by default, so it overruled a
    // headphonesPolicy of Ignore. The session no longer reports route changes;
    // what follows is the behaviour that leaves.

    @Test
    fun `an output route change under Ignore keeps playing even when the focus policy pauses`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        // The combination that used to fail: ignore the headphones, but pause
        // for a genuine focus loss.
        val config = InterruptionConfig(
            audioFocusPolicy = AudioFocusPolicy.RestoreIfPlayingBefore,
            headphonesPolicy = HeadphonesPolicy.Ignore,
        )
        val h = handler(config, player)

        h.headphonesOut()

        assertEquals(0, player.pauseCallCount)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
        assertEquals(60_000L, player.state.value.durationMs) // position and media untouched

        // And the focus policy still does its own job when focus is genuinely lost.
        h.focusLost()
        assertEquals(1, player.pauseCallCount)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)
    }

    @Test
    fun `a user pause still pauses under Ignore`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.Uninterruptible, player)

        h.headphonesOut()
        // Ignoring the interruption must not suppress pausing in general — this
        // is the user pressing Pause, which never goes through the handler.
        player.pause()

        assertEquals(1, player.pauseCallCount)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)
    }

    @Test
    fun `an output route change under Ignore leaves an already paused player paused`() {
        val player = FakePlayer().also { it.loadAndPause() }
        val h = handler(InterruptionConfig.Uninterruptible, player)

        h.headphonesOut()
        // The reconnect arrives too: neither may start a player the user paused.
        h.headphonesIn()

        assertEquals(1, player.playCallCount) // the initial load only
        assertEquals(0, player.pauseCallCount)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)
    }

    @Test
    fun `an output route change under Ignore leaves a buffering player buffering`() {
        val player = FakePlayer().also { it.loadAndBuffer() }
        val h = handler(InterruptionConfig.Uninterruptible, player)

        h.headphonesOut()

        assertEquals(0, player.pauseCallCount)
        assertEquals(PlaybackStatus.Buffering, player.state.value.status)
    }

    @Test
    fun `an output route change under a pausing policy leaves a buffering player buffering`() {
        val player = FakePlayer().also { it.loadAndBuffer() }
        val h = handler(InterruptionConfig.StrictManualResume, player)

        h.headphonesOut()

        // Nothing is playing yet, so there is nothing to pause — the handler
        // only pauses a player that is actually playing.
        assertEquals(0, player.pauseCallCount)
        assertEquals(PlaybackStatus.Buffering, player.state.value.status)
    }

    @Test
    fun `AudioFocusPolicy PauseAndStayPaused pauses on loss and ignores AudioFocusRegained`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val config = InterruptionConfig(
            backgroundPolicy = BackgroundPolicy.KeepState,
            audioFocusPolicy = AudioFocusPolicy.PauseAndStayPaused,
            headphonesPolicy = HeadphonesPolicy.Ignore
        )
        val h = handler(config, player)

        h.focusLost()
        assertEquals(1, player.pauseCallCount)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)

        val playCountBefore = player.playCallCount
        h.focusRegained(systemAllowsResume = true)

        assertEquals(playCountBefore, player.playCallCount) // never resumes
        assertEquals(PlaybackStatus.Paused, player.state.value.status)
    }


    @Test
    fun `BackgroundPolicy PauseAndStayPaused pauses on background and ignores AppForegrounded`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val config = InterruptionConfig(
            backgroundPolicy = BackgroundPolicy.PauseAndStayPaused,
            audioFocusPolicy = AudioFocusPolicy.Ignore,
            headphonesPolicy = HeadphonesPolicy.Ignore
        )
        val h = handler(config, player)

        h.background()
        assertEquals(1, player.pauseCallCount)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)

        val playCountBefore = player.playCallCount
        h.foreground()

        assertEquals(playCountBefore, player.playCallCount) // never resumes
        assertEquals(PlaybackStatus.Paused, player.state.value.status)
    }


    @Test
    fun `focus lost while already paused by backgrounding does not double-pause foreground still resumes`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.VideoLesson, player)

        h.background() // pauses; background now active
        assertEquals(1, player.pauseCallCount)

        // Player is already paused — focus loss adds to the active set but issues
        // no second pause().
        h.focusLost()
        assertEquals(1, player.pauseCallCount) // unchanged

        // Focus returns but background is still active → the set is non-empty, so
        // nothing resumes yet.
        val playCountBefore = player.playCallCount
        h.focusRegained(systemAllowsResume = true)
        assertEquals(playCountBefore, player.playCallCount)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)

        // Foregrounding empties the set and resumes (was playing when the chain began).
        h.foreground()
        assertEquals(playCountBefore + 1, player.playCallCount)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
    }

    @Test
    fun `backgrounding while already paused by focus loss does not double-pause focus regain still resumes`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.VideoLesson, player)

        h.focusLost() // pauses; focus now active
        assertEquals(1, player.pauseCallCount)

        h.background() // already paused → added to set, no new pause()
        assertEquals(1, player.pauseCallCount)

        // Foreground while focus is still active → set non-empty, no resume.
        val playCountBefore = player.playCallCount
        h.foreground()
        assertEquals(playCountBefore, player.playCallCount)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)

        // Focus regain empties the set and resumes.
        h.focusRegained(systemAllowsResume = true)
        assertEquals(playCountBefore + 1, player.playCallCount)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
    }


    @Test
    fun `AppBackgrounded is a no-op when player is already paused`() {
        val player = FakePlayer().also { it.loadAndPause() }
        val h = handler(InterruptionConfig.VideoLesson, player)
        val pauseCountBefore = player.pauseCallCount

        h.background()

        assertEquals(pauseCountBefore, player.pauseCallCount)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)
    }

    @Test
    fun `AudioFocusLost is a no-op when player is already paused`() {
        val player = FakePlayer().also { it.loadAndPause() }
        val h = handler(InterruptionConfig.VideoLesson, player)
        val pauseCountBefore = player.pauseCallCount

        h.focusLost()

        assertEquals(pauseCountBefore, player.pauseCallCount)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)
    }

    @Test
    fun `HeadphonesDisconnected is a no-op when player is already paused`() {
        val player = FakePlayer().also { it.loadAndPause() }
        val h = handler(InterruptionConfig.VideoLesson, player)
        val pauseCountBefore = player.pauseCallCount

        h.headphonesOut()

        assertEquals(pauseCountBefore, player.pauseCallCount)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)
    }


    @Test
    fun `MediaPlayerDefault keeps playing in background restores on focus requires manual headphone resume`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.MediaPlayerDefault, player)

        h.background()
        assertEquals(0, player.pauseCallCount) // KeepState: never pauses
        assertEquals(PlaybackStatus.Playing, player.state.value.status)

        h.focusLost()
        assertEquals(1, player.pauseCallCount)
        h.focusRegained(systemAllowsResume = true)
        assertEquals(2, player.playCallCount)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)

        h.headphonesOut()
        assertEquals(2, player.pauseCallCount)
        val playCountBefore = player.playCallCount
        h.headphonesIn()
        assertEquals(playCountBefore, player.playCallCount) // manual resume required
        assertEquals(PlaybackStatus.Paused, player.state.value.status)
    }

    @Test
    fun `VideoLesson pauses and restores across background and headphones`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.VideoLesson, player)

        h.background()
        assertEquals(1, player.pauseCallCount)
        h.foreground()
        assertEquals(2, player.playCallCount)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)

        h.headphonesOut()
        assertEquals(2, player.pauseCallCount)
        h.headphonesIn()
        assertEquals(3, player.playCallCount)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
    }

    @Test
    fun `AutoPlay always resumes on focus regain regardless of shouldResume ignores headphones`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.AutoPlay, player)

        h.focusLost()
        assertEquals(1, player.pauseCallCount)

        h.focusRegained(systemAllowsResume = false) // ignored under AlwaysResume
        assertEquals(2, player.playCallCount)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)

        val pauseCountBefore = player.pauseCallCount
        h.headphonesOut()
        assertEquals(pauseCountBefore, player.pauseCallCount) // ContinuePlayback: no pause
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
    }


    @Test
    fun `AudioFocusRegained without a prior AudioFocusLost does not resume`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.VideoLesson, player)
        val playCountBefore = player.playCallCount

        h.focusRegained(systemAllowsResume = true)

        assertEquals(playCountBefore, player.playCallCount)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
    }

    @Test
    fun `AppForegrounded without a prior AppBackgrounded does not resume`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.VideoLesson, player)
        val playCountBefore = player.playCallCount

        h.foreground()

        assertEquals(playCountBefore, player.playCallCount)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
    }

    // ── Stacked interruptions (multi-source) ─────────────────────────────────

    @Test
    fun `phone call ending while still backgrounded does not resume until foreground`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val interruptions = InterruptionManager()
        val h = handler(InterruptionConfig.VideoLesson, player, interruptions)

        h.focusLost()   // phone call
        h.background()  // app goes background while on the call
        assertEquals(1, player.pauseCallCount)
        assertEquals(
            setOf(InterruptionCause.AudioFocusLoss, InterruptionCause.AppBackgrounded),
            interruptions.active.value,
        )

        h.focusRegained(systemAllowsResume = true) // call ends, still backgrounded
        assertEquals(1, player.playCallCount) // NOT resumed — background still active
        assertEquals(PlaybackStatus.Paused, player.state.value.status)
        assertEquals(setOf(InterruptionCause.AppBackgrounded), interruptions.active.value)

        h.foreground() // now the set empties
        assertEquals(2, player.playCallCount)
        assertTrue(interruptions.active.value.isEmpty())
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
    }

    @Test
    fun `a stay-paused interruption in the chain suppresses an always-resume one`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        // Background = stay paused (never auto-resume); focus = always resume.
        val config = InterruptionConfig(
            backgroundPolicy = BackgroundPolicy.PauseAndStayPaused,
            audioFocusPolicy = AudioFocusPolicy.AlwaysResume,
            headphonesPolicy = HeadphonesPolicy.Ignore,
        )
        val h = handler(config, player)

        h.focusLost()   // pauses; on its own would AlwaysResume
        h.background()  // stacks a "never resume" interruption
        assertEquals(1, player.pauseCallCount)

        h.focusRegained(systemAllowsResume = true) // background still active → no resume
        assertEquals(1, player.playCallCount)

        h.foreground() // chain empties, but the strictest policy (Never) wins
        assertEquals(1, player.playCallCount) // never auto-resumes
        assertEquals(PlaybackStatus.Paused, player.state.value.status)
    }

    // ── Audio ownership on resume ────────────────────────────────────────────

    @Test
    fun `resume re-acquires audio ownership before playing`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val session = FakeAudioSession() // grants by default
        val h = handler(InterruptionConfig.VideoLesson, player, audioSession = session)

        h.focusLost()
        h.focusRegained(systemAllowsResume = true)

        assertEquals(2, player.playCallCount)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
    }

    @Test
    fun `resume is blocked when audio ownership is denied`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val session = FakeAudioSession().apply { grantOwnership = false } // another app holds it
        val h = handler(InterruptionConfig.VideoLesson, player, audioSession = session)

        h.focusLost()
        assertEquals(1, player.pauseCallCount)

        h.focusRegained(systemAllowsResume = true)

        assertEquals(1, player.playCallCount) // no resume — ownership denied
        assertEquals(PlaybackStatus.Paused, player.state.value.status)
    }

    // ── Ducking ──────────────────────────────────────────────────────────────

    @Test
    fun `duck lowers volume then restores it on end`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.MediaPlayerDefault, player) // LowerVolume(0.2)

        h.onEvent(InterruptionEvent.DuckBegan)
        assertEquals(0.2f, player.state.value.volume)
        assertEquals(PlaybackStatus.Playing, player.state.value.status) // never pauses

        h.onEvent(InterruptionEvent.DuckEnded)
        assertEquals(1f, player.state.value.volume) // restored to pre-duck volume
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
    }

    @Test
    fun `duck is a no-op when duckPolicy is Ignore`() {
        val player = FakePlayer().also { it.loadAndPlay() }
        val h = handler(InterruptionConfig.Uninterruptible, player) // duckPolicy = Ignore

        h.onEvent(InterruptionEvent.DuckBegan)

        assertEquals(0, player.setVolumeCallCount)
        assertEquals(1f, player.state.value.volume)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
    }
}
