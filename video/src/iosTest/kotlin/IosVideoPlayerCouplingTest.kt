import io.github.kotlin.videoplayer.generated.resources.Res
import kotlinx.cinterop.BetaInteropApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSOrderedAscending
import platform.Foundation.NSRunLoop
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.compare
import platform.Foundation.create
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.Foundation.writeToURL
import kplayer.IosVideoPlayer
import kplayer.core.state.PlaybackStatus
import kplayer.core.state.MediaSource
import kotlin.test.BeforeTest


/**
 * Real-coupling tests for IosVideoPlayer.
 *
 * Unlike a fake-PlayerEngine wiring test, these run against an *actual*
 * AVPlayer with no substitutions — they exist to verify that real KVO and
 * notification delivery from AVFoundation drives the shared state machine
 * the way the production code assumes it does: correct key paths, correct
 * CMTime -> ms conversion, and correct event ordering under real async
 * timing (which a hand-scripted fake can never expose, since the fake's
 * callback order is whatever the test author assumes it to be).
 *
 * Keep this suite small and slow-but-reliable. Branch coverage of the
 * observer *logic itself* belongs in a separate fake-engine wiring test —
 * this file is specifically for proving the real plumbing works.
 *
 * Suggested location: iosTest/kotlin/videoplayer/IosVideoPlayerCouplingTest.kt
 * Run with: ./gradlew :shared:iosSimulatorArm64Test
 */
@OptIn(ExperimentalForeignApi::class)
class IosVideoPlayerCouplingTest {

    private lateinit var sampleFile: String

    private lateinit var testLoaderDelegate: TestVideoBytesLoader

    @BeforeTest
    fun setup() {
        runBlocking {
            // 1. Read the local fixed asset bytes into memory
            val bytes = Res.readBytes("files/video_test_fixed.mp4")

            // 2. Initialize our native delegate with the video memory block
            testLoaderDelegate = TestVideoBytesLoader(bytes)

            // 3. Set your sampleFile string parameter using our secure custom URL scheme
            sampleFile = "mockfile://video_test.mp4"
        }
    }
    @Test
    fun `loading a real local file reaches Playing with correct duration`() {
        val player = IosVideoPlayer()
        try {
            // Assign the test delegate you built in @BeforeTest straight to the player property
            player.testResourceLoaderDelegate = testLoaderDelegate

            // Load your custom asset string
            player.load(MediaSource.Url(sampleFile))

            val reached = awaitUntil(timeoutSeconds = 5.0) {
                player.state.value.status == PlaybackStatus.Playing ||
                        player.state.value.status == PlaybackStatus.Error
            }

            println("State status: ${player.state.value.status}")
            println("State error: ${player.state.value.errorMessage}")

            assertTrue(reached, "Player never left Buffering: ${player.state.value}")
            assertEquals(PlaybackStatus.Playing, player.state.value.status)
            assertTrue(
                player.state.value.durationMs in EXPECTED_DURATION_MS_RANGE,
                "Unexpected durationMs: ${player.state.value.durationMs}"
            )
        } finally {
            releaseAndAwait(player)
        }
    }

    @Test
    fun `loading a nonexistent file reaches Error`() {
        val player = IosVideoPlayer()
        try {
            player.load(MediaSource.Url("/nonexistent/file.mp4"))

            val reached = awaitUntil(timeoutSeconds = 5.0) {
                player.state.value.status == PlaybackStatus.Error
            }
            assertTrue(reached, "Expected Error status, got: ${player.state.value.status}")
        } finally {
            releaseAndAwait(player)
        }
    }

    @Test
    fun `stop does not leak a transient Paused state from the rate KVO race`() {
        val player = IosVideoPlayer()
        player.testResourceLoaderDelegate = testLoaderDelegate
        val statusHistory = mutableListOf<PlaybackStatus>()
        val collectorJob: Job = CoroutineScope(Dispatchers.Main).launch {
            player.state.collect { statusHistory.add(it.status) }
        }

        try {
            player.load(MediaSource.Url(sampleFile))
            assertTrue(
                awaitUntil(5.0) { player.state.value.status == PlaybackStatus.Playing },
                "Never reached Playing: $statusHistory"
            )

            player.stop()
            assertTrue(
                awaitUntil(2.0) { player.state.value.status == PlaybackStatus.Stopped },
                "Never reached Stopped: $statusHistory"
            )

            // The real bug this guards against: player.pause() inside stop()
            // can fire the "rate" KVO synchronously, BEFORE onEvent(StopRequested)
            // runs — at that instant state.value.status is still Playing, so the
            // "ignore spurious pause after stop" guard does nothing, and a
            // transient Paused leaks into the stream before Stopped settles.
            assertEquals(
                false,
                statusHistory.contains(PlaybackStatus.Paused),
                "stop() leaked a transient Paused state before settling to Stopped: $statusHistory"
            )
            assertEquals(0L, player.state.value.positionMs)
        } finally {
            collectorJob.cancel()
            releaseAndAwait(player)
        }
    }

    @Test
    fun `playback reaching the end fires PlaybackCompleted`() {
        val player = IosVideoPlayer()
        player.testResourceLoaderDelegate = testLoaderDelegate
        try {
            player.load(MediaSource.FilePath(sampleFile))
            assertTrue(
                awaitUntil(5.0) { player.state.value.status == PlaybackStatus.Playing },
                "Never reached Playing before end-of-playback check"
            )

            val reached = awaitUntil(timeoutSeconds = SAMPLE_VIDEO_DURATION_SECONDS + 5.0) {
                player.state.value.status == PlaybackStatus.Completed
            }
            assertTrue(reached, "Never reached Completed: ${player.state.value.status}")
        } finally {
            releaseAndAwait(player)
        }
    }

    @Test
    fun `pause after play reaches Paused via real rate KVO`() {
        val player = IosVideoPlayer()
        player.testResourceLoaderDelegate = testLoaderDelegate
        try {
            player.load(MediaSource.FilePath(sampleFile))
            assertTrue(awaitUntil(5.0) { player.state.value.status == PlaybackStatus.Playing })

            player.pause()
            assertTrue(
                awaitUntil(2.0) { player.state.value.status == PlaybackStatus.Paused },
                "Never reached Paused after pause(): ${player.state.value.status}"
            )
        } finally {
            releaseAndAwait(player)
        }
    }

    // ── Test harness helpers ───────────────────────────────────────────────

    private fun awaitUntil(timeoutSeconds: Double, condition: () -> Boolean): Boolean {
        val deadline = NSDate.dateWithTimeIntervalSinceNow(timeoutSeconds)
        while (NSDate().compare(deadline) == NSOrderedAscending) {
            if (condition()) return true
            NSRunLoop.currentRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.05))
        }
        return condition()
    }

    /**
     * Drives release() through the real Dispatchers.Main queue and waits
     * for it to actually complete before the test returns. Skipping this
     * risks an AVFoundation crash ("an instance was deallocated while key
     * value observers were still registered") if `player` — and the
     * AVPlayer it owns — is torn down before its KVO observers are removed.
     */
    private fun releaseAndAwait(player: IosVideoPlayer) {
        player.release()
        awaitUntil(2.0) { player.state.value.status == PlaybackStatus.Released }
    }



    companion object {
        // Update this to match the real duration of the fixture you embed below.
        private const val SAMPLE_VIDEO_DURATION_SECONDS = 2.0
        private val EXPECTED_DURATION_MS_RANGE = 1_800L..2_200L
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun ByteArray.toNSData(): NSData = memScoped {
    NSData.create(bytes = allocArrayOf(this@toNSData), length = this@toNSData.size.toULong())
}

fun writeToTempFile(bytes: ByteArray, name: String): String {
    val fileManager = NSFileManager.defaultManager

    // Grab the actual Caches directory mapped inside the Simulator's environment
    val cachesUrls = fileManager.URLsForDirectory(NSCachesDirectory, NSUserDomainMask)
    val cachesDirUrl = cachesUrls.first() as NSURL

    // Construct the fully qualified file URL
    val fileUrl = cachesDirUrl.URLByAppendingPathComponent(name) ?:
    throw IllegalStateException("Could not append path component: $name")

    // Convert the Kotlin ByteArray to NSData
    val data = bytes.toNSData()

    // Write the binary data atomically to the validated sandbox location
    val success = data.writeToURL(fileUrl, atomically = true)
    if (!success) {
        throw IllegalStateException("Failed to write video bytes to sandboxed URL: ${fileUrl.path}")
    }

    // Return the absolute path string expected by VideoSource.FilePath
    return fileUrl.path ?: throw IllegalStateException("URL path string representation is null")
}