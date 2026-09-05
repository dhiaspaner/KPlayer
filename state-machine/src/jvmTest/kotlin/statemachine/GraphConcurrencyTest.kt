package com.dhiachemingui.statemachine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Requirements 2, 6 and 8. These need real threads, so they live in `jvmTest` rather
 * than `commonTest` — the semantics they pin down are platform-independent, but
 * `commonTest` has no way to start a second thread.
 */
class GraphConcurrencyTest {

    /** Requirement 2 — two callers cannot interleave around a transition. */
    @Test
    fun `a concurrent caller waits for the whole transition to commit`() {
        val inFlight = AtomicInteger(0)
        val overlaps = AtomicInteger(0)

        val machine = graph {
            initialState(Parity.Even)
            state(Parity.Even) {
                on<Tick> {
                    transitionTo(Parity.Odd) {
                        if (inFlight.incrementAndGet() != 1) overlaps.incrementAndGet()
                        Thread.sleep(20)          // widen the window a race would use
                        inFlight.decrementAndGet()
                    }
                }
            }
            state(Parity.Odd) {
                on<Tick> {
                    transitionTo(Parity.Even) {
                        if (inFlight.incrementAndGet() != 1) overlaps.incrementAndGet()
                        Thread.sleep(20)
                        inFlight.decrementAndGet()
                    }
                }
            }
        }.start()

        val barrier = CyclicBarrier(2)
        val threads = (1..2).map {
            thread {
                barrier.await()
                repeat(10) { machine.consume(Tick) }
            }
        }
        threads.forEach { it.join() }

        assertEquals(0, overlaps.get(), "two callers were inside a transition at once")
        assertEquals(Parity.Even, machine.currentState.id, "20 transitions must land on Even")
    }

    /**
     * Requirement 2, the sharper half: a caller must not be able to read `currentState`,
     * lose the CPU, and then commit a transition computed from a state that has since
     * moved. Thread B's transition has to observe the state thread A left behind.
     */
    @Test
    fun `a transition never commits from a stale read of currentState`() {
        val staleReads = AtomicInteger(0)
        val machine = graph {
            initialState(Parity.Even)
            state(Parity.Even) {
                on<Tick> {
                    transitionTo(Parity.Odd) { Thread.yield() }
                }
                onEnter { _, _ -> }
            }
            state(Parity.Odd) {
                on<Tick> {
                    transitionTo(Parity.Even) { Thread.yield() }
                }
            }
        }.start()

        val lastSeen = AtomicReference<State>(Parity.Even)
        val observerLock = ReentrantLock()

        // Wrap each dispatch so we can check the before/after pair atomically.
        fun tick() = machine.withTransitionLock {
            val before = machine.currentState.id
            machine.consume(Tick)
            val after = machine.currentState.id
            observerLock.withLock {
                if (lastSeen.get() != before) staleReads.incrementAndGet()
                lastSeen.set(after)
            }
        }

        val barrier = CyclicBarrier(4)
        val threads = (1..4).map {
            thread {
                barrier.await()
                repeat(200) { tick() }
            }
        }
        threads.forEach { it.join() }

        assertEquals(0, staleReads.get(), "a transition started from a state nobody was in")
    }

    /** Requirement 8 — stress. Strict alternation is the corruption detector. */
    @Test
    fun `concurrent dispatch never corrupts the transition sequence`() {
        val threads = 8
        val perThread = 500
        val arrivals = ArrayList<State>(threads * perThread)
        val arrivalLock = ReentrantLock()

        val machine = graph {
            initialState(Parity.Even)
            state(Parity.Even) {
                onEnter { s, _ -> arrivalLock.withLock { arrivals += s } }
                on<Tick> { transitionTo(Parity.Odd) { Thread.yield() } }
            }
            state(Parity.Odd) {
                onEnter { s, _ -> arrivalLock.withLock { arrivals += s } }
                on<Tick> { transitionTo(Parity.Even) { Thread.yield() } }
            }
        }.start()

        arrivalLock.withLock { arrivals.clear() }   // drop the start() notification

        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val workers = (1..threads).map {
            thread {
                ready.countDown()
                go.await()
                repeat(perThread) { machine.consume(Tick) }
            }
        }
        ready.await()
        go.countDown()
        workers.forEach { it.join() }

        val total = threads * perThread
        assertEquals(total, arrivals.size, "every consume() must produce exactly one transition")

        // Every event is legal from wherever the machine is, so the arrival sequence
        // must alternate perfectly. Two threads committing off one read would show up
        // here as a repeated state.
        val firstBreak = (1 until arrivals.size).firstOrNull { arrivals[it] == arrivals[it - 1] }
        assertNull(
            firstBreak,
            "sequence broke at index $firstBreak: ${arrivals.getOrNull((firstBreak ?: 1) - 1)} " +
                "then ${arrivals.getOrNull(firstBreak ?: 1)}",
        )

        val expected = if (total % 2 == 0) Parity.Even else Parity.Odd
        assertEquals(expected, machine.currentState.id)
    }

    /** Requirement 8, via coroutines on a real multi-threaded pool rather than raw threads. */
    @Test
    fun `concurrent dispatch from coroutines on Dispatchers Default is also serialized`() = runBlocking {
        val overlaps = AtomicInteger(0)
        val inFlight = AtomicInteger(0)

        val machine = graph {
            initialState(Parity.Even)
            state(Parity.Even) {
                on<Tick> {
                    transitionTo(Parity.Odd) {
                        if (inFlight.incrementAndGet() != 1) overlaps.incrementAndGet()
                        inFlight.decrementAndGet()
                    }
                }
            }
            state(Parity.Odd) {
                on<Tick> {
                    transitionTo(Parity.Even) {
                        if (inFlight.incrementAndGet() != 1) overlaps.incrementAndGet()
                        inFlight.decrementAndGet()
                    }
                }
            }
        }.start()

        withContext(Dispatchers.Default) {
            (1..16).map { async { repeat(250) { machine.consume(Tick) } } }.awaitAll()
        }

        assertEquals(0, overlaps.get())
        assertEquals(Parity.Even, machine.currentState.id, "4000 transitions must land on Even")
    }

    /**
     * Requirement 6 — `Dispatchers.Unconfined` is gone from the graph entirely. There is
     * no dispatcher to pass any more, and a transition dispatched from a thread that has
     * no coroutine context at all still applies synchronously.
     */
    @Test
    fun `a transition applies synchronously on a bare thread with no coroutine context`() {
        val machine = graph {
            initialState(Light.Red)
            state(Light.Red) { on<Signal.Go> { transitionTo(Light.Green) } }
            state(Light.Green) {}
        }.start()

        val observedOnNextLine = AtomicReference<State?>(null)
        thread {
            machine.consume(Signal.Go)
            observedOnNextLine.set(machine.currentState.id)
        }.join()

        assertEquals(Light.Green, observedOnNextLine.get())
    }

    /** Requirement 3, under contention: a nested Decision must not deadlock a waiter. */
    @Test
    fun `a decision chain holds the lock through nested transitions without deadlocking`() {
        val machine = graph {
            initialState(Light.Red)
            state(Light.Red) { on<Signal.Go> { transitionTo(Light.Green) } }
            state(Light.Green) {
                decision { _, _ -> Signal.Slow }
                on<Signal.Slow> { transitionTo(Light.Yellow) }
            }
            state(Light.Yellow) {
                decision { _, _ -> Signal.Halt }
                on<Signal.Halt> { transitionTo(Light.Red) }
            }
        }.start()

        val barrier = CyclicBarrier(4)
        val workers = (1..4).map {
            thread {
                barrier.await()
                repeat(100) { machine.consume(Signal.Go) }
            }
        }
        workers.forEach { it.join(10_000) }

        assertTrue(workers.none { it.isAlive }, "a worker deadlocked on a nested Decision")
        // Each Go runs Red -> Green -> Yellow -> Red, so the machine ends where it began.
        assertEquals(Light.Red, machine.currentState.id)
    }
}
