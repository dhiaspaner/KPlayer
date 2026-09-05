package io.github.kotlin.fibonacci.videoplayer

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import nskeyvalueobserving.NSKeyValueObservingProtocol
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatus
import platform.darwin.NSObject

/**
 * KVO observer for [AVPlayerItem] that implements [NSKeyValueObservingProtocol] via cinterop
 * so [observeValueForKeyPath] can be implemented (Kotlin/Native cannot override it on [NSObject] alone).
 *
 * @see <a href="https://proandroiddev.com/leveraging-key-value-observing-kvo-in-kotlin-multiplatform-kmp-for-ios-231519e5c1ff">KVO in KMP iOS</a>
 */
@OptIn(ExperimentalForeignApi::class)
class PlayerObserver(
    private val onStatusChange: (AVPlayerItemStatus) -> Unit
) : NSObject(), NSKeyValueObservingProtocol {

    override fun observeValueForKeyPath(
        keyPath: String?,
        ofObject: Any?,
        change: Map<Any?, *>?,
        context: COpaquePointer?
    ) {
        if (keyPath == "status") {
            val item = ofObject as? AVPlayerItem
            item?.status?.let { onStatusChange(it) }
        }
    }
}
