package kplayer.interruption

/**
 * What to do when another app takes *transient* focus we may duck under (e.g. a
 * navigation prompt) — a volume concern, never a pause. Distinct from the
 * pause/resume policies.
 */
sealed interface DuckPolicy {

    /** Keep full volume through the duck (let the OS/session handle it, if at all). */
    data object Ignore : DuckPolicy

    /**
     * Lower our volume to [level] (0..1) while ducked, restoring the previous
     * volume when it ends.
     */
    data class LowerVolume(val level: Float = 0.2f) : DuckPolicy
}
