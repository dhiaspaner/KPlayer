# Task prompt — native desktop video engines for kplayer

> Paste this into a fresh Claude Code session at the repo root. It is written to be
> self-contained: it states the architecture you must fit, the constraints that were
> already discovered the hard way, and what "done" means.

---

## Goal

Give kplayer a **real video player on desktop (the `jvm()` target)**, backed by the
platform's own media stack, one engine per OS:

| OS | Native stack | Java binding route |
|---|---|---|
| Windows | Media Foundation (`IMFMediaEngine`) | JNA COM, or JNI shim |
| macOS | AVFoundation (`AVPlayer` + `AVPlayerItemVideoOutput`) | see the KVO constraint below |
| Linux | GStreamer (`playbin` + `appsink`) | `gst1-java-core` (already a dependency) |

Plus the Compose desktop **render surface** to display the frames, because a video
engine that decodes into nowhere is not a player.

Today `video/src/jvmMain/kotlin/kplayer/videoplayer/VideoPlayer.jvm.kt` is a stub that
rejects every command via `PlaybackFeedback.Rejected`. Replace it.

---

## Read these first

- `CLAUDE.md` — module architecture, the engine-seam rules, conventions.
- `docs/adr/0001-sharing-player-logic-between-audio-and-video.md` — why the seam exists.
- `core/README.md` § `kplayer.player` — the shared backend.
- `video/README.md`, `audio/README.md` — the two existing engine sets.
- `audio/src/jvmMain/kotlin/kplayer/audioplayer/` — **the closest precedent.** Desktop
  *audio* already works this way: `GStreamerAudioEngine` + a `DesktopAudioEngines`
  dispatcher that selects on `os.name`. Mirror its shape.

## The architecture you must fit — do not redesign it

All playback sequencing already exists in `:core` and is unit-tested. **You are only
writing native translation.** Do not put buffering bookkeeping, auto-play, position
polling, volume clamping or state transitions in your engine — they are already in
`EngineMediaPlayer` and duplicating them will be rejected.

The entire contract (`core/src/commonMain/kotlin/kplayer/player/MediaEngine.kt`):

```kotlin
interface MediaEngine {
    fun attach(listener: Listener)
    fun setSource(source: MediaSource): Boolean   // false = cannot represent it; change nothing
    fun prepare()                                  // begin loading; ends in Listener.onReady
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun setVolume(volume: Float)                   // already clamped to 0f..1f by the caller
    fun currentPositionMs(): Long                  // polled while playing; must be cheap
    fun release()

    interface Listener {
        fun onPlayingChanged(isPlaying: Boolean)
        fun onBufferingChanged(isBuffering: Boolean)   // safe to repeat; caller de-dupes
        fun onReady(durationMs: Long)                  // 0 = unknown (live)
        fun onCompleted()
        fun onError(message: String)
        fun onCustomEvent(event: PlaybackEvent) = Unit // video uses it for SubtitleCueChanged
    }
}
```

Two rules the existing engines follow and yours must too:

1. **Translate quirks in the engine, not upstream.** `ExoVideoEngine` swallows the
   spurious `isPlaying=false` ExoPlayer emits at end-of-media, because upstream that
   reads as a pause and the player visibly steps through `Paused` before `Completed`.
   Both HTML engines do the same for the `pause` that precedes `ended`. Expect your
   native stack to have an equivalent wart.
2. **Never report state you were told to enter.** `play()` must not call
   `onPlayingChanged(true)` itself — wait for the native callback (or your poll to
   observe it), or `PlaybackState` starts describing intentions instead of facts.

Wiring the engine into a player is then three lines — copy `AndroidVideoPlayer`:

```kotlin
class DesktopVideoPlayer(/* scopes */) : EngineMediaPlayer<VideoPlayerState>(
    engine = DesktopVideoEngines.create(),
    initialState = VideoPlayerState(),
    scope = scope,                  // command dispatch
    stateMachineScope = stateMachineScope,
    reduceCustom = VideoCueReducer, // both already exist in :video
    onLoad = VideoOnLoad,
)
```

---

## Constraints already discovered — do not re-litigate these

These cost real time to establish. Treat them as findings, not opinions.

1. **AVFoundation's callbacks need an Objective-C class, and pure Java cannot make
   one.** `AVPlayer` reports rate/status/end-of-media via KVO and
   `NSNotificationCenter`, both of which require an ObjC object implementing
   `observeValueForKeyPath:…`. JNA and Panama can *call* ObjC but cannot *define* a
   class. Rococoa can, but it ships a native dylib, was last released in 2011, and has
   **no arm64 support** — it is not an option on Apple Silicon. Your two real choices
   on macOS are a **JNI/ObjC shim compiled into the repo**, or **polling** `rate`,
   `status` and `currentTime` on a timer and synthesising the callbacks. Polling fits
   the existing shape — `EngineMediaPlayer` already polls `currentPositionMs()`.
2. **`CMTime` is a 24-byte struct**, returned indirectly on arm64. JNA cannot handle
   that cleanly. Panama can (`MemoryLayout`), and so can a JNI shim. `AVAudioPlayer`
   sidesteps it entirely with scalar `NSTimeInterval`s, but is local-file-only, so it
   is useless for video.
3. **Panama pins consumers to JDK 22+.** `java.lang.foreign` is final in 22. This repo
   sets `jvmTarget = JVM_11`, but only inside the `androidLibrary { }` blocks — the
   `jvm()` target follows the toolchain. If you choose Panama, say so in the ADR and in
   `README.md` § Requirements, because most Compose Desktop apps run JDK 17 or 21.
4. **Media Foundation needs a COM callback object too.** `IMFMediaEngine` requires an
   `IMFMediaEngineNotify` implementation, i.e. a synthesised vtable. JNA's
   `com.sun.jna.platform.win32.COM` can do it. Also required: `CoInitializeEx` per
   thread and `MFStartup`. `IMFMediaEngine::TransferVideoFrame` is the frame-grab path.
5. **GStreamer is bindings-only.** `libs.gstreamer` (`gst1-java-core` 1.4.0) is
   **already declared** in both `:audio` and `:video` `jvmMain`. The natives must be
   installed on the machine (`brew install gstreamer`, `apt install
   libgstreamer1.0-0 gstreamer1.0-plugins-{base,good}`, or the Windows MSI).
   `DesktopAudioEngines.isAvailable` shows the pattern: probe with `Gst.init()`, and if
   it fails return a player that rejects every command with an install instruction
   rather than throwing from inside `play()`.
6. **GStreamer has no playback-rate property.** Speed is a seek with a rate — see
   `GStreamerAudioEngine.setSpeed`.

---

## Rendering the frames into Compose

This is the part with no precedent in the repo; audio needed no surface. `:ui` already
has the seam: `ui/src/commonMain/kotlin/kplayer/ui/NativeVideoSurface.kt` is an
`expect fun`, and **`ui/src/jvmMain/kotlin/kplayer/ui/NativeVideoSurface.jvm.kt`
already exists as a placeholder actual** — fill it in. Read the Android and iOS actuals
first; both unwrap `KMediaManager` to reach the backend, and you must do the same.

Two strategies. **Do A first**, and only attempt B if you have a measured reason:

**A — frame pump (portable, one copy per frame).** The sink hands you raw RGBA;
wrap it as `org.jetbrains.skia.Image` → `ImageBitmap` → draw in a Compose `Canvas`.
- GStreamer: `appsink` with `video/x-raw,format=BGRA`, pull with a `NEW_SAMPLE` callback.
- macOS: `AVPlayerItemVideoOutput.copyPixelBuffer(forItemTime:)` → `CVPixelBuffer`.
- Windows: `IMFMediaEngine::TransferVideoFrame` into a D3D/DXGI surface or a byte buffer.

Keep the frame path off the Compose thread and drop frames rather than queueing them;
a stalled UI thread must never back up the decoder.

**B — native surface interop (zero-copy, fiddly).** Hand the platform a window handle
(`HWND` / `NSView` / X11 `Window`) via `SwingPanel`, and let it render directly. Fastest,
but Compose interop layering and resize/occlusion behaviour is where this gets hard.

`VideoSurfaceConfig` (scaling mode, aspect ratio, `keepScreenOn`, `showNativeSubtitles`)
already exists and the desktop surface should honour what it can. Subtitles: report cues
through `Listener.onCustomEvent(PlaybackEvent.SubtitleCueChanged(text))` — the state
machine's `VideoCueReducer` already routes them to `VideoPlayerState.activeSubtitle`.

---

## File layout

Mirror `:audio`'s desktop layout exactly:

```
video/src/jvmMain/kotlin/kplayer/videoplayer/
    DesktopVideoEngines.kt      # os.name dispatcher + isAvailable + unavailableReason
    GStreamerVideoEngine.kt     # playbin + appsink
    MediaFoundationVideoEngine.kt
    AvFoundationVideoEngine.kt
video/src/jvmMain/kotlin/kplayer/
    DesktopVideoPlayer.kt       # EngineMediaPlayer subclass + native handle accessor
video/src/jvmMain/kotlin/kplayer/videoplayer/VideoPlayer.jvm.kt   # replace the stub
ui/src/jvmMain/kotlin/kplayer/ui/NativeVideoSurface.jvm.kt        # fill in the actual
```

If you add a JNI/ObjC shim, keep sources in-repo under `video/src/nativeShim/` with a
documented build step, and make its absence a graceful `isAvailable = false` — never a
link error at first play.

**Conventions:** comments explain *why*, never *what*; document the native wart you are
working around at the point you work around it, the way the existing engines do. Match
the surrounding KDoc density.

---

## Verification

Everything below must pass. Nothing here is currently red.

```bash
./gradlew :core:jvmTest :audio:jvmTest :video:jvmTest    # 53 + 38 + 15, all green
./gradlew :video:compileKotlinJvm :audio:compileKotlinJvm
./gradlew :video:assembleAndroidMain :video:compileKotlinIosSimulatorArm64
./gradlew :video:compileKotlinWasmJs :audio:compileKotlinWasmJs
./gradlew :ui:assembleAndroidMain :ui:compileKotlinIosSimulatorArm64
./gradlew :sample:assembleDebug :sample:compileKotlinIosSimulatorArm64
```

Note `timeout` is not available on macOS; run long Gradle invocations in the
background rather than wrapping them.

**Test the engine-independent parts against a fake**, as `:audio` does: `FakeMediaEngine`
+ `EngineMediaPlayerTest` in `audio/src/commonTest/` cover the whole seam contract with
no device. If you change anything in `:core`'s `kplayer.player`, those 17 tests are your
safety net. Add a `FakeMediaEngine`-based suite in `:video` too if you touch shared code.

Three traps that will cost you an hour each if you do not know them:
- Cancel test scopes in a `finally` — the position-sync loop shares the test scheduler,
  so a failed assertion that skips cancellation makes `runTest` hang instead of fail.
- Buffering de-duplication is invisible through `state`: `StateFlow` conflates equal
  values. Assert that repeats leave the state object unchanged.
- Kotlin/Native rejects `,` in backticked test names — a name that compiles for
  `jvmTest` can still fail `compileTestKotlinIosSimulatorArm64`.

## Acceptance criteria

1. `VideoPlayer()` on the JVM target returns a real player; the rejecting stub is gone.
2. Load → buffer → play → pause → seek → speed → volume → complete → release all drive
   the native stack, and `VideoPlayerState` reflects what the engine *did*.
3. Frames are visible in a Compose desktop window through `NativeVideoSurface`.
4. Missing native libraries produce one actionable `PlaybackFeedback.Rejected`, never a
   crash or an `UnsatisfiedLinkError`.
5. All existing tests and every compile target above stay green.
6. Docs updated: `video/README.md` (a desktop section mirroring the platform-specifics
   sections), `CLAUDE.md` (module table, targets), `README.md` (§ Requirements if the
   JDK floor moves, § Known gaps), and a new ADR if you pick Panama or ship a shim.

## Non-goals

- Do not touch the Android, iOS or web engines; they are done and tested.
- Do not add playlist/queue support, `bufferedPositionMs`, or `MediaSession`-style OS
  integration. All are known gaps tracked elsewhere.
- Do not "improve" `PlaybackStateMachine`, `PlayerState<Self>` or `EngineMediaPlayer`
  while you are here. If the seam genuinely cannot express something, say so and propose
  a change to `MediaEngine` — that is what `onCustomEvent` was for.

## Decisions you must make explicitly, and record

- macOS: JNI shim vs polling. State the trade you took and why.
- Panama vs JNA on Windows/macOS, and therefore whether the desktop JDK floor becomes 22.
- Whether all three OSes ship at once, or GStreamer stays the fallback for OSes whose
  native engine is not written yet — `DesktopAudioEngines` already does the latter, with
  `TODO(desktop)` markers at the branch points.
- Rendering strategy A or B, and what you measured if you chose B.

If you can only verify one OS on your machine, say so plainly in the summary and mark
the unverified engines as such in the code and the README. Do not present unverified
native bindings as working.
