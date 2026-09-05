package kplayer.ui

import kplayer.core.MediaPlayer
import kplayer.videoplayer.frame.VideoFrameSource

/**
 * Never any frames on Android: ExoPlayer renders into a `SurfaceView` /
 * `TextureView` and the compositor keeps the pixels, so there is nothing in
 * memory to inspect and nothing that could fail the way a pulled frame can.
 */
internal actual fun MediaPlayer<*, *>.videoFrameSourceOrNull(): VideoFrameSource? = null
