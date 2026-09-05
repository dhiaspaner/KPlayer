package kplayer.core.player

import android.net.Uri
import kplayer.core.state.MediaSource
import java.io.File

/**
 * Maps a [MediaSource] to the Android type every engine ultimately needs.
 *
 * Lives in `:core` because both `:video` and `:audio` need exactly this, and it
 * depends only on the platform SDK — no media3 — so it does not breach the "no
 * engine in `:core`" rule.
 *
 * Returns `null` for a source this platform cannot represent; callers turn that
 * into a failure rather than a silent no-op.
 */
fun MediaSource.toAndroidUri(): Uri? = when (this) {
    is MediaSource.Url -> Uri.parse(value)
    is MediaSource.FilePath -> Uri.fromFile(File(path))
    is MediaSource.AndroidUriString -> Uri.parse(value)
    is MediaSource.Custom -> Uri.parse(value)
}
