package sample.source

import io.github.vinceglb.filekit.PlatformFile
import kplayer.core.state.MediaSource
import org.w3c.dom.url.URL

/**
 * A page cannot read the local filesystem, so there is no path to hand over — the
 * browser only lets the `File` be referenced through an object URL. That URL is a
 * perfectly ordinary [MediaSource.Url] as far as `HtmlVideoEngine` is concerned:
 * it assigns it to `video.src` like any other.
 *
 * The URL stays valid until the document is unloaded. This sample never revokes
 * it — one leaked blob handle per pick, and the tab owns them all — but a real app
 * would call `URL.revokeObjectURL` once the player is done with the source.
 */
actual fun PlatformFile.toMediaSource(): MediaSource =
    MediaSource.Url(URL.createObjectURL(file))
