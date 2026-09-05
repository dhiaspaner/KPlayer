package com.dhiachemingui.statemachine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Requirement 5 — outbound notification still works, and stays strictly separate from
 * how incoming events are serialized. The observer buffer is one slot; it is not a
 * mailbox, and nothing in the transition path waits on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphObserverTest {

    private fun lights() = graph {
        initialState(Light.Red)
        state(Light.Red) { on<Signal.Go> { transitionTo(Light.Green) } }
        state(Light.Green) { on<Signal.Slow> { transitionTo(Light.Yellow) } }
        state(Light.Yellow) { on<Signal.Halt> { transitionTo(Light.Red) } }
    }

    @Test
    fun `a collector sees every dwell state`() = runTest(UnconfinedTestDispatcher()) {
        val machine = lights()
        val seen = mutableListOf<State>()
        val job = launch { machine.observeState().collect { seen += it } }

        machine.start()
        machine.consume(Signal.Go)
        machine.consume(Signal.Slow)
        machine.consume(Signal.Halt)
        advanceUntilIdle()

        assertEquals(
            listOf<State>(Light.Red, Light.Green, Light.Yellow, Light.Red),
            seen,
        )
        job.cancel()
    }

    @Test
    fun `observeStateChanges reports traversals as well as dwells`() =
        runTest(UnconfinedTestDispatcher()) {
            val machine = lights().start()
            val seen = mutableListOf<MachineState>()
            val job = launch { machine.observeStateChanges().collect { seen += it } }

            machine.consume(Signal.Go)
            advanceUntilIdle()

            assertTrue(
                seen.any { it is MachineState.Traversing },
                "expected a Traversing notification but saw $seen",
            )
            assertTrue(
                seen.last().let { it is MachineState.Dwelling && it.id == Light.Green },
                "expected to end dwelling on Green but saw $seen",
            )
            job.cancel()
        }

    @Test
    fun `transitions run to completion with nothing collecting`() {
        // No subscriber, no coroutine, no dispatcher: the transition path must not
        // depend on anyone draining the observer flows.
        val machine = lights().start()

        repeat(50) {
            machine.consume(Signal.Go)
            machine.consume(Signal.Slow)
            machine.consume(Signal.Halt)
        }

        assertEquals(Light.Red, machine.currentState.id)
    }

    @Test
    fun `a collector that arrives late is not blocked by the one-slot buffer`() =
        runTest(UnconfinedTestDispatcher()) {
            val machine = lights().start()

            // Emissions with no subscriber are dropped on the floor, not queued.
            repeat(30) {
                machine.consume(Signal.Go)
                machine.consume(Signal.Slow)
                machine.consume(Signal.Halt)
            }

            val seen = mutableListOf<State>()
            val job = launch { machine.observeState().collect { seen += it } }
            advanceUntilIdle()

            // replay = 0, so nothing is replayed to a late subscriber...
            assertEquals(emptyList<State>(), seen)

            // ...but it sees everything from the moment it subscribed.
            machine.consume(Signal.Go)
            advanceUntilIdle()
            assertEquals(listOf<State>(Light.Green), seen)

            job.cancel()
        }
}
