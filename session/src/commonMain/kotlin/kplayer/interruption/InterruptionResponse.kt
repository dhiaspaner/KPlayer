package kplayer.interruption

/**
 * How aggressively an interruption is allowed to auto-resume playback.
 *
 * Declared from least to most restrictive so that `maxOf(...)` over a chain of
 * stacked interruptions yields the strictest behavior — that is how a
 * "require manual resume" interruption wins over a permissive one.
 */
enum class ResumePolicy {
    /** Resume once nothing else is interrupting (was-playing-before still required). */
    Always,

    /** Same as [Always], but only if the OS also says it is safe to resume. */
    WhenSystemAllows,

    /** Never auto-resume — the user must press play. */
    Never,
}

/**
 * The playback-neutral decision a policy produces for a single interruption:
 * whether it pauses, and how it may later resume. This is the shared vocabulary
 * every per-source policy (audio focus, background, headphones, and future
 * ones) maps into, so the handler needs no per-source branching.
 */
internal data class InterruptionResponse(
    val pausesPlayback: Boolean,
    val resume: ResumePolicy,
)

internal fun InterruptionConfig.responseFor(cause: InterruptionCause): InterruptionResponse =
    when (cause) {
        InterruptionCause.AudioFocusLoss -> audioFocusPolicy.toResponse()
        InterruptionCause.AppBackgrounded -> backgroundPolicy.toResponse()
        InterruptionCause.HeadphonesDisconnected -> headphonesPolicy.toResponse()
    }

private fun AudioFocusPolicy.toResponse(): InterruptionResponse = when (this) {
    AudioFocusPolicy.Ignore -> InterruptionResponse(pausesPlayback = false, resume = ResumePolicy.Never)
    AudioFocusPolicy.RestoreIfPlayingBefore -> InterruptionResponse(pausesPlayback = true, resume = ResumePolicy.WhenSystemAllows)
    AudioFocusPolicy.AlwaysResume -> InterruptionResponse(pausesPlayback = true, resume = ResumePolicy.Always)
    AudioFocusPolicy.PauseAndStayPaused -> InterruptionResponse(pausesPlayback = true, resume = ResumePolicy.Never)
}

private fun BackgroundPolicy.toResponse(): InterruptionResponse = when (this) {
    BackgroundPolicy.KeepState -> InterruptionResponse(pausesPlayback = false, resume = ResumePolicy.Never)
    BackgroundPolicy.PauseAndRestore -> InterruptionResponse(pausesPlayback = true, resume = ResumePolicy.WhenSystemAllows)
    BackgroundPolicy.PauseAndStayPaused -> InterruptionResponse(pausesPlayback = true, resume = ResumePolicy.Never)
}

private fun HeadphonesPolicy.toResponse(): InterruptionResponse = when (this) {
    HeadphonesPolicy.Ignore -> InterruptionResponse(pausesPlayback = false, resume = ResumePolicy.Never)
    HeadphonesPolicy.ContinuePlayback -> InterruptionResponse(pausesPlayback = false, resume = ResumePolicy.Never)
    HeadphonesPolicy.PauseAndRequireManualResume -> InterruptionResponse(pausesPlayback = true, resume = ResumePolicy.Never)
    HeadphonesPolicy.PauseAndRestoreOnReconnect -> InterruptionResponse(pausesPlayback = true, resume = ResumePolicy.WhenSystemAllows)
}
