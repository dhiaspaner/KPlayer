package kplayer.ui

import kplayer.core.MediaPlayer
import kplayer.videoplayer.frame.VideoFrameSource

/**
 * Never any frames on the web: the `<video>` element decodes and draws itself, so
 * no pixels pass through Kotlin at all.
 */
internal actual fun MediaPlayer<*, *>.videoFrameSourceOrNull(): VideoFrameSource? = null
