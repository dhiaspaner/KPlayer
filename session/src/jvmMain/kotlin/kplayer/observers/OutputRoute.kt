package kplayer.observers

import kplayer.interruption.InterruptionCause
import kplayer.interruption.InterruptionEvent

/**
 * Where desktop audio is currently going, as plain data.
 *
 * Three CoreAudio properties, because no single one of them answers the
 * question. Switching to AirPods changes the *device*; plugging into the 3.5mm
 * jack does not — the device stays the built-in output and only its **data
 * source** changes. An implementation watching either one alone misses half the
 * cases.
 *
 * @param deviceId the default output `AudioObjectID`. Identity only; the number
 *   itself means nothing across reboots.
 * @param transportType `kAudioDevicePropertyTransportType`, a FourCC — `'bltn'`
 *   for built-in, `'blue'` for Bluetooth, `'usb '`, `'hdmi'`, and so on.
 * @param dataSource `kAudioDevicePropertyDataSource`, a FourCC, or `null` on a
 *   device that does not publish one. `'ispk'` is the internal speaker and
 *   `'hdpn'` the headphone jack.
 */
internal data class OutputRoute(
    val deviceId: Int,
    val transportType: Int,
    val dataSource: Int?,
) {

    /**
     * Whether the user is listening on something other than the machine's own
     * speaker — headphones, AirPods, a USB interface, a TV over HDMI.
     *
     * The two conditions are not redundant: a non-built-in transport catches an
     * external *device*, and the headphone data source catches the jack on the
     * built-in device, which reports `'bltn'` either way.
     */
    val isExternal: Boolean
        get() = transportType != TRANSPORT_BUILT_IN || dataSource == DATA_SOURCE_HEADPHONES

    companion object {
        /** `kAudioDeviceTransportTypeBuiltIn`. */
        const val TRANSPORT_BUILT_IN = 0x626C746E // 'bltn'

        /** The headphone jack, as reported by the built-in output's data source. */
        const val DATA_SOURCE_HEADPHONES = 0x6864706E // 'hdpn'

        /** The internal speaker. Named for tests and for reading the logs. */
        const val DATA_SOURCE_INTERNAL_SPEAKER = 0x6973706B // 'ispk'
    }
}

/**
 * The interruption a change of output route represents, or `null` for the
 * changes that are not interruptions at all.
 *
 * The desktop counterpart of `IosHardwareObserver.routeChangeInterruption`, and
 * deliberately the same shape: of everything CoreAudio will report, only two
 * transitions mean anything to playback. A sample-rate change, a volume change or
 * a switch between two pairs of headphones must not read as an unplug.
 *
 * - **external → built-in** is the output the user was listening on going away:
 *   headphones out, AirPods back in the case, the USB interface unplugged.
 * - **built-in → external** is the reverse, so a policy that resumes on
 *   reconnect has an `Ended` to act on.
 *
 * [InterruptionCause.HeadphonesDisconnected] is broader than its name on every
 * platform — iOS reports it for any `OldDeviceUnavailable`, Android for any
 * becoming-noisy broadcast — and this matches that: losing an HDMI TV is the
 * same event to a media player as losing a pair of headphones.
 *
 * Pure, so the classification can be tested without an audio device, and
 * separate from the CoreAudio plumbing for exactly that reason.
 */
internal fun outputRouteInterruption(
    previous: OutputRoute,
    current: OutputRoute,
): InterruptionEvent? = when {
    previous.isExternal && !current.isExternal ->
        InterruptionEvent.Began(InterruptionCause.HeadphonesDisconnected)

    !previous.isExternal && current.isExternal ->
        InterruptionEvent.Ended(InterruptionCause.HeadphonesDisconnected)

    else -> null
}
