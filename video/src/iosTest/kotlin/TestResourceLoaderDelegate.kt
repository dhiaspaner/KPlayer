import platform.AVFoundation.AVAssetResourceLoader
import platform.AVFoundation.AVAssetResourceLoaderDelegateProtocol
import platform.AVFoundation.AVAssetResourceLoadingRequest
import platform.Foundation.NSData
import platform.darwin.NSObject

class TestResourceLoaderDelegate(private val videoBytes: ByteArray) : NSObject(), AVAssetResourceLoaderDelegateProtocol {

    override fun resourceLoader(
        resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource: AVAssetResourceLoadingRequest
    ): Boolean {
        val contentRequest = shouldWaitForLoadingOfRequestedResource.contentInformationRequest
        val dataRequest = shouldWaitForLoadingOfRequestedResource.dataRequest

        contentRequest?.setContentType("public.mpeg-4")
        contentRequest?.setContentLength(videoBytes.size.toLong())
        contentRequest?.setByteRangeAccessSupported(true)

        if (dataRequest != null) {
            val size = videoBytes.size.toLong()
            val start = dataRequest.requestedOffset.coerceIn(0L, size).toInt()
            val end = minOf(dataRequest.requestedOffset + dataRequest.requestedLength, size).toInt()
            if (start < end) {
                dataRequest.respondWithData(videoBytes.copyOfRange(start, end).toNSData())
            }
        }

        shouldWaitForLoadingOfRequestedResource.finishLoading()
        return true
    }
}

class TestVideoBytesLoader(private val videoBytes: ByteArray) : NSObject(), AVAssetResourceLoaderDelegateProtocol {

    override fun resourceLoader(
        resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource: AVAssetResourceLoadingRequest
    ): Boolean {
        val contentRequest = shouldWaitForLoadingOfRequestedResource.contentInformationRequest
        val dataRequest = shouldWaitForLoadingOfRequestedResource.dataRequest

        // AVFoundation's first call is content-info only (dataRequest == null).
        // Returning false here makes AVFoundation give up immediately — always return true.
        contentRequest?.setContentType("public.mpeg-4") // UTI, not MIME type
        contentRequest?.setContentLength(videoBytes.size.toLong())
        contentRequest?.setByteRangeAccessSupported(true)

        // Subsequent calls carry a data request; serve only the requested byte range.
        // The moov atom of this file is at the end, so AVPlayer will issue a late
        // range request to seek there — we must handle every offset correctly.
        if (dataRequest != null) {
            val size = videoBytes.size.toLong()
            val start = dataRequest.requestedOffset.coerceIn(0L, size).toInt()
            // requestedOffset + requestedLength can exceed Long.MAX_VALUE for open-ended
            // requests; clamp via minOf before converting to Int.
            val end = minOf(dataRequest.requestedOffset + dataRequest.requestedLength, size).toInt()
            if (start < end) {
                dataRequest.respondWithData(videoBytes.copyOfRange(start, end).toNSData())
            }
        }

        shouldWaitForLoadingOfRequestedResource.finishLoading()
        return true
    }
}