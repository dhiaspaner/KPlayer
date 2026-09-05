package kplayer.core.state

import kplayer.core.state.PlaybackError

/**
 * The platform's own account of a failure, before anyone has classified it.
 *
 * Every backend fails in its own vocabulary — a media3 `errorCode`, an `NSError`
 * domain and code, a `MediaError` code, a GStreamer bus message, a JDK exception —
 * and none of those types may cross into `commonMain` (ADR 0001). So this is the one
 * thing common code says about platform failures: that a platform *has* such a
 * vocabulary and can hand it over. What that vocabulary is made of is the `actual`'s
 * business — each target declares exactly the fields it can fill in, and no other
 * target ever sees them.
 *
 * The single member declared here is the constructor every platform shares: a
 * [Throwable], which is all a synchronous failure ever gives you. The
 * backend-specific ways of building one are declared by the `actual` that has
 * something to do with them, so a new backend on one platform changes nothing in
 * common code:
 *
 * | target  | how an engine builds one                                         |
 * |---------|------------------------------------------------------------------|
 * | android | `NativeError.media3(errorCode, message, cause, httpStatusCode, mimeType)` |
 * | ios     | `NativeError.avError(item.error)`                                |
 * | jvm     | `NativeError.gstreamer(code, message)` / `NativeError.avError(domain, code, description)` |
 * | wasmJs  | `NativeError.mediaElement(code, message)` / `NativeError.rejected(text)` |
 *
 * @see toPlaybackError
 */
expect class NativeError(cause: Throwable?)

/**
 * Describe [this] in [kplayer.core.state.PlaybackError]'s terms.
 *
 * The one seam between "what the platform said" and "what we call it", and the only
 * `expect` in the error path. Classification is not the player's job and not the
 * engine seam's either: `EngineMediaPlayer` reaches this from its one error
 * boundary, engines reach it from their own callbacks and `runCatching` blocks, and
 * neither has to be handed a mapper or implement a hook to get it. Nothing here is
 * coupled to a player instance, so it is equally usable from a poll thread, a DOM
 * callback, or a test.
 *
 * Every actual must fall back to [kplayer.core.state.PlaybackError.Unknown] rather than throw — this
 * runs while something has *already* failed, and a mapper that throws would replace
 * a described failure with a crash.
 */
expect fun NativeError.toPlaybackError(): PlaybackError

/**
 * The same classification for a plain throw, which is what a *call* into a native
 * player produces.
 *
 * Not an `expect` of its own: it is [NativeError]'s common constructor and the one
 * mapper above, spelled as the extension the call sites already read best.
 */
fun Throwable.toPlaybackError(): PlaybackError = NativeError(this).toPlaybackError()
