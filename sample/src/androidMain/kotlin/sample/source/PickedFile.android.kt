package sample.source

import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile
import kplayer.core.state.MediaSource

/**
 * The photo picker and `OpenDocument` both hand back a `content://` URI, which is
 * why [MediaSource.AndroidUriString] exists — ExoPlayer resolves it through the
 * `ContentResolver`, and turning it into a path would strip the read grant that
 * came with it.
 *
 * The [AndroidFile.FileWrapper] branch only happens for a `PlatformFile` built
 * from a real path; nothing in this sample does that, but the `when` has to be
 * exhaustive and a plain path is genuinely a [MediaSource.FilePath].
 */
actual fun PlatformFile.toMediaSource(): MediaSource = when (val file = androidFile) {
    is AndroidFile.UriWrapper -> MediaSource.AndroidUriString(file.uri.toString())
    is AndroidFile.FileWrapper -> MediaSource.FilePath(file.file.absolutePath)
}
