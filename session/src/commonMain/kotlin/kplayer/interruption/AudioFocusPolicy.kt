package kplayer.interruption

sealed interface AudioFocusPolicy {


    /**
     * Ignore audio focus changes.
     *
     * Rare use case.
     */
    data object Ignore : AudioFocusPolicy


    /**
     * Pause on temporary focus loss.
     *
     * Resume only if player was playing before interruption.
     *
     * Example:
     * - Spotify
     * - Podcasts
     */
    data object RestoreIfPlayingBefore : AudioFocusPolicy


    /**
     * Always resume after focus returns.
     *
     * Example:
     * - Kiosk applications
     */
    data object AlwaysResume : AudioFocusPolicy


    /**
     * Pause and require user action.
     *
     * Example:
     * - Sensitive content
     * - Video players
     */
    data object PauseAndStayPaused : AudioFocusPolicy
}