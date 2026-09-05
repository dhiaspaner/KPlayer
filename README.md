# kplayer

A Kotlin Multiplatform media player for **Android and iOS**, with a Compose Multiplatform UI layer.

One `MediaPlayer` interface, one `PlaybackState`, one set of Compose controls — backed by
ExoPlayer on Android and AVPlayer on iOS. On top of playback it handles the part most apps
get wrong: **what happens when something interrupts you** — a phone call, backgrounding,
headphones being unplugged, another app taking audio focus — as declarative policy instead
of scattered listeners.

```kotlin
@Composable
fun Screen() {
    val player = rememberVideoPlayer(audioSessionMode = AudioSessionMode.Movie)

    LaunchedEffect(Unit) {
        player.load(MediaSource.Url("https://example.com/video.mp4"))
    }

    FlexibleVideoPlayer(
        player = player,
        modifier = Modifier.fillMaxWidth(),
        surfaceConfig = VideoSurfaceConfig(targetAspectRatio = 16f / 9f),
    )
}
```

That is a complete player: native render surface, transport controls, seek bar, audio session
ownership, and interruption handling.

> **Status:** pre-release. The API is usable and the sample app runs on both platforms, but the
> library is not published to Maven Central yet — see [Installing](#installing).

---

## Contents

- [Features](#features)
- [Modules](#modules)
- [Requirements](#requirements)
- [Installing](#installing)
- [Platform setup](#platform-setup)
- [Quick start](#quick-start)
- [Core concepts](#core-concepts)
- [Interruption policies](#interruption-policies)
- [Using it without Compose](#using-it-without-compose)
- [Previews and tests](#previews-and-tests)
- [Running the sample app](#running-the-sample-app)
- [Build and test](#build-and-test)
- [Known gaps](#known-gaps)

---

## Features

- **One API, two platforms.** `MediaPlayer<MediaSource, VideoPlayerState>` in common code;
  ExoPlayer (media3) and AVPlayer behind it.
- **Video or audio-only, same contract.** `VideoPlayer()` for video, `AudioPlayer()` for music,
  podcasts and audiobooks — identical `MediaPlayer` interface, state machine and interruption
  handling, with no Compose dependency needed for the audio path.
- **Declarative interruption policy.** Pick a preset (`InterruptionConfig.VideoLesson`,
  `MediaPlayerDefault`, `AutoPlay`, `StrictManualResume`, `Uninterruptible`) or compose your own
  from per-source policies. Change it live through a `StateFlow`.
- **Correct under stacked interruptions.** A call that ends while the app is still backgrounded
  does not resume playback — resume happens only when *every* active interruption has cleared.
- **Real audio session ownership.** Android audio focus and iOS `AVAudioSession` behind a single
  `AudioSession` abstraction; playback does not start if ownership is denied.
- **Composable, replaceable UI.** A three-layer player (surface / content overlay / controls
  overlay), two ready-made control templates, and slot composables you can rearrange — or
  hand the whole transport to the platform's native controls with one flag.
- **Explicit state machine.** Every status transition is a graph edge in `:state-machine`,
  not a `when` chain, so illegal transitions are simply absent from the graph.
- **Testable without a device.** `FakeVideoPlayer` implements the same interface with no engine
  behind it, so the entire UI layer renders in `@Preview` and screenshot tests.

## Modules

| Module | What it is | Docs |
|---|---|---|
| [`:ui`](ui/README.md) | Compose Multiplatform player UI — `FlexibleVideoPlayer`, `PlayerState`, control templates | [ui/README.md](ui/README.md) |
| [`:video`](video/README.md) | Video backends — ExoPlayer / AVPlayer / JVM stub | [video/README.md](video/README.md) |
| [`:audio`](audio/README.md) | Audio-only backends — ExoPlayer / AVPlayer / JVM stub | [audio/README.md](audio/README.md) |
| [`:session`](session/README.md) | Audio session ownership, interruption policy engine, system observers, `KMediaManager` wiring | [session/README.md](session/README.md) |
| [`:core`](core/README.md) | The `MediaPlayer` contract, playback state machine, engine seam, error classification | [core/README.md](core/README.md) |
| [`:state-machine`](state-machine/README.md) | Generic graph-based FSM DSL | [state-machine/README.md](state-machine/README.md) |
| [`:sample`](sample/README.md) | Runnable Android + iOS demo | [sample/README.md](sample/README.md) |

Dependencies point strictly upward — no module knows about anything above it:

```
     :state-machine
           |
         :core          ← what a player IS: contract, state machine, engine seam.
           |              No ExoPlayer / AVPlayer, and no third-party dependency at all.
        :session        ← what it is ALLOWED to do: audio session, interruption
         /    \           policy, observers, KMediaManager. Owns every platform dep.
    :video    :audio    ← media3 lives ONLY in these two
         \    /
          :ui
            |
         :sample
```

Most apps depend on `:ui` only; it api-exposes everything below it. An audio-only app can depend on
`:audio` alone and skip Compose entirely.

`:video` and `:audio` are siblings that cannot see each other, so anything both need — the
`MediaPlayer` contract and the shared state machine and engine seam in `kplayer.player` — lives in
`:core`, and anything both need *about the device* lives in `:session`. See
[ADR 0001](docs/adr/0001-sharing-player-logic-between-audio-and-video.md).

`:core` and `:session` split on a line the import graph already drew ([ADR 0002](docs/adr/0002-splitting-core-into-core-and-session.md)): every arrow between them ran
session → playback, so the boundary is now the compiler's to enforce rather than review's. It is
also where the dependencies fall — JNA, `androidx.lifecycle-process`, the `org.w3c.dom` bindings and
the Android `appContext` are all `:session`'s, which leaves `:core` with nothing but coroutines and
`:state-machine`.

## Requirements

| | |
|---|---|
| Kotlin | 2.2.20 |
| Android | minSdk 24, compileSdk 36, JVM target 11 |
| iOS | `iosArm64`, `iosSimulatorArm64`, `iosX64` |
| JVM | compiles, but has **no** media engine — see [Known gaps](#known-gaps) |
| Compose Multiplatform | 1.10.3 (only for `:ui`) |
| media3 | 1.10.0 (Android only) |

## Installing

The library is **not on Maven Central yet**. Consume it as an included build or by adding the
modules to your own multi-module project:

```kotlin
// settings.gradle.kts
includeBuild("../kplayer")   // or copy the modules in
```

```kotlin
// build.gradle.kts — Compose app
commonMain.dependencies {
    implementation(project(":ui"))       // brings :video → :session → :core → :state-machine
}

// build.gradle.kts — no Compose, engine only
commonMain.dependencies {
    implementation(project(":video"))
}
```

Publishing is wired up (`vanniktech.mavenPublish` on `:audio`, `:core`, `:session`, `:video`,
`:ui`) but the
coordinates and POM metadata are still template placeholders, so don't treat
`io.github.kotlin:…:1.0.0` as a real address yet.

## Platform setup

### Android

Two things are required:

**1. Give the library an application context**, before any player is constructed — the audio
focus controller, the becoming-noisy receiver and ExoPlayer all read it:

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeContext(this)   // kplayer.initializeContext
        setContent { App() }
    }
}
```

`Application.onCreate` works just as well and is the better place if you build players outside
an Activity.

**2. Declare the permissions** you actually need, for streaming:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

`WAKE_LOCK` is merged in by media3 — you don't declare it yourself. `VideoSurfaceConfig.keepScreenOn`
goes through Compose's `Modifier.keepScreenOn()`, i.e. the view's `keepScreenOn` flag rather than a
wake lock, and only while playback is actually running.

### iOS

No setup call. `AVAudioSession` is configured on the first `acquire()`, from the
`AudioSessionMode` you pass to the player.

For playback that continues while the app is backgrounded, add the background audio mode to your
`Info.plist` and pair it with `BackgroundPolicy.KeepState`:

```xml
<key>UIBackgroundModes</key>
<array><string>audio</string></array>
```

## Quick start

### The whole player, in Compose

```kotlin
val player = rememberVideoPlayer(
    interruptionConfig = remember { MutableStateFlow(InterruptionConfig.VideoLesson) },
    audioSessionMode = AudioSessionMode.Movie,
)

LaunchedEffect(Unit) { player.load(MediaSource.Url(url)) }

FlexibleVideoPlayer(
    player = player,
    modifier = Modifier.fillMaxWidth(),
    surfaceConfig = VideoSurfaceConfig(targetAspectRatio = 16f / 9f),
) {
    DefaultControlsTemplate()   // `this` is PlayerState — swap in your own chrome
}
```

`rememberVideoPlayer` builds the engine and releases it when the composable leaves the
composition. For playback that must survive configuration changes, build it in a `ViewModel`
with `VideoPlayer(...)` and call `release()` from `onCleared()`.

### Custom controls

Anything the built-in templates do, your own composable can do — they are ordinary extensions on
`PlayerState` with no privileged access:

```kotlin
FlexibleVideoPlayer(player = player) {
    val state = this                     // the slot's receiver is the PlayerState
    Box(Modifier.fillMaxSize()) {
        PlayPauseButton(state, Modifier.align(Alignment.Center))
        Row(Modifier.align(Alignment.BottomCenter)) {
            DurationText(state)
            TimeSeekBar(state, Modifier.weight(1f))
            FullscreenButton(state)
        }
    }
}
```

### Reading state

```kotlin
val state by player.state.collectAsState()

state.status          // Idle / Buffering / Ready / Playing / Paused / Stopped / Completed / Error / Released
state.positionMs      // and durationMs, bufferedPositionMs
state.isPlaying       // extension helpers: isBuffering, hasError, isSeekable, progress, bufferedProgress
state.errorMessage
state.activeSubtitle  // when native subtitle rendering is off
```

## Core concepts

Five types cover almost everything:

| Type | Role |
|---|---|
| `MediaPlayer<S, T>` | The player contract: `load/play/pause/stop/seekTo/setVolume/setPlaybackSpeed/release`, plus `state`, `feedback`, and `onAction`. |
| `MediaSource` | What to play: `Url`, `FilePath`, `AndroidUriString`, `Custom`. |
| `PlaybackAction` | A command as data — `Play`, `SeekTo(ms)`, `Load(source)`, … Everything the UI does goes through `onAction`, which gives you one place to intercept for analytics, logging or policy. |
| `PlaybackState` / `VideoPlayerState` | The single source of truth the UI reads. No second flattened copy. |
| `InterruptionConfig` | Policy for backgrounding, audio focus, headphones and ducking. |

Commands flow one way, events flow back the other:

```
your call ──► MediaPlayer.play() ──► platform player.play()
                                          │
   VideoPlayerState ◄── state machine ◄── native callback (PlaybackEvent)
```

`PlaybackEvent` is what the *native* player reports; you never construct one. The state machine
in `:video` turns those events into `VideoPlayerState`.

## Interruption policies

Build a config, or take one of the presets:

```kotlin
InterruptionConfig(
    backgroundPolicy  = BackgroundPolicy.PauseAndRestore,
    audioFocusPolicy  = AudioFocusPolicy.RestoreIfPlayingBefore,
    headphonesPolicy  = HeadphonesPolicy.PauseAndRequireManualResume,
    duckPolicy        = DuckPolicy.LowerVolume(level = 0.2f),
)
```

| Preset | Backgrounded | Focus loss | Headphones out |
|---|---|---|---|
| `MediaPlayerDefault` | keeps playing | resume if it was playing | pause, manual resume |
| `VideoLesson` | pause, restore on return | resume if it was playing | pause, resume on reconnect |
| `AutoPlay` | pause, restore on return | always resume | keep playing |
| `StrictManualResume` | pause, stay paused | pause, stay paused | pause, manual resume |
| `Uninterruptible` | keeps playing | ignored | ignored (no ducking either) |

Pass a `MutableStateFlow<InterruptionConfig>` to change policy at runtime — the sample app's
"Normal" tab does exactly that.

Two rules govern everything, and they are worth knowing because they explain the behaviour you
will see:

1. An interruption pauses playback only if its policy says so.
2. Auto-resume happens only when **no** interruption is still active, the player was playing when
   the chain started, the strictest policy in the chain allows it, **and** the audio session can be
   re-acquired.

See [core/README.md](core/README.md) for the full policy reference.

## Using it without Compose

`:ui` is optional. `:video` gives you a fully-wired engine with the audio session, interruption
handler and system observers already attached:

```kotlin
val player: MediaPlayer<MediaSource, VideoPlayerState> = VideoPlayer(
    interruptionConfig = MutableStateFlow(InterruptionConfig.MediaPlayerDefault),
    audioSessionMode = AudioSessionMode.Movie,
)

player.load(MediaSource.Url(url))
player.play()
scope.launch { player.state.collect { render(it) } }

// when done
player.release()
```

You will have to attach a render surface yourself. `VideoPlayer()` returns a `KMediaManager`
decorator, so the native handle is one level down:

```kotlin
val backend = (player as KMediaManager<*, *, *>).player
(backend as AndroidVideoPlayer).exoPlayer.setVideoSurfaceView(surfaceView)   // Android
(backend as IosVideoPlayer).avPlayer                                        // iOS
```

Use those handles for **rendering only** — issue transport commands through `MediaPlayer`, or the
state machine and interruption engine fall out of sync. This unwrapping is exactly what `:ui`'s
`NativeVideoSurface` does for you.

`MediaPlayer { … }` (the builder in `:session`) is the escape hatch below that: it lets you supply
your own backend, audio session or observers. See [core/README.md](core/README.md).

### Audio-only

For music, podcasts or audiobooks, depend on `:audio` instead of `:ui`/`:video`. Same contract,
nothing to render:

```kotlin
val player: MediaPlayer<MediaSource, AudioPlayerState> = AudioPlayer(
    audioSessionMode = AudioSessionMode.Speech,   // Music by default
)

player.load(MediaSource.Url(url))
scope.launch { player.state.collect { render(it) } }

// when done
player.release()
```

The default interruption policy is `MediaPlayerDefault` rather than video's `StrictManualResume`,
because a listener expects playback to come back on its own after a phone call. The native handles
(`AndroidAudioPlayer.exoPlayer`, `IosAudioPlayer.avPlayer`) are reachable the same way as above —
use them for `MediaSession` / `MPNowPlayingInfoCenter` integration, not transport. See
[audio/README.md](audio/README.md).

## Previews and tests

`FakeVideoPlayer` is a real `MediaPlayer` with no engine, no I/O and no audio session, so the
control layer renders in a `@Preview` or a screenshot test:

```kotlin
@Preview
@Composable
fun ControlsPreview() {
    val player = rememberFakeVideoPlayer(
        VideoPlayerState(status = PlaybackStatus.Playing, positionMs = 42_000, durationMs = 225_000)
    )
    FlexibleVideoPlayer(player = player, modifier = Modifier.aspectRatio(16f / 9f))
}
```

It needs no support from the library — `FlexibleVideoPlayer` takes the `MediaPlayer` interface, so
a fake is just another implementation. For controls with no player at all, use the
`PlayerState` overload of `FlexibleVideoPlayer` with a hand-written state literal.

## Running the sample app

The sample has two screens: a 16:9 workbench exposing every knob the library has, and a
vertical short-form feed showing player handover between pages.

**Android**

```bash
./gradlew :sample:installDebug
```

**iOS** — the Xcode project is checked in:

```bash
open sample/iosApp/iosApp.xcodeproj
```

Build and run the `iosApp` scheme. A build phase runs
`:sample:embedAndSignAppleFrameworkForXcode`, so the Kotlin framework is built and linked
automatically.

## Build and test

```bash
./gradlew build                       # everything

./gradlew :core:jvmTest               # fastest feedback loop
./gradlew :session:jvmTest
./gradlew :audio:jvmTest
./gradlew :video:jvmTest
./gradlew :state-machine:jvmTest

./gradlew :session:jvmTest --tests "kplayer.DefaultPlaybackInterruptionHandlerTest"

./gradlew :video:compileAndroidMain           # compile without running tests
./gradlew :video:compileKotlinIosSimulatorArm64

./gradlew :audio:assembleAndroidMain          # :audio, all three targets
./gradlew :audio:compileKotlinJvm
./gradlew :audio:compileKotlinIosSimulatorArm64
```

## Known gaps

Honest list of what is not there yet:

- **JVM/desktop has no engine.** The target compiles and `VideoPlayer()` / `AudioPlayer()` return
  players that emit `PlaybackFeedback.Rejected` for every command. They exist so shared code
  type-checks and so `:core` logic can be unit-tested on the JVM.
- **`:audio` has no playlist/queue support** and no `MediaSession` / `MPNowPlayingInfoCenter`
  integration for lock-screen controls — the exposed `exoPlayer` / `avPlayer` handles are the seam
  for building the latter.
- **`:video` is not on the engine seam.** The seam and all the sequencing above it are shared in
  `:core`, and `:audio` runs on them with 17 backend tests; `:video` still drives ExoPlayer and
  `AVPlayer` inline, so only its state machine is tested. The native translation inside the engines
  is untested on both sides.
- **`:video` does not use the engine seam yet.** The state machine, abstract player and engine
  contract are shared in `:core` (see [ADR 0001](docs/adr/0001-sharing-player-logic-between-audio-and-video.md)),
  and `:audio` runs on the seam with 17 backend tests; `:video` still drives ExoPlayer and `AVPlayer`
  inline, so only its state machine is tested. Its iOS KVO observers are still its own.
- **`bufferedPositionMs` is never populated** by either backend, on either platform.
- **Not published.** Maven coordinates and POM metadata are placeholders, and `:state-machine`
  has no publishing block at all even though `:core` api-exposes it.
- **Denied audio ownership is silent.** If `acquire()` fails, `play()`/`load()` simply do nothing;
  there is no `PlaybackFeedback` for it yet.
- **No player pool.** Feed-style UIs build and tear down an engine per settled page (see the
  Reels screen); there is no pre-warming.
- **`:ui` is Compose-only** and depends on media3-ui on Android.
- **`:state-machine` has no tests**, despite being the module every status transition runs through.

## License

[Apache License 2.0](LICENSE).
