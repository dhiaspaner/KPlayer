package kplayer.ui.model

/**
 * Formats a duration as `m:ss`, or `h:mm:ss` once past an hour.
 *
 * Negative or unknown (`0`) durations render as `0:00` rather than something
 * alarming, since the engine reports `0` before the media is prepared.
 *
 * In `kplayer.ui.model` rather than `kplayer.ui` so every consumer — Compose,
 * SwiftUI, a notification, a log line — renders the same clock from the same
 * rule without pulling in the toolkit.
 */
fun formatPlaybackTime(millis: Long): String {
    if (millis <= 0L) return "0:00"
    val totalSeconds = millis / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$ss" else "$minutes:$ss"
}
