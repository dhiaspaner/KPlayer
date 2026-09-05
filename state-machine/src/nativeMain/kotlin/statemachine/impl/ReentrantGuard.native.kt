package com.dhiachemingui.statemachine.impl

import kotlinx.cinterop.Arena
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.ptr
import platform.posix.PTHREAD_MUTEX_RECURSIVE
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import platform.posix.pthread_mutexattr_destroy
import platform.posix.pthread_mutexattr_init
import platform.posix.pthread_mutexattr_settype
import platform.posix.pthread_mutexattr_t

/**
 * A POSIX mutex created with `PTHREAD_MUTEX_RECURSIVE`, which is the platform's own
 * version of the contract in [ReentrantGuard]: the owning thread may relock freely,
 * any other thread blocks.
 *
 * The [Arena] holding the mutex lives as long as the guard and is never freed — one
 * mutex per `Graph`, and a `Graph` outlives the player that owns it.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual class ReentrantGuard actual constructor() {
    private val arena = Arena()
    private val mutex = arena.alloc<pthread_mutex_t>()

    init {
        val attr = arena.alloc<pthread_mutexattr_t>()
        pthread_mutexattr_init(attr.ptr)
        pthread_mutexattr_settype(attr.ptr, PTHREAD_MUTEX_RECURSIVE.convert())
        pthread_mutex_init(mutex.ptr, attr.ptr)
        pthread_mutexattr_destroy(attr.ptr)
    }

    actual fun lock() {
        pthread_mutex_lock(mutex.ptr)
    }

    actual fun unlock() {
        pthread_mutex_unlock(mutex.ptr)
    }
}
