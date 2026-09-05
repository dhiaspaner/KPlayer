package com.dhiachemingui.statemachine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Before the first transition: what a graph does between [graph] and [Graph.start],
 * and how entering a node directly differs from arriving along an edge.
 *
 * The distinction matters because [Graph.transitionTo] is the escape hatch the media
 * backends use for "valid from anywhere" moves — failure and release — and those
 * happen to be the moves most likely to land on a node while the machine has not
 * started yet.
 */
class GraphLifecycleTest {

    private fun lights(
        onRedEnter: (Event?) -> Unit = {},
        onRedExit: (Event?) -> Unit = {},
        greenDecision: Event? = null,
    ) = graph {
        initialState(Light.Red)
        state(Light.Red) {
            onEnter { _, trigger -> onRedEnter(trigger) }
            onExit { _, trigger -> onRedExit(trigger) }
            on<Signal.Go> { transitionTo(Light.Green) }
        }
        state(Light.Green) {
            greenDecision?.let { decision { _, _ -> it } }
            on<Signal.Halt> { transitionTo(Light.Red) }
        }
        state(Light.Yellow) {
            on<Signal.Halt> { transitionTo(Light.Red) }
        }
    }

    @Test
    fun `a graph that has not started is Inactive`() {
        val machine = lights()

        assertIs<MachineState.Inactive>(machine.currentState)
    }

    @Test
    fun `consume before start is ignored`() {
        var entered = false
        val machine = lights(onRedEnter = { entered = true })

        machine.consume(Signal.Go)

        assertIs<MachineState.Inactive>(machine.currentState)
        assertFalse(entered, "nothing should have been entered before start")
    }

    @Test
    fun `start enters the initial node and runs onEnter with no trigger`() {
        var entries = 0
        var trigger: Event? = Signal.Go
        val machine = lights(onRedEnter = { entries++; trigger = it })

        machine.start()

        assertEquals(Light.Red, machine.currentState.id)
        assertEquals(1, entries)
        assertEquals(null, trigger, "start has no event behind it")
    }

    @Test
    fun `start can override the declared initial state`() {
        val machine = lights()

        machine.start(MachineState.Dwelling(Light.Yellow))

        assertEquals(Light.Yellow, machine.currentState.id)
    }

    @Test
    fun `start rejects a Traversing starting state`() {
        val machine = lights()

        assertFailsWith<IllegalArgumentException> {
            machine.start(MachineState.Traversing(Light.Red to Light.Green))
        }
    }

    /**
     * An initial state that was never declared as a `state { }` leaves the machine
     * Inactive rather than inventing a node for it — the builder only knows the
     * nodes the DSL named.
     */
    @Test
    fun `start on an undeclared initial state leaves the machine Inactive`() {
        val machine = graph {
            initialState(Light.Red)
            state(Light.Green) {
                on<Signal.Halt> { transitionTo(Light.Yellow) }
            }
        }

        machine.start()

        assertIs<MachineState.Inactive>(machine.currentState)
    }

    /**
     * From Inactive there is no source node to leave, so the move is a direct entry:
     * no edge is traversed and no `onExit` runs anywhere.
     */
    @Test
    fun `transitionTo from Inactive enters the node directly`() {
        var exits = 0
        val machine = lights(onRedExit = { exits++ })

        val landed = machine.transitionTo(Light.Green)

        assertEquals(Light.Green, landed)
        assertEquals(Light.Green, machine.currentState.id)
        assertEquals(0, exits)
    }

    /**
     * The counterpart to the direct entry above: once dwelling there *is* a node to
     * leave, so the same call traverses an edge and the source's `onExit` runs.
     */
    @Test
    fun `transitionTo while dwelling leaves the source node properly`() {
        var exits = 0
        val machine = lights(onRedExit = { exits++ }).start()

        machine.transitionTo(Light.Green)

        assertEquals(Light.Green, machine.currentState.id)
        assertEquals(1, exits)
    }

    @Test
    fun `transitionTo still refuses a state the graph does not contain even while Inactive`() {
        val machine = lights()

        assertEquals(null, machine.transitionTo(Parity.Even))
        assertIs<MachineState.Inactive>(machine.currentState)
    }

    /**
     * A decision fires on entry *along an edge*. Direct entry — [Graph.start] and a
     * [Graph.transitionTo] out of Inactive — deliberately does not run it, so
     * restoring a machine into a state cannot immediately fire it back out again.
     */
    @Test
    fun `a decision does not fire on direct entry`() {
        val machine = lights(greenDecision = Signal.Halt)

        machine.transitionTo(Light.Green)

        assertEquals(
            Light.Green,
            machine.currentState.id,
            "the decision fired on a direct entry and bounced the machine to Red",
        )
    }

    @Test
    fun `a decision does fire when the same node is reached along an edge`() {
        val machine = lights(greenDecision = Signal.Halt).start()

        machine.consume(Signal.Go)

        assertEquals(
            Light.Red,
            machine.currentState.id,
            "Green's decision should have fired Halt on arrival",
        )
    }

    @Test
    fun `withTransitionLock is re-entrant so consume works inside it`() {
        val machine = lights().start()

        val landed = machine.withTransitionLock {
            machine.consume(Signal.Go)
            machine.currentState.id
        }

        assertEquals(Light.Green, landed)
    }

    @Test
    fun `withTransitionLock returns the block's value`() {
        val machine = lights().start()

        assertTrue(machine.withTransitionLock { true })
    }
}
