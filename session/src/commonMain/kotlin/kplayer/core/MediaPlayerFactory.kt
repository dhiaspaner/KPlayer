package kplayer.core

import kplayer.engine.dsl.KMediaManagerBuilder
import kplayer.core.state.MediaSource
import kplayer.core.state.PlayerState

/**
 * Assembles a fully-wired player: the backend supplied via
 * [KMediaManagerBuilder.player] wrapped in a [kplayer.engine.KMediaManager] that owns
 * the audio session, the interruption handler and the system observers.
 *
 * It reads as a constructor for [MediaPlayer] and keeps that interface's package on
 * purpose, so `import kplayer.core.MediaPlayer` still brings both — but it lives in
 * `:session`, not beside the interface in `:core`. The contract is the bottom of the
 * stack: every backend implements it and nothing it names may point upward, whereas
 * this function reaches the very top of the session layer. Declared next to the
 * interface, it was the single import in the library running against the layering,
 * and the only thing standing between `kplayer.core.state` / `event` / `player` and a
 * module of their own.
 *
 * Public because the platform backends live in `:video` and `:audio`, so they call
 * this across a module boundary.
 */
fun <S : PlayerState<S>> MediaPlayer(
    config: KMediaManagerBuilder<S>.() -> Unit = {}
): MediaPlayer<MediaSource, S> =
    KMediaManagerBuilder<S>()
        .apply(config)
        .build()
