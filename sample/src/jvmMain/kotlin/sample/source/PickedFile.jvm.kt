package sample.source

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import kplayer.core.state.MediaSource

/**
 * Desktop dialogs return an ordinary filesystem path, so there is nothing to
 * translate.
 *
 * Picking here still ends in `PlaybackFeedback.Rejected`: `:video`'s JVM actual is
 * a stub. The picker is wired up anyway so the desktop window exercises the same
 * code path as the others — and so it starts working the day the desktop engine
 * lands.
 */
actual fun PlatformFile.toMediaSource(): MediaSource = MediaSource.FilePath(path)
