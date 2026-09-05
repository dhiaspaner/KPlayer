package kplayer.videoplayer

import io.github.kotlin.fibonacci.videoplayer.PlayerObserver
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kplayer.BufferingObserver
import kplayer.RateObserver
import kplayer.core.event.PlaybackEvent
import kplayer.core.player.AbstractMediaEngine
import kplayer.core.player.MediaEngine
import kplayer.core.player.toIosUrl
import kplayer.core.state.MediaSource
import kplayer.core.state.NativeError
import kplayer.core.state.toPlaybackError
import platform.AVFoundation.AVAssetResourceLoaderDelegateProtocol
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemLegibleOutput
import platform.AVFoundation.AVPlayerItemLegibleOutputPushDelegateProtocol
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.addOutput
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.removeOutput
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.resourceLoader
import platform.AVFoundation.seekToTime
import platform.AVFoundation.volume
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSAttributedString
import platform.Foundation.NSKeyValueObservingOptionNew
import platform.Foundation.NSLog
import platform.Foundation.NSNotificationCenter
import platform.Foundation.addObserver
import platform.Foundation.removeObserver
import platform.darwin.NSEC_PER_SEC
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_get_main_queue

/**
 * [MediaEngine] backed by `AVPlayer`, for video.
 *
 * The audio counterpart is `:audio`'s `AvAudioEngine`; this one adds subtitle-cue
 * routing, which on iOS is a genuine either/or rather than a free extra.
 *
 * All the sequencing lives in `EngineMediaPlayer`; this file is only the translation
 * of AVFoundation's KVO and notifications into the vocabulary [MediaEngine.events]
 * carries.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class AvVideoEngine : AbstractMediaEngine() {

    val avPlayer: AVPlayer = AVPlayer()

    /**
     * Off until a surface asks. See [AvVideoFrameOutput] for why this is not the
     * default way to get a picture on iOS.
     */
    private var observedItem: AVPlayerItem? = null
    private var playbackEndObserver: NSObjectProtocol? = null

    /** `AVPlayer.play()` always resumes at rate 1.0, so the speed must be re-applied. */
    private var playbackSpeed: Float = 1f

    /**
     * Lets a test serve media from memory under the `mockfile://` scheme, so iOS
     * tests need no network. Set before [setSource].
     */
    var testResourceLoaderDelegate: AVAssetResourceLoaderDelegateProtocol? = null

    private val rateObserver = RateObserver { rate ->
        reportPlaying(rate > 0f)
    }

    private val itemStatusObserver = PlayerObserver { status ->
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
                NSLog("AvVideoEngine: AVPlayerItem failed: ${error.message}")
                reportError(error)
            }

            else -> Unit
        }
    }

    private val bufferingObserver = BufferingObserver { likelyToKeepUp ->
        reportBuffering(!likelyToKeepUp)
    }

    // ── Subtitle routing ──────────────────────────────────────────────────────

    private var legibleOutput: AVPlayerItemLegibleOutput? = null

    private val legibleDelegate = LegibleOutputDelegate { text ->
        report(PlaybackEvent.SubtitleCueChanged(text))
    }

    /**
     * Routes subtitle cues into `VideoPlayerState.activeSubtitle` instead of letting
     * AVFoundation draw them.
     *
     * A genuine either/or on iOS, unlike Android: attaching an
     * `AVPlayerItemLegibleOutput` with `suppressesPlayerRendering` makes AVFoundation
     * hand cues to us *rather than* rendering them. So the choice has to be made
     * before or during playback, not at draw time — `:ui`'s `NativeVideoSurface` sets
     * it from `VideoSurfaceConfig.showNativeSubtitles`. Safe to flip mid-playback.
     */
    var routesSubtitlesToState: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) attachLegibleOutput(avPlayer.currentItem)
            else detachLegibleOutput(avPlayer.currentItem)
        }

    private fun attachLegibleOutput(item: AVPlayerItem?) {
        if (item == null || legibleOutput != null) return
        // The plain init delivers cues as NSAttributedString rather than raw sample
        // buffers, which is the representation we can actually read.
        val output = AVPlayerItemLegibleOutput()
        output.setDelegate(legibleDelegate, queue = dispatch_get_main_queue())
        // Already the default, but this is the flag the whole either/or hinges on.
        output.suppressesPlayerRendering = true
        item.addOutput(output)
        legibleOutput = output
    }

    private fun detachLegibleOutput(item: AVPlayerItem?) {
        val output = legibleOutput ?: return
        item?.removeOutput(output)
        legibleOutput = null
        // Whatever was last routed to Compose is about to be drawn natively (or not
        // at all); either way the state must not keep claiming it is showing.
        report(PlaybackEvent.SubtitleCueChanged(null))
    }

    // ── MediaEngine ───────────────────────────────────────────────────────────

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

        val item = if (url.scheme == "mockfile") {
            val asset = AVURLAsset.URLAssetWithURL(url, options = null)
            testResourceLoaderDelegate?.let { delegate ->
                asset.resourceLoader.setDelegate(delegate, queue = dispatch_get_main_queue())
            }
            AVPlayerItem.playerItemWithAsset(asset)
        } else {
            AVPlayerItem(uRL = url)
        }
        observedItem = item

        // Re-attach per item: outputs belong to the item, not the player, so a new
        // media load starts with none.
        if (routesSubtitlesToState) attachLegibleOutput(item)

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

    /** Handing the item over is what starts loading; [setSource] only built it. */
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
        // Setting a non-zero rate on a paused player would start it playing.
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
        // Before the KVO teardown: the output holds a reference to this item.
        detachLegibleOutput(observedItem)
        observedItem?.let { item ->
            try {
                item.removeObserver(itemStatusObserver, forKeyPath = "status")
                item.removeObserver(bufferingObserver, forKeyPath = "playbackLikelyToKeepUp")
            } catch (e: Exception) {
                NSLog("AvVideoEngine: native observer already unlinked: ${e.message}")
            }
        }
        playbackEndObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        playbackEndObserver = null
        observedItem = null
    }
}

/**
 * Receives subtitle cues from [AVPlayerItemLegibleOutput] and flattens them to plain
 * text.
 *
 * AVFoundation delivers cues as `NSAttributedString`s carrying WebVTT/CEA styling —
 * colour, position, italics. All of that is dropped here: the shared state is a
 * Kotlin type that cannot express attributed text, and the point of routing cues to
 * Compose is that the *app* decides how they look.
 *
 * An empty delivery means "nothing showing now", which is how a cue's end time
 * arrives — hence `null` rather than an empty string, so consumers can tell "no cue"
 * from "a blank cue".
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class LegibleOutputDelegate(
    private val onText: (String?) -> Unit,
) : NSObject(), AVPlayerItemLegibleOutputPushDelegateProtocol {

    override fun legibleOutput(
        output: AVPlayerItemLegibleOutput,
        didOutputAttributedStrings: List<*>,
        nativeSampleBuffers: List<*>,
        forItemTime: CValue<CMTime>,
    ) {
        val text = didOutputAttributedStrings
            .filterIsInstance<NSAttributedString>()
            .map { it.string }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        onText(text.takeIf { it.isNotEmpty() })
    }
}
