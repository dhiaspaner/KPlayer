package kplayer.core.audio

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kplayer.appContext

actual fun createAudioSession(): AudioSession =
    AndroidAudioSession(appContext)

/**
 * Audio ownership on Android, expressed as an audio-focus request.
 *
 * Three separate mappings feed this, and keeping them separate is the point:
 *
 * - [AudioSessionMode] → `AudioAttributes` ([platformAudioAttributesFor])
 * - [AudioCoexistence] → focus gain ([focusPlanFor])
 * - focus callbacks → [AudioInterruption] ([FocusChangeTranslator])
 *
 * [AudioOutputPreference] has no mapping here on purpose. Android routes
 * playback to the loudspeaker already, for `USAGE_VOICE_COMMUNICATION` too; the
 * only way to force it is `AudioManager.setCommunicationDevice`, which mutates
 * device-wide routing that outlives this session. Honouring `Speaker` that way
 * would be a bigger side effect than the preference asks for, so the platform
 * ignores it — the same way desktop ignores [AudioCoexistence].
 */
internal class AndroidAudioSession(
    private val context: Context,
) : AudioSession {

    private val _interruptions = MutableSharedFlow<AudioInterruption>(extraBufferCapacity = 4)
    override val interruptions: Flow<AudioInterruption> = _interruptions

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private var focusRequest: AudioFocusRequest? = null
    private var lastRequestedConfig: AudioSessionConfig? = null

    private val focusChanges = FocusChangeTranslator()

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        focusChanges.translate(focusChange).forEach(_interruptions::tryEmit)
    }

    override fun reacquire(): Boolean = lastRequestedConfig?.let { acquire(it) } ?: true

    override fun acquire(config: AudioSessionConfig): Boolean {
        lastRequestedConfig = config

        val plan = focusPlanFor(config.coexistence)

        // Coexisting with others: hold no focus request (so we never pause other
        // apps) and the system won't notify us to stop. Playback always proceeds.
        if (!plan.requestsFocus) {
            focusRequest = null
            focusChanges.endDuck().forEach(_interruptions::tryEmit)
            return true
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(plan.focusGain)
                .setAudioAttributes(platformAudioAttributesFor(config.mode))
                .setOnAudioFocusChangeListener(focusChangeListener)
                // Always false, and not derived from AudioCoexistence — the two
                // are opposite directions. AudioCoexistence says how *we* treat
                // *other* apps; this says how we react to being ducked, and
                // `true` means "don't duck me, send LOSS_TRANSIENT and I'll
                // pause". This used to be `coexistence != Duck`, so the default
                // Exclusive config asked never to be told about ducking, and
                // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK stopped arriving: the whole
                // DuckBegan/DuckEnded machine was dead on O+, and DuckPolicy
                // .LowerVolume — the library default — silently became a pause.
                .setWillPauseWhenDucked(false)
                .build()

            focusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                plan.focusGain,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    override fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
        // We will get no further callbacks, so a duck in progress can never be
        // ended by one — close it here or the handler holds our volume down for
        // good and the next session's AUDIOFOCUS_GAIN reads as a stale duck end.
        focusChanges.endDuck().forEach(_interruptions::tryEmit)
    }
}
