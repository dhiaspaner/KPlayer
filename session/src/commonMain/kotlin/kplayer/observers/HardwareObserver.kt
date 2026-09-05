package kplayer.observers

import kplayer.interruption.PlaybackInterruptionHandler

interface HardwareObserver : InterruptionObserver

expect fun createHardwareObserver(handler: PlaybackInterruptionHandler): HardwareObserver
