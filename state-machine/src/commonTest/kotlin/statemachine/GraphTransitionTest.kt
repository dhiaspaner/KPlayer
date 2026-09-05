package com.dhiachemingui.statemachine

import com.dhiachemingui.statemachine.impl.MAX_TRANSITION_DEPTH
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Requirement 7 — the transition semantics that existed before the graph became
 * synchronous still hold, now without a dispatcher or a coroutine anywhere in sight.
 * Every test in this file calls the machine from plain, non-suspending test code:
 * that it compiles at all is half the point.
 */
class GraphTransitionTest {

    private fun lights(
        onRedEnter: (Event?) -> Unit = {},
    ) = graph {
        initialState(Light.Red)
        state(Light.Red) {
            onEnter { _, trigger -> onRedEnter(trigger) }
            on<Signal.Go> { transitionTo(Light.Green) }
        }
        state(Light.Green) {
            on<Signal.Slow> { transitionTo(Light.Yellow) }
        }
        state(Light.Yellow) {
            on<Signal.Halt> { transitionTo(Light.Red) }
        }
    }.start()

    @Test
    fun `consume moves along a declared edge`() {
        val machine = lights()

        machine.consume(Signal.Go)

        assertEquals(Light.Green, machine.currentState.id)
    }

    @Test
    fun `consume is synchronous - the new state is readable on the next line`() {
        val machine = lights()

        machine.consume(Signal.Go)
        val immediately = machine.currentState.id

        assertEquals(Light.Green, immediately)
    }

    @Test
    fun `an event with no edge on the current node is ignored`() {
        val machine = lights()

        machine.consume(Signal.Unknown)
        machine.consume(Signal.Halt)

        assertEquals(Light.Red, machine.currentState.id)
    }

    @Test
    fun `onEnter receives the event that caused the transition`() {
        var seen: Event? = null
        val machine = graph {
            initialState(Light.Red)
            state(Light.Red) { on<Signal.Go> { transitionTo(Light.Green) } }
            state(Light.Green) { onEnter { _, trigger -> seen = trigger } }
        }.start()

        machine.consume(Signal.Go)

        assertEquals(Signal.Go, seen)
    }

    @Test
    fun `fail aborts the transition and stays on the source state`() {
        val exits = mutableListOf<State>()
        val machine = graph {
            initialState(Light.Red)
            state(Light.Red) {
                onExit { state, _ -> exits += state }
                on<Signal.Go> { transitionTo(Light.Green) { fail() } }
            }
            state(Light.Green) {}
        }.start()

        machine.consume(Signal.Go)

        assertEquals(Light.Red, machine.currentState.id)
        // Documented asymmetry: onExit has already run by the time an action can veto.
        assertEquals(listOf<State>(Light.Red), exits)
    }

    @Test
    fun `failAndExit also runs the edge onExit`() {
        val edgeExits = mutableListOf<Pair<State, State>>()
        val machine = graph {
            initialState(Light.Red)
            state(Light.Red) {
                on<Signal.Go> {
                    transitionTo(Light.Green) { failAndExit() }
                    onExit { edgeExits += it }
                }
            }
            state(Light.Green) {}
        }.start()

        machine.consume(Signal.Go)

        assertEquals(Light.Red, machine.currentState.id)
        assertEquals(listOf<Pair<State, State>>(Light.Red to Light.Green), edgeExits)
    }

    @Test
    fun `transitionTo reaches a node with no declared edge`() {
        val machine = lights()

        val landed = machine.transitionTo(Light.Yellow)

        assertEquals(Light.Yellow, landed)
        assertEquals(Light.Yellow, machine.currentState.id)
    }

    @Test
    fun `transitionTo returns null for a state the graph does not contain`() {
        val machine = lights()

        assertNull(machine.transitionTo(Parity.Even))
        assertEquals(Light.Red, machine.currentState.id)
    }

    @Test
    fun `edge action runs between the source exit and the target entry`() {
        val order = mutableListOf<String>()
        val machine = graph {
            initialState(Light.Red)
            state(Light.Red) {
                onExit { _, _ -> order += "from.onExit" }
                on<Signal.Go> {
                    transitionTo(Light.Green) { order += "action" }
                    onEnter { order += "edge.onEnter" }
                    onExit { order += "edge.onExit" }
                }
            }
            state(Light.Green) { onEnter { _, _ -> order += "to.onEnter" } }
        }.start()

        machine.consume(Signal.Go)

        assertEquals(
            listOf("from.onExit", "edge.onEnter", "action", "edge.onExit", "to.onEnter"),
            order,
        )
    }

    // ── Requirement 3 & 4 — re-entrant Decision chains ───────────────────────

    @Test
    fun `a decision re-enters consume without deadlocking`() {
        // Green's decision immediately fires Halt, so one consume(Go) must land on Red.
        val machine = graph {
            initialState(Light.Red)
            state(Light.Red) { on<Signal.Go> { transitionTo(Light.Green) } }
            state(Light.Green) {
                decision { _, _ -> Signal.Slow }
                on<Signal.Slow> { transitionTo(Light.Yellow) }
            }
            state(Light.Yellow) { on<Signal.Halt> { transitionTo(Light.Red) } }
        }.start()

        machine.consume(Signal.Go)

        assertEquals(Light.Yellow, machine.currentState.id)
    }

    @Test
    fun `nested transitions finish before the outer consume returns`() {
        val arrivals = mutableListOf<State>()
        val machine = graph {
            initialState(Light.Red)
            state(Light.Red) {
                onEnter { s, _ -> arrivals += s }
                on<Signal.Go> { transitionTo(Light.Green) }
            }
            state(Light.Green) {
                onEnter { s, _ -> arrivals += s }
                decision { _, _ -> Signal.Slow }
                on<Signal.Slow> { transitionTo(Light.Yellow) }
            }
            state(Light.Yellow) {
                onEnter { s, _ -> arrivals += s }
                decision { _, _ -> Signal.Halt }
                on<Signal.Halt> { transitionTo(Light.Red) }
            }
        }.start()

        arrivals.clear()
        machine.consume(Signal.Go)

        // Two nested hops happened inside the single consume() call above.
        assertEquals(listOf<State>(Light.Green, Light.Yellow, Light.Red), arrivals)
        assertEquals(Light.Red, machine.currentState.id)
    }

    @Test
    fun `a cycling decision chain fails with a diagnostic instead of dying on the stack`() {
        val machine = graph {
            initialState(Parity.Even)
            state(Parity.Even) {
                decision { _, _ -> Tick }
                on<Tick> { transitionTo(Parity.Odd) }
            }
            state(Parity.Odd) {
                decision { _, _ -> Tick }
                on<Tick> { transitionTo(Parity.Even) }
            }
        }.start()

        val failure = assertFailsWith<IllegalStateException> { machine.consume(Tick) }

        assertTrue(
            failure.message!!.contains("$MAX_TRANSITION_DEPTH"),
            "expected the depth limit in the message but got: ${failure.message}",
        )
        assertTrue(
            failure.message!!.contains("Decision"),
            "expected the message to name Decision as the cause but got: ${failure.message}",
        )
    }
}
