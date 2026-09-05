package kplayer.observers

import kplayer.interruption.InterruptionCause
import kplayer.interruption.InterruptionEvent
import kplayer.observers.OutputRoute.Companion.DATA_SOURCE_HEADPHONES
import kplayer.observers.OutputRoute.Companion.DATA_SOURCE_INTERNAL_SPEAKER
import kplayer.observers.OutputRoute.Companion.TRANSPORT_BUILT_IN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop route classification, asserted without an audio device.
 *
 * This is why the decision was split out of the CoreAudio plumbing: plugging and
 * unplugging headphones is not something a test can do, but deciding what a given
 * pair of routes *means* is pure arithmetic.
 *
 * Runs everywhere — nothing here touches a native library.
 */
class OutputRouteTest {

    private val builtInSpeaker =
        OutputRoute(deviceId = 1, transportType = TRANSPORT_BUILT_IN, dataSource = DATA_SOURCE_INTERNAL_SPEAKER)

    /** Same device, same transport — only the data source says headphones. */
    private val headphoneJack =
        OutputRoute(deviceId = 1, transportType = TRANSPORT_BUILT_IN, dataSource = DATA_SOURCE_HEADPHONES)

    private val airPods =
        OutputRoute(deviceId = 2, transportType = 0x626C7565 /* 'blue' */, dataSource = null)

    private val usbInterface =
        OutputRoute(deviceId = 3, transportType = 0x75736220 /* 'usb ' */, dataSource = null)

    private val hdmiTv =
        OutputRoute(deviceId = 4, transportType = 0x68646D69 /* 'hdmi' */, dataSource = null)

    // ── isExternal ────────────────────────────────────────────────────────────

    @Test
    fun `the built-in speaker is not external`() {
        assertFalse(builtInSpeaker.isExternal)
    }

    /**
     * The case a device-only implementation misses entirely: the 3.5mm jack does
     * not create a device and does not change the transport type, so `'bltn'`
     * alone would read as "still on the speaker" with headphones plugged in.
     */
    @Test
    fun `the headphone jack is external despite a built-in transport`() {
        assertTrue(
            headphoneJack.isExternal,
            "a built-in device with the headphone data source is still headphones",
        )
    }

    @Test
    fun `every non built-in transport is external`() {
        listOf(airPods, usbInterface, hdmiTv).forEach {
            assertTrue(it.isExternal, "transport ${it.transportType} should be external")
        }
    }

    @Test
    fun `a device with no data source is judged on transport alone`() {
        val unknownBuiltIn = OutputRoute(deviceId = 9, transportType = TRANSPORT_BUILT_IN, dataSource = null)

        assertFalse(unknownBuiltIn.isExternal)
    }

    // ── The transitions that matter ───────────────────────────────────────────

    @Test
    fun `unplugging headphones begins an interruption`() {
        assertEquals(
            InterruptionEvent.Began(InterruptionCause.HeadphonesDisconnected),
            outputRouteInterruption(headphoneJack, builtInSpeaker),
        )
    }

    @Test
    fun `AirPods going away begins an interruption`() {
        assertEquals(
            InterruptionEvent.Began(InterruptionCause.HeadphonesDisconnected),
            outputRouteInterruption(airPods, builtInSpeaker),
        )
    }

    @Test
    fun `plugging headphones in ends the interruption`() {
        assertEquals(
            InterruptionEvent.Ended(InterruptionCause.HeadphonesDisconnected),
            outputRouteInterruption(builtInSpeaker, airPods),
        )
    }

    /**
     * The cause is broader than its name on every platform — iOS reports it for
     * any `OldDeviceUnavailable` — so losing an HDMI TV is the same event to a
     * media player as losing a pair of headphones.
     */
    @Test
    fun `losing an HDMI output counts the same as losing headphones`() {
        assertEquals(
            InterruptionEvent.Began(InterruptionCause.HeadphonesDisconnected),
            outputRouteInterruption(hdmiTv, builtInSpeaker),
        )
    }

    // ── The changes that are not interruptions ────────────────────────────────

    @Test
    fun `swapping one pair of headphones for another is not an interruption`() {
        // The user never stopped listening on something external, so nothing
        // should reach the policy engine — a pause here would be gratuitous.
        assertNull(outputRouteInterruption(airPods, usbInterface))
    }

    @Test
    fun `a change that leaves the route internal is not an interruption`() {
        val otherInternal = builtInSpeaker.copy(deviceId = 7)

        assertNull(outputRouteInterruption(builtInSpeaker, otherInternal))
    }

    @Test
    fun `an identical route reports nothing`() {
        assertNull(outputRouteInterruption(builtInSpeaker, builtInSpeaker))
        assertNull(outputRouteInterruption(airPods, airPods))
    }

    /**
     * A round trip must produce a matched pair. An unbalanced Began would sit in
     * the handler's active set forever and block every later auto-resume — the
     * exact failure the iOS session's route-change note describes.
     */
    @Test
    fun `unplugging and replugging produces a matched pair`() {
        assertEquals(
            InterruptionEvent.Began(InterruptionCause.HeadphonesDisconnected),
            outputRouteInterruption(headphoneJack, builtInSpeaker),
        )
        assertEquals(
            InterruptionEvent.Ended(InterruptionCause.HeadphonesDisconnected),
            outputRouteInterruption(builtInSpeaker, headphoneJack),
        )
    }
}
