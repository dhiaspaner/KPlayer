package kplayer

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import nskeyvalueobserving.NSKeyValueObservingProtocol
import platform.Foundation.NSKeyValueChangeNewKey
import platform.Foundation.NSNumber
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
class RateObserver(
    private val onRateChange: (Float) -> Unit
) : NSObject(), NSKeyValueObservingProtocol {
    override fun observeValueForKeyPath(
        keyPath: String?,
        ofObject: Any?,
        change: Map<Any?, *>?,
        context: COpaquePointer?
    ) {
        if (keyPath == "rate") {
            val rate = (change?.get(NSKeyValueChangeNewKey) as? NSNumber)
                ?.floatValue ?: return
            onRateChange(rate)
        }
    }
}
@OptIn(ExperimentalForeignApi::class)
class BufferingObserver(
    private val onBufferingChange: (Boolean) -> Unit
) : NSObject(), NSKeyValueObservingProtocol {

    override fun observeValueForKeyPath(
        keyPath: String?,
        ofObject: Any?,
        change: Map<Any?, *>?,
        context: COpaquePointer?
    ) {
        if (keyPath == "playbackBufferEmpty") {
            val bufferEmpty = change?.get(NSKeyValueChangeNewKey) as? Boolean ?: return
            onBufferingChange(bufferEmpty)
        }
    }
}