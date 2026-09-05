package com.dhiachemingui.statemachine.impl

/**
 * wasmJs runs on one thread, so there is no other thread to exclude. The hold count
 * is kept anyway: it makes an unbalanced lock/unlock fail here exactly as it would on
 * a threaded platform, rather than only showing up on Android or iOS.
 */
internal actual class ReentrantGuard actual constructor() {
    private var holds = 0

    actual fun lock() {
        holds++
    }

    actual fun unlock() {
        check(holds > 0) { "unlock() without a matching lock()" }
        holds--
    }
}
