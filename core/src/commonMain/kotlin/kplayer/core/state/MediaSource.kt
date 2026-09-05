package kplayer.core.state

/**
 * Cross-platform media source descriptor.
 *
 * Use [Url] for network locations, [FilePath] for local files, [AndroidUriString]
 * for Android content URIs, and [Custom] for future platform-specific sources.
 */
sealed interface MediaSource {
    data class Url(val value: String) : MediaSource
    data class FilePath(val path: String) : MediaSource
    data class AndroidUriString(val value: String) : MediaSource
    data class Custom(val kind: String, val value: String) : MediaSource
}