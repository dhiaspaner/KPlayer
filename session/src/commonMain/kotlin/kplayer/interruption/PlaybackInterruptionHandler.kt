package kplayer.interruption

interface PlaybackInterruptionHandler {

    fun onEvent(event: InterruptionEvent)
}
