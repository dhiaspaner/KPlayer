package kplayer.interruption

sealed interface BackgroundPolicy {

    /**
     * Do not modify playback when app goes background/foreground.
     *
     * Example:
     * - Music continues playing in background.
     * - Paused player stays paused.
     */
    data object KeepState : BackgroundPolicy


    /**
     * Pause playback when app goes background.
     *
     * When app returns foreground:
     * restore previous playback state.
     *
     * Example:
     * - Video app
     * - Learning app
     */
    data object PauseAndRestore : BackgroundPolicy


    /**
     * Pause playback when app goes background.
     *
     * When app returns foreground:
     * keep paused.
     *
     * Example:
     * - User should manually press play again.
     */
    data object PauseAndStayPaused : BackgroundPolicy
}