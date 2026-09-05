package kplayer.interruption


data class InterruptionConfig(
    val backgroundPolicy: BackgroundPolicy = BackgroundPolicy.KeepState,
    val audioFocusPolicy: AudioFocusPolicy = AudioFocusPolicy.RestoreIfPlayingBefore,
    val headphonesPolicy: HeadphonesPolicy = HeadphonesPolicy.PauseAndRequireManualResume,
    val duckPolicy: DuckPolicy = DuckPolicy.LowerVolume()
) {
    companion object {
        /** Background audio-style app: keeps playing when backgrounded,
         *  recovers from focus interruptions, requires manual resume after headphones. */
        val MediaPlayerDefault = InterruptionConfig(
            backgroundPolicy = BackgroundPolicy.KeepState,
            audioFocusPolicy = AudioFocusPolicy.RestoreIfPlayingBefore,
            headphonesPolicy = HeadphonesPolicy.PauseAndRequireManualResume
        )

        /** Video/lesson-style app: pauses on backgrounding and headphone
         *  disconnect, auto-restores on return in all three cases. */
        val VideoLesson = InterruptionConfig(
            backgroundPolicy = BackgroundPolicy.PauseAndRestore,
            audioFocusPolicy = AudioFocusPolicy.RestoreIfPlayingBefore,
            headphonesPolicy = HeadphonesPolicy.PauseAndRestoreOnReconnect
        )

        /** Kiosk/ambient-style app: always resumes regardless of prior
         *  state, ignores headphone changes entirely. */
        val AutoPlay = InterruptionConfig(
            backgroundPolicy = BackgroundPolicy.PauseAndRestore,
            audioFocusPolicy = AudioFocusPolicy.AlwaysResume,
            headphonesPolicy = HeadphonesPolicy.ContinuePlayback
        )

        /** Sensitive-content-style app: pauses on every interruption and
         *  never auto-resumes — always requires explicit user action. */
        val StrictManualResume = InterruptionConfig(
            backgroundPolicy = BackgroundPolicy.PauseAndStayPaused,
            audioFocusPolicy = AudioFocusPolicy.PauseAndStayPaused,
            headphonesPolicy = HeadphonesPolicy.PauseAndRequireManualResume
        )


        val Uninterruptible = InterruptionConfig(
            backgroundPolicy = BackgroundPolicy.KeepState,
            audioFocusPolicy = AudioFocusPolicy.Ignore,
            headphonesPolicy = HeadphonesPolicy.Ignore,
            duckPolicy = DuckPolicy.Ignore
        )
    }
}
