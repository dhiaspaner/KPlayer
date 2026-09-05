package kplayer.core.audio

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.Foundation.NSLog
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification

actual fun createAudioSession(): AudioSession = IosAudioSession()

/**
 * Audio ownership on iOS, expressed as an `AVAudioSession` category + activation.
 *
 * Two separations are load-bearing here.
 *
 * **Configuration vs. coexistence vs. routing.** What to apply is decided by the
 * pure [toAvSettings] mapping; this class only applies it. Nothing below reads
 * [AudioSessionConfig] field by field.
 *
 * **Reactivation vs. interruption lifecycle.** Getting the session active again
 * is a recovery action and says nothing about whether an interruption ended.
 * [reacquire] therefore emits nothing at all, and only a real
 * `AVAudioSessionInterruptionNotification` — or the Siri fallback below, which
 * needs a `Began` we actually saw — can produce an [AudioInterruption].
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosAudioSession : AudioSession {

    private val session = AVAudioSession.sharedInstance()

    private val _interruptions = MutableSharedFlow<AudioInterruption>(extraBufferCapacity = 4)
    override val interruptions: Flow<AudioInterruption> = _interruptions

    private var lastRequestedConfig: AudioSessionConfig? = null

    /**
     * True between an interruption `Began` and its `Ended`.
     *
     * The only reason the foreground observer is allowed to synthesise an
     * `Ended`: it proves there is an interruption outstanding that iOS owes us
     * an end for. Mutated only from the main queue, where both observers run.
     */
    private var interrupted = false

    @OptIn(BetaInteropApi::class)
    override fun acquire(config: AudioSessionConfig): Boolean {
        lastRequestedConfig = config
        return applyAndActivate(config)
    }

    /**
     * Re-applies the last configuration and reactivates.
     *
     * Emits nothing. This used to be reached only from the interruption path,
     * where the caller emitted on its behalf; it is now also the recovery used by
     * the foreground observer, and conflating the two is precisely what made
     * foregrounding look like an interruption ending.
     */
    override fun reacquire(): Boolean =
        lastRequestedConfig?.let { applyAndActivate(it) } ?: false

    @OptIn(BetaInteropApi::class)
    override fun release() {
        interrupted = false
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val deactivated = session.setActive(
                active = false,
                withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
                error = error.ptr,
            )
            if (!deactivated) {
                NSLog("IosAudioSession: failed to deactivate session: ${error.value}")
            }
        }
    }

    @OptIn(BetaInteropApi::class)
    private fun applyAndActivate(config: AudioSessionConfig): Boolean = memScoped {
        val settings = config.toAvSettings()

        val categoryError = alloc<ObjCObjectVar<NSError?>>()
        val categoryApplied = session.setCategory(
            category = settings.category,
            mode = settings.mode,
            options = settings.options,
            error = categoryError.ptr,
        )
        if (!categoryApplied) {
            NSLog("IosAudioSession: failed to set category: ${categoryError.value}")
            return@memScoped false
        }

        val activeError = alloc<ObjCObjectVar<NSError?>>()
        val activated = session.setActive(true, activeError.ptr)
        if (!activated) {
            NSLog("IosAudioSession: failed to activate session: ${activeError.value}")
        }
        activated
    }

    private val interruptionObserver = NSNotificationCenter.defaultCenter.addObserverForName(
        name = AVAudioSessionInterruptionNotification,
        `object` = session,
        queue = NSOperationQueue.mainQueue,
    ) { notification ->
        val userInfo = notification?.userInfo ?: return@addObserverForName
        val type = (userInfo[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.unsignedLongValue
            ?: return@addObserverForName

        when (type) {
            AVAudioSessionInterruptionTypeBegan -> {
                interrupted = true
                _interruptions.tryEmit(AudioInterruption.Began)
            }

            AVAudioSessionInterruptionTypeEnded -> {
                interrupted = false

                // The OS tells us whether resuming is appropriate via the
                // .shouldResume option; absent means another app took over, so
                // we should not barge back in. This is what distinguishes
                // RestoreIfPlayingBefore (honors it) from AlwaysResume (ignores it).
                val options = (userInfo[AVAudioSessionInterruptionOptionKey] as? NSNumber)
                    ?.unsignedLongValue ?: 0uL
                val shouldResume = options and AVAudioSessionInterruptionOptionShouldResume != 0uL

                // Reactivate regardless of shouldResume: getting the session
                // usable again is recovery, not resumption, and a session left
                // inactive would fail the *next* deliberate play() too.
                val reactivated = reacquire()

                // Emitted unconditionally, even when reactivation fails. The
                // Ended is what clears AudioFocusLoss from the handler's active
                // set; withholding it on a failed reactivation left the cause
                // active with nothing able to end it, so every later auto-resume
                // was blocked for the life of the player. A failure is reported
                // as "the system does not allow resuming", which is exactly what
                // it means and which the handler already knows how to obey.
                _interruptions.tryEmit(
                    AudioInterruption.Ended(systemAllowsResume = shouldResume && reactivated)
                )
            }
        }
    }
    // Deliberately *not* observed here: AVAudioSessionRouteChangeNotification.
    //
    // A headphone disconnect is a hardware event with its own cause
    // (InterruptionCause.HeadphonesDisconnected) and its own policy, and
    // IosHardwareObserver already reports it as one. Emitting it from here as
    // well made the same unplug arrive twice — once correctly, and once as
    // AudioInterruption.Began, which KMediaManager can only read as
    // AudioFocusLoss. The focus policy pauses by default, so it paused playback
    // over the top of a headphonesPolicy that said Ignore, and the invented loss
    // had no matching Ended, leaving AudioFocusLoss in the handler's active set
    // for good — blocking every later auto-resume. This session reports what the
    // *session* knows: interruptions, and nothing else.

    /**
     * Siri (and some other brief system interruptions) can leave the session
     * deactivated without ever posting an `Ended`. Foregrounding is a reliable
     * point to notice and recover.
     *
     * Gated on [interrupted], which is the concrete reason the user asked for:
     * we saw a `Began`, no `Ended` ever arrived, so there is a real outstanding
     * interruption that this closes. Foregrounding on its own is *not* an
     * interruption ending — the app being brought forward says nothing about the
     * phone call that may still be in progress — and this used to emit
     * `Ended(systemAllowsResume = true)` for every activation regardless. That
     * cleared `AudioFocusLoss` from the handler's active set on return from
     * background, so a player paused by a call could resume straight over the
     * top of it while the call was still running.
     */
    private val appActiveObserver = NSNotificationCenter.defaultCenter.addObserverForName(
        name = UIApplicationDidBecomeActiveNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _ ->
        if (interrupted && reacquire()) {
            interrupted = false
            _interruptions.tryEmit(AudioInterruption.Ended(systemAllowsResume = true))
        }
    }

    fun dispose() {
        NSNotificationCenter.defaultCenter.removeObserver(interruptionObserver)
        NSNotificationCenter.defaultCenter.removeObserver(appActiveObserver)
    }
}
