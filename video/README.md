# `:video` — video playback backends

The engines: **ExoPlayer** on Android, **AVPlayer** on iOS, and on desktop the platform's own stack
reached through JNA — AVFoundation on macOS, `IMFMediaEngine` on Windows, GStreamer on Linux. This
is the only module that depends on media3, and the only one that talks to a native player.

Depends on `:core` via `api`, so `MediaPlayer`, `MediaSource`, `PlaybackState` and
`InterruptionConfig` come along with it.

```kotlin
implementation(project(":video"))   // or :ui, which api-exposes this
```

---

## Building a player

```kotlin
val player: MediaPlayer<MediaSource, VideoPlayerState> = VideoPlayer(
    interruptionConfig = MutableStateFlow(InterruptionConfig.MediaPlayerDefault),
    audioSessionMode = AudioSessionMode.Movie,
)
```

`VideoPlayer()` is an `expect` factory. Each platform's `actual` builds its backend and wraps it
through `:core`'s `MediaPlayer { }` builder, so what you get back is a `KMediaManager` decorator
that already owns the audio session, the interruption handler and the system observers. There is
nothing else to wire up.

On Android it reads the application context from `kplayer.appContext`, so call
`initializeContext(...)` first.

Release it when you're done — `release()` tears down the native player, the observers and the
manager's coroutine scope.

## `VideoPlayerState`

What `player.state` emits:

```kotlin
data class VideoPlayerState(
    val status: PlaybackStatus = Idle,   // Idle/Buffering/Ready/Playing/Paused/Stopped/Completed/Error/Released
    val playWhenReady: Boolean = true,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedPositionMs: Long = 0,
    val playbackSpeed: Float = 1f,
    val volume: Float = 1f,
    val errorMessage: String? = null,
    val source: MediaSource? = null,
    val activeSubtitle: String? = null,
)
```

Read-side helpers (`isPlaying`, `isBuffering`, `hasError`, `isSeekable`, `progress`,
`bufferedProgress`) live in `:core`'s `kplayer.state.Utils`.

## Architecture

```
VideoPlayer (expect factory)
    └── AbstractVideoPlayer          — owns VideoPlayerStateMachine + SharedFlow<PlaybackFeedback>
            ├── AndroidVideoPlayer   — ExoPlayer; fires PlaybackEvents from Player.Listener
            ├── IosVideoPlayer       — AVPlayer; fires PlaybackEvents from KVO observers
            └── DesktopVideoPlayer   — AVFoundation / IMFMediaEngine / GStreamer; state polled
```

Platform classes implement exactly one method — `execute(action: PlaybackAction)`. All state
management lives in `AbstractVideoPlayer`.

### Data flow

```
your call    →  MediaPlayer.play()
             →  AbstractVideoPlayer.execute(PlaybackAction.Play)     [dispatched to platform]
             →  native player.play()
             →  native callback fires  →  onEvent(PlaybackEvent.PlaybackStarted)
             →  VideoPlayerStateMachine  →  StateFlow<VideoPlayerState>
```

Three vocabularies, deliberately separate:

- **`PlaybackAction`** — commands from callers.
- **`PlaybackEvent`** — notifications from the native player. You never construct these.
- **`VideoPlayerState`** — the single source of truth exposed to UI.

State never changes because you *asked* for something; it changes because the native player
*reported* something. That is why the seek bar and the actual frames cannot disagree.

## The state machine

`VideoPlayerStateMachine` (internal) drives every status transition through `:state-machine`'s
graph DSL. Each `PlaybackStatus` is a node; each `PlaybackEvent` subtype is dispatched by its
`KClass`, so dispatch is an O(1) hash lookup rather than a `when` chain — and an illegal transition
is simply an edge that does not exist.

Four events bypass the graph, because they carry no status meaning or apply from anywhere:

| Event | Why |
|---|---|
| `PositionSynced` | fires several times a second; updates `positionMs` directly |
| `SubtitleCueChanged` | same — updates `activeSubtitle` directly |
| `SpeedChanged` / `VolumeChanged` | plain field updates |
| `Failure` / `ReleaseRequested` | global transitions, valid from any state (`machine.transitionTo(...)`) |

Field updates (`source`, `positionMs`, `durationMs`, `errorMessage`) happen in each node's
`onEnter`, which receives the triggering event.

The machine takes no scope and no dispatcher: `onEvent` runs the transition inline on the calling
thread, under the graph's re-entrant lock, and returns with the state already committed — valid
because every edge action is a non-blocking `StateFlow.update`. So a callback may read `state.value`
on the next line, and the machine can be driven from a plain JVM test with no main dispatcher
installed. In practice every event arrives on the main thread anyway: ExoPlayer's `Player.Listener`
and the iOS KVO/periodic observers all fire there.

Each backend still takes its own `scope`, which dispatches native commands and must be main-thread
bound. That is the only scope left, and the reason it exists is the opposite of the machine's: the
native players insist on one particular thread.

**Auto-play after buffering is the platform's job:** when `PlaybackEvent.Ready` fires and
`playWhenReady == true`, the backend calls `play()`, which eventually produces
`PlaybackStarted` → `Playing`.

## `VideoSource` variants

`MediaSource` (from `:core`) maps to native types per platform:

| Variant | Android (`toAndroidUri`) | iOS (`toIosUrl`) |
|---|---|---|
| `Url(value)` | `Uri.parse(value)` | `NSURL.URLWithString` |
| `FilePath(path)` | `Uri.fromFile(File(path))` | file URL |
| `AndroidUriString(value)` | `Uri.parse(value)` | — |
| `Custom(kind, value)` | `Uri.parse(value)` | scheme-dependent |

A source that cannot be mapped produces `PlaybackEvent.Failure` rather than throwing.

## Platform specifics

### Android (`AndroidVideoPlayer`)

- Wraps `ExoPlayer`, built from the application context.
- Audio focus is **not** delegated to ExoPlayer (`handleAudioFocus` is left off): `:audio`'s
  `AudioSession` owns focus arbitration.
- Events come from `Player.Listener`; position ticks from a periodic job.
- `exoPlayer` is exposed **for rendering only** — `:ui` attaches a `SurfaceView`/`TextureView` to
  it. Do not issue transport commands or release it through that handle.

### iOS (`IosVideoPlayer`)

- Wraps `AVPlayer` + `AVPlayerItem`.
- KVO on `rate`, `status` and `playbackLikelyToKeepUp`; a periodic time observer for position;
  `AVPlayerItemDidPlayToEndTime` for completion.
- Subtitle cues arrive through an `AVPlayerItemLegibleOutput`, which *replaces* AVFoundation's own
  caption rendering while attached.
- `avPlayer` is exposed for rendering only, same contract as Android.
- **`frameSource` is the second rendering path.** `AvVideoFrameOutput` pulls BGRA frames from an
  `AVPlayerItemVideoOutput` so `:ui` can draw them as ordinary Compose content
  (`VideoRenderMode.TEXTURE`). It exists because UIKit interop puts the video in its own layer
  *above* the Compose scene: under the default `AVPlayerViewController` path the picture cannot be
  blurred, clipped to a rounded corner, cross-faded or drawn under other Compose content.

  **The player renders the audio; Compose renders the picture.** In that mode there is no layer and
  no view controller attached at all — the `AVPlayer` plays with nothing to draw into, and the
  `AVPlayerItemVideoOutput` is what keeps *video* decoding, since it is the only consumer asking for
  frames. Pulling them is therefore required to be invisible to playback: if attaching an output
  stalled, re-buffered or re-timed the player, the mode would trade a blur for an audio glitch.
  `AvFoundationFrameTest` pins both directions — playback and frames advancing together, and
  disabling the output mid-playback leaving the player running.

  What it gives up is what belongs to `AVPlayerViewController` rather than to `AVPlayer`: AirPlay,
  Picture-in-Picture and the system's Now Playing integration. Subtitles too, which is why the
  TEXTURE surface routes cues to `activeSubtitle` regardless of `showNativeSubtitles`.

  **Off unless a surface asks**

  Unlike the desktop engine, which polls because JNA cannot define the Objective-C class KVO needs,
  Kotlin/Native *can* subclass `NSObject` — `CADisplayLink` and `AVPlayerItemOutputPullDelegate` are
  both reachable. Polling is still the better fit: `hasNewPixelBufferForItemTime:` is the check
  either design makes, a coroutine loop cancels with the rest of the engine, and it keeps the copy
  off the main thread, which a `CADisplayLink` target would not.

  **Two ways out of a pixel buffer.** The output is asked for packed BGRA, and when it obliges a
  frame is a single `memcpy`. When it does not, the buffer is planar and has no single base address
  to copy from — so CoreImage converts it instead. That costs a conversion per frame and stays the
  fallback, but it means a frame is drawn either way rather than the surface going black on a format
  nobody predicted. Building the attributes dictionary from Kotlin/Native is the fiddly part: the
  key is a `CPointer<__CFString>` and the value a `UInt`, neither of which bridges to
  `NSString`/`NSNumber` on its own, so the key is converted to a Kotlin `String` and the FourCC
  passed as `Int`. Getting that wrong is silent — it was, and the symptom was a black TEXTURE
  surface with no error anywhere.

  **A failure says so, observably.** Every frame producer reports through one
  `FrameOutputFailures` (`kplayer.videoplayer.frame`), which is what
  `VideoFrameSource.frameOutputFailure` exposes as a `StateFlow<String?>` — on all three engines,
  plus `IosVideoPlayer.frameOutputFailure` for the caller who looks there first. It logs the first
  reason once (`NSLog` on iOS, `println` elsewhere), keeps it, and clears it when the item changes.
  Every line is tagged `kplayer/frames`, which `:ui` logs the render half under too — so
  `grep kplayer/frames` gets the decoder and the renderer in one stream.

  First-wins on purpose: a pump failing every tick would emit sixty lines a second and bury the one
  that mattered, while a pump failing silently is indistinguishable from a video with no picture.
  That distinction cost real time twice in this module's history — which is also why it is a flow
  rather than a field. A reason only a debugger can reach is a reason nobody reads; `:ui`'s
  `rememberVideoFrameDiagnostics` puts it on screen, and the sample's **Frame output** card is what
  that looks like.

  Two more consequences worth knowing. Outputs belong to an `AVPlayerItem`

### JVM / desktop (`DesktopVideoPlayer`)

Real playback on macOS and Windows, through the OS's own media stack. `DesktopVideoEngines`
dispatches on `os.name`, and both engines are **pure JNA** — no native shim is built, so there is
nothing to compile per-arch and nothing to package.

| OS | Engine | Native stack | State |
|---|---|---|---|
| macOS | `AvFoundationVideoEngine` | AVFoundation via the ObjC runtime | **verified** on arm64, frames drawn |
| Windows | `MediaFoundationVideoEngine` | `IMFMediaEngine` (frame-server mode) via COM | **written, never executed**; frames drawn |
| Linux | `GStreamerVideoEngine` | GStreamer `playbin` + `appsink` | **written, never executed**; frames drawn by Compose |

**Frames reach the screen on macOS.** `:ui`'s `NativeVideoSurface.jvm.kt` reads
`VideoFrameSource.latestFrame()`, wraps the bytes with `org.jetbrains.skia.Image.makeRaster` at
`ColorType.BGRA_8888`, and draws the result as an ordinary Compose `Image` — so the video is a real
participant in the layout and can be blurred, clipped and animated like any other content. There is
no native view to host on desktop, so this is not a fallback; it is the only path, and it is the
same one iOS opts into with `VideoRenderMode.TEXTURE`.

The surface is written once, in `ui/src/skikoMain/`, and compiled into both the desktop and iOS
compilations — a shared *directory* rather than an intermediate source set, because adding a
`skikoMain` with its own `dependsOn` edges opts `:ui` out of the default hierarchy template and
`iosMain` then stops being recognised as the actual-provider for `commonMain`'s expects.

**Linux and Windows render through the same path**, with no extra `:ui` code: `GStreamerVideoEngine`'s
`appsink` and `MediaFoundationVideoEngine`'s `IMFMediaEngine` both publish into a `FrameBuffer`, so
`frameSource` is non-null on every desktop OS and `NativeVideoSurface.jvm.kt` draws all three
identically. That is the payoff of dispatching on *capability* rather than on `os.name` — three OSes,
one renderer, and `VideoRenderMode` has nothing to select between on desktop at all.

**Windows used to be the exception**, through an engine wrapping MFPlay (`IMFPMediaPlayer`) instead:
MFPlay can be driven with a **null callback** and polled through `GetState`, but it draws straight
into an `HWND` and exposes no frame API, so `:ui` had to embed a heavyweight AWT `Canvas` through
`SwingPanel`, read its `HWND` with `Native.getComponentPointer`, and hand that to
`MFPCreateMediaPlayer` — `NativeWindowVideoSink`, since removed. The video sat *above* the Compose
scene rather than inside its drawing pass, so it could not be blurred, clipped to a rounded corner or
drawn under other Compose content, the same trade iOS makes for `AVPlayerViewController` — except on
Windows it was not a choice, it was the only rendering MFPlay offered.

`IMFMediaEngine`'s **frame-server mode** removes that trade. Two things made the switch cheaper than
it looks:

- **A synthesised COM callback turned out to be unavoidable either way.**
  `IMFMediaEngineClassFactory::CreateInstance` rejects a missing `MF_MEDIA_ENGINE_CALLBACK` even in
  frame-server mode, so this engine builds `IMFMediaEngineNotify`'s vtable (`win/MediaEngineNotify.kt`)
  anyway — MFPlay's null-callback shortcut disappears in the new API regardless of whether frames were
  the goal. The sink is left inert: every fact this engine needs is still polled, exactly as it was
  against MFPlay, because reacting to `EventNotify` would mean keeping state in two places.
- **The destination surface is a WIC bitmap, not Direct3D.** `TransferVideoFrame`'s destination can be
  "a DXGI surface or WIC bitmap", and `MF_MEDIA_ENGINE_DXGI_MANAGER` is documented as optional in
  frame-server mode — so `win/MediaFoundation.kt` never touches Direct3D, a device or a swap chain. A
  WIC bitmap is plain CPU memory the moment it is created, so a lock handed straight to `FrameBuffer`
  is the entire frame path.

#### Frame decoding and buffering

Engines that decode into pixels implement `VideoFrameSource` — deliberately *not* part of
`MediaEngine`, because Android and iOS hand their engines a native view and never see a pixel, so a
frame accessor there would be a method nobody implements.

Frames are **BGRA**, because that is what both decoders produce natively
(`kCVPixelFormatType_32BGRA`, GStreamer `BGRx`) *and* what Skia wants, so a frame reaches the screen
without a channel swap anywhere. `rowBytes` is not always `width * 4` — hardware decoders align
rows — so anything reading the pixels must step by the stride.

`FrameBuffer` sits between decoder and renderer and **drops rather than queues**. A queue would let
a stalled UI thread back up the decoder until it stuttered or ran out of memory, and at 4K a single
frame is 33 MB. It rotates three recycled arrays: allocating per frame would be 2 GB/s of garbage at
60fps, but a slot handed to the renderer must not be overwritten while it is still being read, and
three slots give the renderer a full frame of slack. A renderer lagging further gets a torn frame
rather than a stall — the right trade for video. `FrameBufferTest` pins the rotation down and runs
on every OS, since it is the one piece of the frame path with no native dependency.

Both Apple engines pull from an `AVPlayerItemVideoOutput`, and although the calls cannot be shared —
iOS reaches AVFoundation through Kotlin/Native cinterop, desktop through `objc_msgSend` over JNA —
**the decision of when to copy is**, in `FramePump`. Each engine implements `PixelSource` (attach,
`hasNewFrame`, `publishCurrentFrame`) and the shared driver decides the rest, so the two cannot
drift. `FramePumpTest` covers the policy against a fake on every target.

The policy is not obvious, and getting it wrong is invisible until the moment it matters. Steady
state is gated on `hasNewPixelBufferForItemTime:`, because at 4K a needless copy is 33 MB. But that
gate alone leaves the surface **black exactly when it appears**: the question it answers is "is there
something newer than what you last took?", and for a paused player — or one loaded but never played
— time is not advancing, so the answer is no and nothing is ever published. Switching to the drawn
render mode mid-playback would show nothing until the user pressed play. So the pump forces a copy
whenever it has no frame to show, and after a `requestRefresh()`, which `seekTo` issues on both
platforms because a seek while paused moves the picture without advancing time. Both conditions
self-clear once a frame lands.

macOS runs it on a **separate 16 ms pump**

The `AVPlayerLayer`-in-an-`NSView` route would be zero-copy and hardware-composited, but needs a
real view in the window hierarchy and therefore AppKit interop. Pulling pixel buffers costs one copy
per frame and works with nothing on screen at all, which is what makes it testable.

#### Neither engine gets native callbacks, so both poll

This is the single design decision the desktop backends turn on. JNA can *call* into Objective-C
and COM but cannot *define* a class in either, and both stacks report state through an object you
must implement — `observeValueForKeyPath:` for KVO, `IMFMediaEngineNotify` for `IMFMediaEngine`. So
every event these engines report is synthesised from a property read on a 100 ms timer, and on
Windows that holds even though `IMFMediaEngineNotify` had to be built anyway — see § Desktop for why
building it does not mean using it.

The trade was against a JNI/Objective-C shim compiled into the repo. Polling costs latency and one
daemon thread per player; a shim costs every consumer a toolchain, a per-arch dylib and a packaging
story, and its absence is an `UnsatisfiedLinkError` at the first `play()` rather than a message.
Polling also fits the shape that already exists — `EngineMediaPlayer` polls `currentPositionMs()`
regardless — so it adds a cadence, not a concept.

One consequence worth knowing: **completion is inferred, not announced.** Both stacks stop at the
end of an item by moving to a paused/stopped state indistinguishable from a real pause, so each
engine checks "am I at the end?" *before* reporting the transition. Reporting it naively makes the
player visibly step through `Paused` on its way to `Completed` — the same wart `ExoVideoEngine` and
both HTML engines swallow. `AvFoundationVideoEngineTest` pins this down.

#### `CMTime` through JNA

The received wisdom is that JNA cannot return AVFoundation's 24-byte `CMTime`. It can: JNA calls
through libffi, which implements AArch64's indirect struct return, so a `Structure.ByValue` works
in both directions — measured as a return (`-[AVPlayerItem duration]`) and as an argument
(`-[AVPlayer seekToTime:]`). The real subtlety is that **x86_64 needs `objc_msgSend_stret`** for
structs over 16 bytes while arm64 uses plain `objc_msgSend`; sending through the wrong one returns
garbage rather than failing. `ObjC.msgSendStructFn` makes that choice once, at load.

#### `Pointer.NULL` is literally `null`

Worth knowing before touching any of this from Kotlin: JNA's `Pointer.NULL` is a `null` reference,
not a wrapper around address zero. A `vararg args: Any` therefore makes every `NULL` argument throw
`NullPointerException` at the call site — and because the frame pump deliberately does not report
per-frame failures, that surfaced as "no frames, no error" rather than a stack trace. Every JNA
argument list here is `Any?`, and the pump keeps its first failure in `framePumpError` so the next
occurrence is diagnosable instead of silent.

#### Windows and Linux are unverified

`MediaFoundationVideoEngine` and its `kplayer.videoplayer.win` bindings were written against the
real `mfmediaengine.h` / `wincodec.h` headers (via the mingw-w64 mirror) on a macOS machine and have
never been run. COM dispatch is positional, so a wrong vtable slot calls the wrong function rather
than failing cleanly — every index lives in one table per interface in `MediaFoundation.kt`, with
the header's declaration order checked against a real source rather than recalled, which is more
scrutiny than most JNA-COM code in this repo gets but is still not the same thing as having run it.
`DesktopVideoEngines.isAvailable` probes that `ole32.dll`, `mfplat.dll` and `oleaut32.dll` load, but
"loads" is not "works".

`IMFMediaEngine` in **frame-server mode**, not the MFPlay this engine used before: MFPlay can be
created with a null callback and renders into an `HWND` for free, but exposes no way to pull decoded
frames, which was Windows's only remaining gap next to macOS and Linux — see § Desktop.

`GStreamerVideoEngine` is unverified for a duller reason: the GStreamer natives are not installed on
the machine it was written on. Its bus-message translation is a close copy of `:audio`'s
`GStreamerAudioEngine`, which *is* exercised, so the state path is low-risk; the `appsink` frame
path is entirely new. Installing GStreamer on any OS is enough to exercise it, since `playbin`
auto-plugs per platform — the engine is Linux's only because macOS and Windows have better options,
not because it is Linux-specific.

#### When no engine is available

`VideoPlayer()` returns a player that emits one `PlaybackFeedback.Rejected` carrying an actionable
reason, exactly as `:audio`'s desktop player does. Nothing throws from inside `play()`, and there is
no `UnsatisfiedLinkError` path.

## Audio attributes live in three places (Android)

All three derive from the same `AudioSessionMode`, and a new mode must be handled in all of them:

- `kplayer.core.audio.platformAudioAttributesFor` (`:core`, `android.media`) — for focus.
  Applied by `AndroidAudioSession` on every focus request.
- `kplayer.videoplayer.exoPlayerAudioAttributesFor` (`:video`, media3) — for the output stream.
  **Currently unused:** `AndroidVideoPlayer` builds `ExoPlayer` with default attributes and never
  calls this, so the output stream's usage/content type does not yet follow the session mode.
- `kplayer.audioplayer.exoAudioAttributesFor` (`:audio`, media3) — the same thing for the audio
  backend, which *does* apply it via `setAudioAttributes(attrs, handleAudioFocus = false)`.

## Tests

```bash
./gradlew :video:jvmTest                          # state machine + desktop engine
./gradlew :video:compileKotlinIosSimulatorArm64   # iOS compile check
```

44 tests, all passing:

- `PlaybackStateMachineTest` (15) covers transitions without any engine. No `Dispatchers.setMain()`
  needed, because the machine's scope is `Unconfined`.
- `FrameBufferTest` (9) covers the rotation and drop policy. In `commonTest` since the buffer moved
  to `commonMain` for iOS to share — no native anything, so it is meant to run on every target.
  **It only actually runs on the JVM today**: `:video:iosSimulatorArm64Test` does not compile,
  because `IosVideoPlayerCouplingTest` imports a Compose Resources `Res` accessor that `:video` has
  no plugin to generate. That breakage predates this work and is unrelated to it.
All native access in the desktop AVFoundation engine is serialised behind one lock, and that is not
defensive tidiness. Teardown runs on whichever thread called `release`/`setFrameOutputEnabled` while
the poller may be mid-tick, and `ScheduledFuture.cancel(false)` neither interrupts a running task nor
waits for it — so the pump could hold a local reference to an output the other thread had just
released. Messaging a freed Objective-C object through JNA does not throw; it hits whatever now
occupies that memory. It presented as a test suite that failed roughly one run in ten with **no
failing test**, because the worker JVM aborted:

```
*** Terminating app due to uncaught exception 'NSInvalidArgumentException',
    reason: '-[AVTelemetryInterval hasNewPixelBufferForItemTime:]: unrecognized selector ...'
Process 'Gradle Test Executor' finished with non-zero exit value 134
```

The whole tick is held, copy included: a 4K copy is a few milliseconds, and briefly blocking a
`setFrameOutputEnabled` is a far better trade than reading a buffer as it is freed. **iOS needs no
equivalent** — Kotlin/Native gives Objective-C references ARC semantics, so a local reference in the
pump keeps the output alive; the hazard is specific to JNA's raw pointers.

- `AvFoundationVideoEngineTest` (9) runs the engine against a **real `AVPlayer`** — deliberately not
  a fake, because what is unproven here is exactly what a fake cannot exercise: that JNA can drive
  the ObjC runtime, that a `CMTime` survives the ABI in both directions, and that polling
  reconstructs the callbacks AVFoundation never sends.
- `AvFoundationFrameTest` (6) decodes real 4K frames and asserts on the **bytes**. Shape alone
  proves nothing: a wrong pixel format, an unlocked base address or a bad stride all still produce a
  correctly-sized array, so the load-bearing assertion is that the pixels are not uniformly one
  value.

`:ui:jvmTest` carries the last hop — `DesktopVideoFrameBitmapTest` (5) takes a real decoded frame
through `toImageBitmap()` and asserts on the resulting pixels: opaque alpha, more than one colour in
the sampled region, and rows that resemble their neighbours rather than shearing (which is what a
`rowBytes`-vs-`width` mix-up looks like, and is invisible in a dimensions check). It needs
`compose.desktop.currentOs` on the test classpath purely for skiko's **native** binaries —
`org.jetbrains.skia.Image` fails its static initialiser without them, and the only symptom is a null
bitmap.
- `DesktopVideoPlayerTest` (5) drives the whole stack — `DesktopVideoPlayer` → `EngineMediaPlayer` →
  `PlaybackStateMachine` → `AVPlayer` — to `Completed`.

Media comes from the OS rather than the repo. The state suites use a system sound
(`/System/Library/Sounds/Submarine.aiff`) — identical `AVPlayer` path, present on every macOS
install, short enough to play to completion inside a test — and the frame suite uses the smallest
Sonoma wallpaper `.mov`, the one video file on a stock install.

**All four desktop suites print `skipped:` and pass on non-macOS**, so the suite stays green on CI
machines that are not Macs. That also means a green run on Linux or Windows proves nothing about the
engines for those platforms — nothing exercises them yet.

On iOS,
`IosVideoPlayer.testResourceLoaderDelegate` lets a test inject an in-memory
`AVAssetResourceLoaderDelegate` under the `mockfile://` scheme, so coupling tests need no network.
