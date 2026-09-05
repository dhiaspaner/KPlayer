package kplayer.audioplayer

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kplayer.core.player.AbstractMediaEngine
import kplayer.core.player.MediaEngine
import kplayer.core.player.toIosUrl
import kplayer.core.state.MediaSource
import kplayer.core.state.NativeError
import kplayer.core.state.toPlaybackError
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.volume
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSKeyValueObservingOptionNew
import platform.Foundation.NSLog
import platform.Foundation.NSNotificationCenter
import platform.Foundation.addObserver
import platform.Foundation.removeObserver
import platform.darwin.NSEC_PER_SEC
import platform.darwin.NSObjectProtocol

/**
 * [MediaEngine] backed by `AVPlayer`.
 *
 * `AVPlayer` rather than `AVAudioPlayer`: the latter only plays fully-available
 * local data, so it cannot stream a URL or handle HLS.
 *
 * Nothing here configures `AVAudioSession` — it is a process-wide singleton owned
 * by `:core`'s `IosAudioSession`, whose category and mode already govern this
 * player's output.
 *
 * All the sequencing lives in [EngineMediaPlayer]; this file is only the
 * translation of AVFoundation's KVO and notifications into the vocabulary
 * [MediaEngine.events] carries.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class AvAudioEngine : AbstractMediaEngine() {

    val avPlayer: AVPlayer = AVPlayer()

    private var observedItem: AVPlayerItem? = null
    private var playbackEndObserver: NSObjectProtocol? = null

    /**
     * `AVPlayer.play()` always resumes at rate 1.0, so the requested speed has to
     * be remembered and re-applied after every start. Kept here rather than
     * upstream because it is purely an AVFoundation quirk.
     */
    private var playbackSpeed: Float = 1f

    private val rateObserver = AudioRateObserver { rate ->
        reportPlaying(rate > 0f)
    }

    private val itemStatusObserver = AudioItemStatusObserver { status ->
        when (status) {
            AVPlayerItemStatusReadyToPlay -> {
                val item = observedItem ?: avPlayer.currentItem
                val seconds = item?.let { CMTimeGetSeconds(it.duration) } ?: 0.0
                val durationMs =
                    if (seconds.isNaN() || seconds.isInfinite()) 0L
                    else (seconds * 1000.0).toLong()
                reportReady(durationMs)
            }

            AVPlayerItemStatusFailed -> {
                val item = observedItem ?: avPlayer.currentItem
                val error = NativeError.avError(item?.error).toPlaybackError()
                NSLog("AvAudioEngine: AVPlayerItem failed: ${error.message}")
                reportError(error)
            }

            else -> Unit
        }
    }

    private val bufferingObserver = AudioBufferingObserver { likelyToKeepUp ->
        reportBuffering(!likelyToKeepUp)
    }

    init {
        avPlayer.addObserver(
            rateObserver,
            forKeyPath = "rate",
            options = NSKeyValueObservingOptionNew,
            context = null,
        )
    }

    override fun setSource(source: MediaSource): Boolean {
        val url = source.toIosUrl() ?: return false

        removeItemObservers()

        val item = AVPlayerItem(uRL = url)
        observedItem = item

        item.addObserver(
            itemStatusObserver,
            forKeyPath = "status",
            options = NSKeyValueObservingOptionNew,
            context = null,
        )
        item.addObserver(
            bufferingObserver,
            forKeyPath = "playbackLikelyToKeepUp",
            options = NSKeyValueObservingOptionNew,
            context = null,
        )
        playbackEndObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = item,
            queue = null,
        ) { _ ->
            reportCompleted()
        }

        return true
    }

    /**
     * Handing the item to the player is what starts loading, so preparing is
     * exactly `replaceCurrentItemWithPlayerItem` — `setSource` only built the item.
     */
    override fun prepare() {
        avPlayer.replaceCurrentItemWithPlayerItem(observedItem)
    }

    override fun play() {
        avPlayer.play()
        if (playbackSpeed != 1f) avPlayer.rate = playbackSpeed
    }

    override fun pause() = avPlayer.pause()

    override fun seekTo(positionMs: Long) {
        avPlayer.seekToTime(
            CMTimeMakeWithSeconds(positionMs / 1000.0, preferredTimescale = NSEC_PER_SEC.toInt())
        )
    }

    override fun setSpeed(speed: Float) {
        playbackSpeed = speed
        // Setting a non-zero rate on a paused player would start it playing, so a
        // speed change only takes effect immediately if we are already moving.
        if (avPlayer.rate != 0f) avPlayer.rate = speed
    }

    override fun setVolume(volume: Float) {
        avPlayer.volume = volume
    }

    override fun currentPositionMs(): Long {
        val seconds = CMTimeGetSeconds(avPlayer.currentTime())
        return if (seconds.isNaN() || seconds.isInfinite()) 0L else (seconds * 1000.0).toLong()
    }

    override fun release() {
        removeItemObservers()
        avPlayer.removeObserver(rateObserver, forKeyPath = "rate")
        avPlayer.replaceCurrentItemWithPlayerItem(null)
    }

    private fun removeItemObservers() {
        observedItem?.let { item ->
            try {
                item.removeObserver(itemStatusObserver, forKeyPath = "status")
                item.removeObserver(bufferingObserver, forKeyPath = "playbackLikelyToKeepUp")
            } catch (e: Exception) {
                NSLog("AvAudioEngine: native observer already unlinked: ${e.message}")
            }
        }
        playbackEndObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        playbackEndObserver = null
        observedItem = null
    }

}
