package kplayer.interruption

sealed interface HeadphonesPolicy {

    /**
     * Ignore headphone changes.
     */
    data object Ignore : HeadphonesPolicy


    /**
     * Pause when headphones disconnect.
     *
     * Do not automatically resume.
     *
     * Example:
     * - Music players
     */
    data object PauseAndRequireManualResume : HeadphonesPolicy


    /**
     * Pause when headphones disconnect.
     *
     * Resume when headphones reconnect
     * if it was playing before.
     *
     * Example:
     * - Podcasts
     */
    data object PauseAndRestoreOnReconnect : HeadphonesPolicy


    /**
     * Continue playback after disconnect.
     *
     * Rare.
     */
    data object ContinuePlayback : HeadphonesPolicy
}