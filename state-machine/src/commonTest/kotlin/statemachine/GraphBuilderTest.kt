package com.dhiachemingui.statemachine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An event carrying a payload — the action and onEnter both get the instance. */
private data class Dim(val level: Int) : Event

/** A second payload event type used only to prove dispatch is keyed per class. */
private data class Brighten(val level: Int) : Event

/**
 * What the `graph { }` DSL actually builds.
 *
 * The transition tests drive a machine that is already wired; these cover the wiring
 * itself — which nodes exist, which edges carry actions, and how an event is matched
 * to an edge.
 */
class GraphBuilderTest {

    // ── Node declaration ──────────────────────────────────────────────────────

    /**
     * `allows` is the only way to name a node without giving it a `state { }` block
     * of its own, which is what makes it reachable by [Graph.transitionTo] at all.
     */
    @Test
    fun `allows declares a node that transitionTo can reach`() {
        val machine = graph {
            initialState(Light.Red)
            state(Light.Red) { allows(Light.Yellow) }
        }.start()

        assertEquals(Light.Yellow, machine.transitionTo(Light.Yellow))
    }

    @Test
    fun `a node that is never named is unreachable`() {
        val machine = graph {
            initialState(Light.Red)
            state(Light.Red) {}
        }.start()

        assertNull(machine.transitionTo(Light.Yellow))
        assertEquals(Light.Red, machine.currentState.id)
    }

    @Test
    fun `an event edge declares its destination node too`() {
        val machine = graph {
            initialState(Light.Red)
            state(Light.Red) { on<Signal.Go> { transitionTo(Light.Green) } }
        }.start()

        // Green got a node purely by being an edge's destination.
        assertEquals(Light.Green, machine.transitionTo(Light.Green))
    }

    // ── Edges declared by destination ─────────────────────────────────────────

    /**
     * `onTransitionTo` attaches an action to a move that no event triggers, so a
     * [Graph.transitionTo] made from outside the graph still runs it.
     */
    @Test
    fun `an edge declared with onTransitionTo runs its action on transitionTo`() {
        var ran = 0
        val machine = graph {
            initialState(Light.Red)
            state(Light.Red) {
                onTransitionTo(Light.Green) { execute { ran++ } }
            }
        }.start()

        machine.transitionTo(Light.Green)

        assertEquals(1, ran)
        assertEquals(Light.Green, machine.currentState.id)
    }

    @Test
    fun `an edge's onEnter and onExit bracket its action`() {
        val order = mutableListOf<String>()
        val machine = graph {
            initialState(Light.Red)
            state(Light.Red) {
                onExit { _, _ -> order += "node onExit" }
                on<Signal.Go> {
                    onEnter { order += "edge onEnter" }
                    onExit { order += "edge onExit" }
                    transitionTo(Light.Green) { order += "action" }
                }
            }
            state(Light.Green) {
                onEnter { _, _ -> order += "node onEnter" }
            }
        }.start()

        machine.consume(Signal.Go)

        assertEquals(
            listOf("node onExit", "edge onEnter", "action", "edge onExit", "node onEnter"),
            order,
        )
    }

    // ── Event matching ────────────────────────────────────────────────────────

    @Test
    fun `the instance overload of on registers the event's own class`() {
        val machine = graph {
            initialState(Light.Red)
            state(Light.Red) { on(Signal.Go) { transitionTo(Light.Green) } }
        }.start()

        machine.consume(Signal.Go)

        assertEquals(Light.Green, machine.currentState.id)
    }

    /**
     * Dispatch is an exact-class lookup — `edgeTriggers[event::class]` — not an
     * `is` check. Registering the sealed parent does **not** catch its children,
     * which is the DSL's sharpest edge: the graph compiles and then silently
     * ignores every event.
     */
    @Test
    fun `registering a supertype does not catch its subtypes`() {
        val machine = graph {
            initialState(Light.Red)
            state(Light.Red) { on<Signal> { transitionTo(Light.Green) } }
        }.start()

        machine.consume(Signal.Go)

        assertEquals(
            Light.Red,
            machine.currentState.id,
            "on<Signal> should not have matched Signal.Go — dispatch is by exact class",
        )
    }

    @Test
    fun `two payload event types on one node stay on their own edges`() {
        val machine = graph {
            initialState(Light.Red)
            state(Light.Red) {
                on<Dim> { transitionTo(Light.Yellow) }
                on<Brighten> { transitionTo(Light.Green) }
            }
        }.start()

        machine.consume(Brighten(3))

        assertEquals(Light.Green, machine.currentState.id)
    }

    @Test
    fun `an event instance reaches both the edge action and the target onEnter`() {
        var seenByAction: Event? = null
        var seenByEnter: Event? = null
        val machine = graph {
            initialState(Light.Red)
            state(Light.Red) {
                on<Dim> { transitionTo(Light.Yellow) { seenByAction = it } }
            }
            state(Light.Yellow) {
                onEnter { _, trigger -> seenByEnter = trigger }
            }
        }.start()

        machine.consume(Dim(level = 7))

        assertEquals(Dim(7), seenByAction)
        assertEquals(Dim(7), seenByEnter)
    }

    @Test
    fun `registering the same event twice keeps the last edge`() {
        val machine = graph {
            initialState(Light.Red)
            state(Light.Red) {
                on<Signal.Go> { transitionTo(Light.Green) }
                on<Signal.Go> { transitionTo(Light.Yellow) }
            }
        }.start()

        machine.consume(Signal.Go)

        assertEquals(Light.Yellow, machine.currentState.id)
    }

    @Test
    fun `an edge back to the same state is a legal self transition`() {
        var entries = 0
        val machine = graph {
            initialState(Parity.Even)
            state(Parity.Even) {
                onEnter { _, _ -> entries++ }
                on<Tick> { transitionTo(Parity.Even) }
            }
        }.start()

        machine.consume(Tick)

        assertEquals(Parity.Even, machine.currentState.id)
        assertEquals(2, entries, "one entry from start and one from the self edge")
    }

    // ── Build-time failure ────────────────────────────────────────────────────

    /**
     * `execute` sets an action but no destination, and the builder dereferences that
     * destination while collecting nodes. Pinned because the failure arrives at
     * `graph { }` — before a single event is dispatched — and the message says
     * nothing about which state is at fault.
     */
    @Test
    fun `an edge with an action but no destination fails while building`() {
        assertFailsWith<NullPointerException> {
            graph {
                initialState(Light.Red)
                state(Light.Red) {
                    on<Signal.Go> { execute { } }
                }
            }
        }
    }

    @Test
    fun `a graph with no states at all starts Inactive and ignores everything`() {
        val machine = graph { }.start()

        machine.consume(Signal.Go)

        assertTrue(machine.currentState is MachineState.Inactive)
    }
}
