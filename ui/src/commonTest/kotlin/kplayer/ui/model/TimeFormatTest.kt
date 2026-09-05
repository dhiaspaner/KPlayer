package kplayer.ui.model

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeFormatTest {

    @Test
    fun formats_below_an_hour_as_m_ss() {
        assertEquals("0:05", formatPlaybackTime(5_000))
        assertEquals("1:23", formatPlaybackTime(83_000))
        assertEquals("59:59", formatPlaybackTime(3_599_000))
    }

    @Test
    fun formats_an_hour_and_over_as_h_mm_ss() {
        assertEquals("1:00:00", formatPlaybackTime(3_600_000))
        assertEquals("2:05:07", formatPlaybackTime(7_507_000))
    }

    @Test
    fun unknown_and_negative_durations_render_as_zero() {
        // The engine reports 0 before the media is prepared.
        assertEquals("0:00", formatPlaybackTime(0))
        assertEquals("0:00", formatPlaybackTime(-1))
    }
}
