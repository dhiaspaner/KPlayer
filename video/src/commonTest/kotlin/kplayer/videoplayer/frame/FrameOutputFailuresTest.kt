package kplayer.videoplayer.frame

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three properties every engine used to re-derive, now in one place.
 *
 * Worth pinning because two of them are easy to lose in a refactor and neither
 * loss is visible: a reporter that keeps the *last* reason buries the useful one
 * under whatever the pump hit sixty ticks later, and one that never clears blames
 * the new item for the old item's problem.
 */
class FrameOutputFailuresTest {

    @Test
    fun `the first reason wins and is logged once`() {
        val logged = mutableListOf<String>()
        val failures = FrameOutputFailures(logged::add)

        failures.report("planar buffer")
        failures.report("planar buffer")
        failures.report("something else entirely")

        assertEquals("planar buffer", failures.failure.value)
        assertEquals(1, logged.size, "a pump failing every tick must log once")
        assertTrue(
            logged.single().startsWith("kplayer/frames:"),
            "the line must carry the tag :ui logs the render half under: ${logged.single()}",
        )
    }

    @Test
    fun `clearing lets the next item report its own reason`() {
        val failures = FrameOutputFailures { }

        failures.report("previous item")
        failures.clear()
        assertNull(failures.failure.value)

        failures.report("this item")
        assertEquals("this item", failures.failure.value)
    }

    @Test
    fun `the failure is observable`() {
        val failures = FrameOutputFailures { }
        val seen = mutableListOf<String?>()

        // StateFlow.value reads are what the engines expose; this is the half a
        // plain @Volatile field could not do — a UI collecting the flow.
        seen += failures.failure.value
        failures.report("no output")
        seen += failures.failure.value

        assertEquals(listOf(null, "no output"), seen)
    }
}
