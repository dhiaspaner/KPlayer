package com.dhiachemingui.statemachine.impl

/**
 * A **re-entrant**, non-suspending mutual-exclusion primitive.
 *
 * The graph's transition path is synchronous by contract: a caller must be able to
 * `consume(event)` and read the resulting state on the next line. That rules out
 * `kotlinx.coroutines.sync.Mutex`, whose `lock()` suspends, and it rules out a
 * non-re-entrant lock, because a [com.dhiachemingui.statemachine.Decision] re-enters
 * `consume` from inside the transition it is completing — a plain lock would deadlock
 * against itself.
 *
 * Semantics, on every platform:
 * - the owning thread may [lock] any number of times and must [unlock] the same number;
 * - a different thread blocks until the owner's hold count reaches zero, so it never
 *   observes a half-applied transition.
 *
 * On single-threaded platforms (wasmJs) there is nothing to exclude and the
 * implementation degenerates to a hold counter.
 */
internal expect class ReentrantGuard() {
    fun lock()
    fun unlock()
}

/** Runs [block] holding the guard, releasing it even if [block] throws. */
internal inline fun <T> ReentrantGuard.withGuard(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
