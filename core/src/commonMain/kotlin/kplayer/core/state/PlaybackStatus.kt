package kplayer.core.state

import com.dhiachemingui.statemachine.State

enum class PlaybackStatus : State {
    Idle,
    Buffering,
    Ready,
    Playing,
    Paused,
    Stopped,
    Completed,
    Error,
    Released
}
