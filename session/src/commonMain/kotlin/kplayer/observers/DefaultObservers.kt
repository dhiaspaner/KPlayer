package kplayer.observers

import kplayer.interruption.PlaybackInterruptionHandler


fun DefaultObservers(
    handler: PlaybackInterruptionHandler,
) : List<InterruptionObserver> = buildList {
    add(createLifecycleObserver(handler))
    add(createHardwareObserver(handler))
    // Audio session interruptions come from AudioSession.interruptions
    // (see KMediaManager) — that's the single source of truth on both
    // platforms, since it's also what owns session category/mode config.
}