package kplayer.interruption

/**
 * Identifies *what* is interrupting playback.
 *
 * Adding a new interruption source (Bluetooth, CarPlay, Cast, …) is a matter of
 * adding one `data object` here and one branch in [responseFor] — the decision
 * engine in [DefaultPlaybackInterruptionHandler] never changes.
 *
 * Sealed on purpose: a new cause won't compile until it has been mapped to a
 * policy, so no source can be added and silently ignored.
 */
sealed interface InterruptionCause {

    /** Phone call, another app taking focus, Siri, or any system audio interruption. */
    data object AudioFocusLoss : InterruptionCause

    /** The app moved to the background. */
    data object AppBackgrounded : InterruptionCause

    /** Wired/Bluetooth headphones were unplugged (route became unavailable). */
    data object HeadphonesDisconnected : InterruptionCause
}
