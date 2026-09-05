package com.dhiachemingui.kplayer.sample

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import sample.SampleApp

/**
 * Web entry point.
 *
 * No context or session setup: `appContext` is Android-only, and `WebAudioSession` has
 * nothing to acquire because the browser arbitrates audio itself.
 *
 * Video renders through Compose's HTML interop: `WebVideoPlayer` decodes into a real
 * `<video>` element and `NativeVideoSurface`'s web actual composes that element into
 * the Compose scene with `WebElementView`, so the shared control overlay draws over it
 * exactly as it does on Android and iOS. Audio on web is fully working too.
 *
 * One browser rule worth knowing when trying this: playback before any user gesture is
 * rejected by autoplay policy, and both HTML engines surface that as
 * `PlaybackFeedback.Failed` rather than failing silently. Click something first.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        SampleApp()
    }
}
