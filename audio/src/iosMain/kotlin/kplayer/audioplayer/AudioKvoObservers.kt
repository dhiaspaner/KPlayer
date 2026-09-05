package kplayer.audioplayer

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import nskeyvalueobserving.NSKeyValueObservingProtocol
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatus
import platform.Foundation.NSKeyValueChangeNewKey
import platform.Foundation.NSNumber
import platform.darwin.NSObject

/**
 * KVO plumbing for [kplayer.IosAudioPlayer].
 *
 * `observeValueForKeyPath` cannot be overridden on [NSObject] from Kotlin/Native,
 * so these classes conform to `NSKeyValueObserving` through the cinterop binding
 * declared in `src/nativeInterop/cinterop/nskeyvalueobserving.def`.
 *
 * Deliberately separate from `:video`'s equivalents rather than shared: `:audio`
 * and `:video` are siblings, so neither can see the other's internals, and a
 * shared copy would have to live in `:core` — which is kept free of AVFoundation.
 *
 * @see <a href="https://proandroiddev.com/leveraging-key-value-observing-kvo-in-kotlin-multiplatform-kmp-for-ios-231519e5c1ff">KVO in KMP iOS</a>
 */
@OptIn(ExperimentalForeignApi::class)
internal class AudioItemStatusObserver(
    private val onStatusChange: (AVPlayerItemStatus) -> Unit,
) : NSObject(), NSKeyValueObservingProtocol {

    override fun observeValueForKeyPath(
        keyPath: String?,
        ofObject: Any?,
        change: Map<Any?, *>?,
        context: COpaquePointer?,
    ) {
        if (keyPath != "status") return
        // Read the item rather than the change dictionary: the raw value is an
        // NSNumber, and the item's typed `status` property is what we want.
        (ofObject as? AVPlayerItem)?.status?.let(onStatusChange)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class AudioRateObserver(
    private val onRateChange: (Float) -> Unit,
) : NSObject(), NSKeyValueObservingProtocol {

    override fun observeValueForKeyPath(
        keyPath: String?,
        ofObject: Any?,
        change: Map<Any?, *>?,
        context: COpaquePointer?,
    ) {
        if (keyPath != "rate") return
        val rate = (change?.get(NSKeyValueChangeNewKey) as? NSNumber)?.floatValue ?: return
        onRateChange(rate)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class AudioBufferingObserver(
    private val onLikelyToKeepUpChange: (Boolean) -> Unit,
) : NSObject(), NSKeyValueObservingProtocol {

    override fun observeValueForKeyPath(
        keyPath: String?,
        ofObject: Any?,
        change: Map<Any?, *>?,
        context: COpaquePointer?,
    ) {
        if (keyPath != "playbackLikelyToKeepUp") return
        // A BOOL arrives boxed as NSNumber, not as a Kotlin Boolean — casting
        // straight to Boolean silently yields null and the callback never fires.
        val likelyToKeepUp =
            (change?.get(NSKeyValueChangeNewKey) as? NSNumber)?.boolValue ?: return
        onLikelyToKeepUpChange(likelyToKeepUp)
    }
}
