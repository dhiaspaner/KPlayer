package kplayer.observers

import kplayer.interruption.PlaybackInterruptionHandler

interface LifecycleObserver : InterruptionObserver

expect fun createLifecycleObserver(handler: PlaybackInterruptionHandler): LifecycleObserver
