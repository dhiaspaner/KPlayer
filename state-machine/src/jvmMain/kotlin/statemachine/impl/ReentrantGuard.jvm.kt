package com.dhiachemingui.statemachine.impl

import java.util.concurrent.locks.ReentrantLock

internal actual class ReentrantGuard actual constructor() {
    private val lock = ReentrantLock()

    actual fun lock() = lock.lock()
    actual fun unlock() = lock.unlock()
}
