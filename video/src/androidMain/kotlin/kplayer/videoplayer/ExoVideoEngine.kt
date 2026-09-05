package kplayer.videoplayer

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import kplayer.core.event.PlaybackEvent
import kplayer.core.player.AbstractMediaEngine
import kplayer.core.player.MediaEngine
import kplayer.core.player.toAndroidUri
import kplayer.core.state.MediaSource
import kplayer.core.state.NativeError
import kplayer.core.state.PlaybackError
import kplayer.core.state.toPlaybackError

/**
 * [MediaEngine] backed by ExoPlayer, for video.
 *
 * The audio counterpart is `:audio`'s `ExoAudioEngine`; the only difference is that
 * this one reports subtitle cues. Both stay separate because the modules cannot see
 * each other — see ADR 0001.
 *
 * All the sequencing lives in `EngineMediaPlayer`; this file is only the translation
 * of media3's vocabulary into the one [MediaEngine.events] carries.
 */
internal class ExoVideoEngine(context: Context) : AbstractMediaEngine() {

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()

    private val playerListener = object : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            when {
                isPlaying -> reportPlaying(true)
                exoPlayer.playbackState == Player.STATE_ENDED -> Unit
                else -> reportPlaying(false)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> reportBuffering(true)

                Player.STATE_READY -> {
                    reportBuffering(false)
                    reportReady(exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: 0L)
                }

                Player.STATE_ENDED -> {
                    reportBuffering(false)
                    reportCompleted()
                }

                Player.STATE_IDLE -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            reportError(error.toPlaybackError())
        }

        /**
         * Cues are always extracted, whether or not anything renders them.
         *
         * ExoPlayer decodes the selected text track regardless — this callback is
         * the same data `SubtitleView` draws from — so publishing it costs a string
         * join and lets the UI switch between native and Compose subtitle rendering
         * without touching track selection. Contrast iOS, where routing cues to code
         * *replaces* native rendering and so has to be chosen up front.
         */
        override fun onCues(cueGroup: CueGroup) {
            val text = cueGroup.cues
                .mapNotNull { it.text?.toString() }
                .filter { it.isNotBlank() }
                .joinToString("\n")
            report(PlaybackEvent.SubtitleCueChanged(text.takeIf { it.isNotEmpty() }))
        }
    }

    init {
        exoPlayer.addListener(playerListener)
    }

    override fun setSource(source: MediaSource): Boolean {
        val uri = source.toAndroidUri() ?: return false
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        return true
    }

    override fun prepare() = exoPlayer.prepare()

    override fun play() = exoPlayer.play()

    override fun pause() = exoPlayer.pause()

    override fun seekTo(positionMs: Long) = exoPlayer.seekTo(positionMs)

    override fun setSpeed(speed: Float) {
        exoPlayer.playbackParameters = PlaybackParameters(speed)
    }

    override fun setVolume(volume: Float) {
        exoPlayer.volume = volume
    }

    override fun currentPositionMs(): Long = exoPlayer.currentPosition

    override fun release() {
        exoPlayer.removeListener(playerListener)
        exoPlayer.clearMediaItems()
        exoPlayer.release()
    }
}

/**
 * media3's failure vocabulary in [PlaybackError]'s terms.
 *
 * Only the *extraction* is here; the classification is `:core`'s, behind the one
 * [NativeError] seam every backend goes through. Digging the HTTP status and the
 * codec out of media3's exception hierarchy needs media3 types, which may not cross
 * into `:core` (ADR 0001), so this small helper is the one part that stays here —
 * and it is the part with no decisions in it.
 */
private fun PlaybackException.toPlaybackError(): PlaybackError = NativeError.media3(
    errorCode = errorCode,
    message = message,
    cause = cause,
    httpStatusCode = httpStatusCode(),
    mimeType = (this as? ExoPlaybackException)?.rendererFormat?.sampleMimeType,
).toPlaybackError()

/**
 * The response code behind a failed HTTP read, when there was one.
 *
 * media3 buries it in an `InvalidResponseCodeException` somewhere down the cause
 * chain rather than exposing it on `PlaybackException`, and it is the difference
 * between a 503 worth retrying and a 403 that never heals — so it is worth the walk.
 */
private fun PlaybackException.httpStatusCode(): Int? {
    var current: Throwable? = cause
    while (current != null) {
        if (current is HttpDataSource.InvalidResponseCodeException) return current.responseCode
        current = current.cause
    }
    return null
}
