package sample.source

import io.github.vinceglb.filekit.PlatformFile
import kplayer.core.state.MediaSource

/**
 * Turns a file the user picked into the [MediaSource] the player understands.
 *
 * This is the whole seam between FileKit and kplayer, and it is deliberately in
 * `:sample` rather than the library: what a picked file *is* differs per platform
 * (a content URI, a sandboxed file URL, an absolute path, a browser `File`), and
 * `MediaSource` already has a variant for each. The library takes the descriptor
 * and never learns that a picker exists.
 *
 * Every actual is total — a picked file always maps to something — so callers get
 * a source, not a nullable.
 */
expect fun PlatformFile.toMediaSource(): MediaSource
