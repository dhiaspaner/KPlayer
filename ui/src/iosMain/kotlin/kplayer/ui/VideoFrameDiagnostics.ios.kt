package kplayer.ui

import kplayer.IosVideoPlayer
import kplayer.core.MediaPlayer
import kplayer.engine.KMediaManager
import kplayer.videoplayer.frame.VideoFrameSource

/**
 * Always present on iOS — the engine can produce frames whether or not anything
 * asked it to, so this says nothing about whether [VideoRenderMode.TEXTURE] is
 * the mode in use. `VideoFrameDiagnostics.outputEnabled` is what answers that.
 */
internal actual fun MediaPlayer<*, *>.videoFrameSourceOrNull(): VideoFrameSource? = when (this) {
    is IosVideoPlayer -> null
    is KMediaManager<*, *> -> null
    else -> null
}
