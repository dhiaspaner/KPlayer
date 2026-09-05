package com.dhiachemingui.kplayer.sample

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import sample.SampleApp

/**
 * Desktop entry point.
 *
 * No `initializeContext` counterpart to `MainActivity`: `appContext` is Android-only,
 * and the desktop audio session has nothing to acquire (see `DesktopAudioSession`).
 *
 * **Video does not play here yet.** `:video`'s JVM actual is still a rejecting stub, so
 * `NativeVideoSurface` draws its configured background and the transport reports
 * `PlaybackFeedback.Rejected`. What this window *does* exercise is the whole UI layer —
 * templates, control slots, the seek rule, the policy editor — against a real Compose
 * runtime rather than a preview. Desktop audio works: see `DesktopAudioPlayer`.
 *
 * The window opens at a 16:9-friendly size so the normal player screen is not letterboxed
 * by the default square-ish window.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "kplayer — sample",
        state = rememberWindowState(width = 1100.dp, height = 760.dp),
    ) {
        SampleApp()
    }
}
