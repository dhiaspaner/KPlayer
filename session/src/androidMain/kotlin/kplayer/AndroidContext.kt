package kplayer

import android.content.Context

/**
 * Application context used by the Android implementations across the kplayer
 * modules (audio focus, the becoming-noisy receiver, the ExoPlayer instance).
 *
 * Lives in `:audio` because that is the lowest module in the stack, so every
 * module above it can read it. Assignment stays private to this file — call
 * [initializeContext] once from `Application.onCreate` or your launcher
 * `Activity` before constructing a player.
 */
lateinit var appContext: Context
    private set

fun initializeContext(context: Context) {
    appContext = context.applicationContext
}
