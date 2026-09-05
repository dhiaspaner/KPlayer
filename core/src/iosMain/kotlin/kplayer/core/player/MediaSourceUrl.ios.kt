package kplayer.core.player

import kplayer.core.state.MediaSource
import platform.Foundation.NSURL

/**
 * Maps a [MediaSource] to the `NSURL` every engine ultimately needs.
 *
 * Lives in `:core` because both `:video` and `:audio` need exactly this, and it
 * depends only on Foundation — no AVFoundation — so it does not breach the "no
 * engine in `:core`" rule.
 *
 * Returns `null` for a source this platform cannot represent; callers turn that
 * into a failure rather than a silent no-op.
 */
fun MediaSource.toIosUrl(): NSURL? = when (this) {
    is MediaSource.Url -> NSURL.URLWithString(value)

    // A FilePath carrying a scheme is really a URL — `fileURLWithPath` would
    // percent-escape the "://" and produce a path that resolves to nothing. This is
    // what lets `IosVideoPlayer`'s tests feed in `mockfile://` sources.
    is MediaSource.FilePath ->
        if (path.contains("://")) NSURL.URLWithString(path) else NSURL.fileURLWithPath(path)

    is MediaSource.AndroidUriString -> NSURL.URLWithString(value)
    is MediaSource.Custom -> NSURL.URLWithString(value)
}
