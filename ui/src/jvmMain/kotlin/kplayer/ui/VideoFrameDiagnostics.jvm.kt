package kplayer.ui

import kplayer.core.MediaPlayer
import kplayer.videoplayer.frame.VideoFrameSource

/**
 * macOS and Linux produce frames; Windows draws into its own `HWND` and has none,
 * which `DesktopVideoPlayer.frameSource` already reports as null.
 */
internal actual fun MediaPlayer<*, *>.videoFrameSourceOrNull(): VideoFrameSource? =
    desktopPlayerOrNull()?.frameSource
