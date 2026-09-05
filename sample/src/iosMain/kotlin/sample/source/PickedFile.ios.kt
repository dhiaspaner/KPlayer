package sample.source

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import kplayer.core.state.MediaSource

/**
 * A picked video on iOS is already a plain file in the app's temporary directory:
 * `FileKitType.Video` routes to `PHPickerViewController`, and FileKit copies the
 * item out of the photo library before handing it over. So the path needs no
 * security-scoped access and `MediaSource.FilePath` — which becomes
 * `NSURL.fileURLWithPath` — is enough for `AVPlayer`.
 *
 * This is why the picker asks for [io.github.vinceglb.filekit.dialogs.FileKitType.Video]
 * rather than a generic file type: the document-picker route returns a
 * security-scoped URL instead, and playing from one means holding
 * `startAccessingSecurityScopedResource` open for the life of the item.
 */
actual fun PlatformFile.toMediaSource(): MediaSource = MediaSource.FilePath(path)
