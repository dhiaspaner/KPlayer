package com.dhiachemingui.statemachine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The value semantics the rest of the machine leans on.
 *
 * [Node] compares by `id` alone, and [MachineState] is a data class over nodes — so
 * `Dwelling(Light.Red)` built by hand equals the one a transition committed. Tests
 * elsewhere assert states that way; these are what make that legal.
 */
class GraphIdentityTest {

    private fun lights() = graph {
        initialState(Light.Red)
        state(Light.Red) { on<Signal.Go> { transitionTo(Light.Green) } }
        state(Light.Green) { on<Signal.Halt> { transitionTo(Light.Red) } }
    }

    // ── Node and MachineState ─────────────────────────────────────────────────

    @Test
    fun `nodes compare by id and ignore their hooks`() {
        val bare = Node(Light.Red)
        val decorated = Node(Light.Red).apply {
            onEnter = StateVisitor { _, _ -> }
            decision = Decision { _, _ -> Signal.Go }
        }

        assertEquals(bare, decorated)
        assertEquals(bare.hashCode(), decorated.hashCode())
    }

    @Test
    fun `a node with a different id is a different node`() {
        assertNotEquals(Node(Light.Red), Node(Light.Green))
    }

    @Test
    fun `a hand-built Dwelling equals the one a transition commits`() {
        val machine = lights().start()

        machine.consume(Signal.Go)

        assertEquals(MachineState.Dwelling(Light.Green), machine.currentState)
    }

    @Test
    fun `Inactive carries the sentinel InactiveState`() {
        assertEquals(MachineState.InactiveState, MachineState.Inactive().id)
    }

    @Test
    fun `Traversing identifies itself by the pair of endpoints`() {
        val traversing = MachineState.Traversing(Light.Red to Light.Green)

        assertEquals(
            MachineState.CompoundState(Light.Red, Light.Green),
            traversing.id,
        )
    }

    @Test
    fun `Traversing keeps the event that triggered it`() {
        val traversing = MachineState.Traversing(Light.Red to Light.Green, trigger = Signal.Go)

        assertEquals(Signal.Go, traversing.trigger)
    }

    // ── Graph ─────────────────────────────────────────────────────────────────

    @Test
    fun `two graphs built from the same declaration are equal`() {
        assertEquals(lights(), lights())
        assertEquals(lights().hashCode(), lights().hashCode())
    }

    @Test
    fun `graphs that have moved to different states are not equal`() {
        val a = lights().start()
        val b = lights().start()

        a.consume(Signal.Go)

        assertNotEquals(a, b)
    }

    @Test
    fun `a graph with a different node set is not equal`() {
        val extra = graph {
            initialState(Light.Red)
            state(Light.Red) { on<Signal.Go> { transitionTo(Light.Green) } }
            state(Light.Green) { on<Signal.Halt> { transitionTo(Light.Red) } }
            state(Light.Yellow) {}
        }

        assertNotEquals(lights(), extra)
    }

    @Test
    fun `a graph never equals a non-graph`() {
        assertTrue(lights() != Any())
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    @Test
    fun `currentStateName names the state rather than the wrapper`() {
        val machine = lights().start()

        assertEquals("Red", machine.currentStateName)
    }

    @Test
    fun `toString names the dwell and its state`() {
        val machine = lights().start()

        assertEquals("Dwelling on Red", machine.toString())
    }

    @Test
    fun `toString renders an inactive graph without a state`() {
        assertEquals("Inactive", lights().toString())
    }

    @Test
    fun `toString renders a traversal as an arrow between endpoints`() {
        val machine = lights().start()
        machine.currentState = MachineState.Traversing(Light.Red to Light.Green)

        assertEquals("Traversing from Red -> Green", machine.toString())
    }
}
