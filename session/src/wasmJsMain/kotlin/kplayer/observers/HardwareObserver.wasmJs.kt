package kplayer.observers

import kplayer.interruption.PlaybackInterruptionHandler

/**
 * The web platform exposes no headphone-disconnect signal.
 *
 * `navigator.mediaDevices.devicechange` fires for capture devices and needs
 * microphone permission to reveal anything useful, so there is no way to detect a
 * route change to the speaker. Browsers also do not pause on unplug, so there is
 * no native behaviour to match.
 */
actual fun createHardwareObserver(handler: PlaybackInterruptionHandler): HardwareObserver =
    object : HardwareObserver {
        override fun start() = Unit
        override fun stop() = Unit
    }
