# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Build all modules
./gradlew build

# Run JVM tests (fastest feedback loop)
./gradlew :core:jvmTest
./gradlew :session:jvmTest
./gradlew :audio:jvmTest
./gradlew :video:jvmTest
./gradlew :state-machine:jvmTest

# Run a single test class (JVM)
./gradlew :session:jvmTest --tests "kplayer.DefaultPlaybackInterruptionHandlerTest"

# Compile a module for Android / iOS without running tests
# (compileAndroidMain is a lifecycle task — use assembleAndroidMain to force Kotlin compilation)
./gradlew :video:assembleAndroidMain
./gradlew :video:compileKotlinIosSimulatorArm64
./gradlew :audio:assembleAndroidMain
./gradlew :audio:compileKotlinIosSimulatorArm64

# Run Android host tests (no device needed — these are host tests)
./gradlew :core:testAndroidHostTest      # the media3 error table
./gradlew :session:testAndroidHostTest   # the Android focus mapping

# Install Android sample app
./gradlew :sample:installDebug

# iOS: generate Xcode project (one-time, requires xcodegen)
xcodegen generate --spec sample/iosApp/project.yml
open sample/iosApp/iosApp.xcodeproj
# Xcode's pre-build step runs :sample:embedAndSignAppleFrameworkForXcode automatically
```

## Module Architecture

Seven Gradle modules, all KMP:

| Module | Role | Targets |
|--------|------|---------|
| `:state-machine` | Generic graph-based FSM DSL | Android, JVM, iOS, Linux |
| `:core` | The `MediaPlayer` contract, playback state machine, engine seam, error classification | Android, JVM, iOS, wasmJs |
| `:session` | Audio session ownership, interruption policy engine, observers, `KMediaManager` wiring | Android, JVM, iOS, wasmJs |
| `:video` | Video backends | Android (ExoPlayer), iOS (AVPlayer), JVM (AVFoundation/IMFMediaEngine/GStreamer via JNA) |
| `:audio` | Audio-only backends | Android (ExoPlayer), iOS (AVPlayer), JVM stub |
| `:ui` | Compose Multiplatform player UI (stub) | Android, JVM, iOS |
| `:sample` | Compose Multiplatform demo app | Android + iOS |

Dependency direction is strictly bottom-up:

```
     :state-machine
           |
         :core          ← what a player IS. No ExoPlayer/AVPlayer, and no
           |              third-party dependency beyond coroutines.
        :session        ← what it is ALLOWED to do. Owns AudioSession, the
         /    \           policy engine, the observers and every platform dep.
    :video    :audio    ← media3-exoplayer lives ONLY in these two
         \    /
     :ui  /  :sample
```

`:core` uses `api()` for `:state-machine` because `PlaybackStatus` and
`PlaybackEvent` implement its `State` / `Event` interfaces; `:session` api-exposes
`:core`, and `:video` / `:audio` api-expose both.

`:video` and `:audio` are **siblings that cannot see each other**, so anything both
need lives below them — see `kplayer.player` in `:core`, which holds the shared state
machine, abstract player and engine seam (ADR 0001). Nothing engine-free should be
written twice; if a change seems to belong in both backends, it belongs in `:core`
(if it is about playback) or `:session` (if it is about the device).

**The `:core` / `:session` boundary is the one to keep honest** (ADR 0002). Every import between
them runs session → playback, and the module split is what enforces it: nothing in
`kplayer.state`, `kplayer.event` or `kplayer.player` may name `kplayer.core.audio`,
`kplayer.interruption`, `kplayer.observers` or `kplayer.engine`. If a change seems to
need that, the design is wrong, not the boundary.

The `MediaPlayer { }` factory is the one thing that looks like it contradicts this: it
keeps the `kplayer` package so `import kplayer.MediaPlayer` still finds it, but its
file lives in `:session` (`MediaPlayerFactory.kt`), because it builds a
`KMediaManager`. The interface it reads as a constructor for stays in `:core`.

Three consequences of this layering:
- **`appContext`** (`kplayer.AndroidContext`, set via `initializeContext()`) lives in
  `:session` because the audio session and the becoming-noisy receiver (both
  `:session`) need it, and the ExoPlayer instances (`:video`, `:audio`) sit above it.
  `:core` never touches it.
- **Audio attributes are mapped in three places** over the same `AudioSessionMode`:
  `kplayer.core.audio.platformAudioAttributesFor` (`:session`, `android.media`, for focus),
  `kplayer.videoplayer.exoPlayerAudioAttributesFor` (`:video`, media3) and
  `kplayer.audioplayer.exoAudioAttributesFor` (`:audio`, media3) for the output
  stream. A new `AudioSessionMode` must be handled in all three.
- **The audio session lives below both engines**, so `KMediaManager` arbitrates focus
  identically for audio and video — neither backend touches `AudioSession` itself.

## State Machine DSL (`:state-machine`)

The `graph { }` DSL produces a `Graph` that drives transitions. Key concepts:

- **`State`** and **`Event`** are empty marker interfaces — implement them in domain code.
- **`MachineState`** wraps the graph's runtime state: `Inactive`, `Dwelling(node)`, or `Traversing(edge)`.
- Transitions are defined per-state via `on(event) { transitionTo(targetState) }` — this builds `Node.edgeTriggers: Map<Event, Edge>`, so dispatching is O(1) hash lookup, not a `when` chain.
- **`Decision`** — a node can auto-fire a follow-up event on entry, enabling compound transitions without explicit callers.
- **`ActionResult`** — edge actions receive an `ActionResult` receiver; call `fail()` to abort the transition (stays in source state) or `failAndExit()` to abort and trigger `onExit`.
- `graph.consume(event)` is the primary entry point; `graph.observe<T>()` / `graph.observeStateChanges()` return `Flow` for reactive observation.

```kotlin
// Minimal usage pattern
val machine = graph {
    initialState(MyState.Idle)
    state(MyState.Idle) {
        on(MyEvent.Start) { transitionTo(MyState.Running) }
    }
    state(MyState.Running) {
        on(MyEvent.Stop) { transitionTo(MyState.Idle) }
    }
}
machine.start()
machine.consume(MyEvent.Start)
machine.observe<MyState>().collect { /* reacts to state dwells */ }
```

## Video Player Architecture (`:video`)

### Layering

```
VideoPlayer (interface)
    └── AbstractVideoPlayer          — owns VideoPlayerStateMachine + SharedFlow<feedback>
            ├── AndroidVideoPlayer   — ExoPlayer, fires PlaybackEvents from Player.Listener
            ├── IosVideoPlayer       — AVPlayer, fires PlaybackEvents from KVO observers
            ├── VideoPlayer.jvm.kt   — stub
            └── VideoPlayer.ios.kt   — expect/actual wiring
```

Platform classes implement only `execute(action: PlaybackAction)`. All state management lives in `AbstractVideoPlayer` via `VideoPlayerStateMachine`.

### Data flow

```
User call  →  VideoPlayer.play()
           →  AbstractVideoPlayer.execute(PlaybackAction.Play)   [dispatched to platform]
           →  platform calls player.play()
           →  native callback fires  →  onEvent(PlaybackEvent.PlaybackStarted)
           →  VideoPlayerStateMachine.reduce()  →  updates StateFlow<VideoPlayerState>
```

`PlaybackAction` = commands from callers.  
`PlaybackEvent` = notifications from the native player (and internal triggers).  
`VideoPlayerState` = the single source of truth exposed to UI.

### State machine wiring

`VideoPlayerStateMachine` in `commonMain` uses the `:state-machine` graph DSL for all status transitions. Each `PlaybackStatus` enum value is a graph node; each `PlaybackEvent` subtype is dispatched by type (KClass key) via `on<T> { transitionTo(...) }`. Three events bypass the graph:
- `PositionSynced` — updates `positionMs` directly on the StateFlow, no status change
- `Failure` / `ReleaseRequested` — global transitions called via `machine.transitionTo(...)` directly

State updates (source, positionMs, durationMs, errorMessage) happen in each node's `onEnter` block, which receives the triggering event as the `trigger: Event?` parameter.

**Transitions are synchronous and serialized, with no dispatcher involved.** `Graph.consume` / `transitionTo` / `start` do not suspend: they run the edge action inline on the calling thread and commit before returning, so `onEvent(e)` followed by `state.value` on the next line always reads the new state — which the platform backends rely on. Every entry point runs under `Graph.withTransitionLock`, a re-entrant lock (`ReentrantGuard`: `java.util.concurrent.locks.ReentrantLock` on JVM/Android, a `PTHREAD_MUTEX_RECURSIVE` mutex on iOS, a hold counter on wasmJs), so a second thread waits for the whole transition — nested `Decision` chains included — instead of interleaving with it. Re-entrancy is what lets a `Decision` call back into `consume` from inside the transition that raised it.

Two consequences when editing:
- **`EdgeAction` cannot suspend.** Anything that needs to suspend belongs outside the graph — launch it from an `onEnter` hook after the transition lands.
- **The state machine has no scope.** `PlaybackStateMachine` takes no dispatcher and the backends no longer have a `stateMachineScope` parameter: transitions run inline under the graph's lock, so nothing can defer one or leave `state.value` stale behind it. `AudioPlayerStateMachineTest` pins this down.

`Decision` chains are bounded by `MAX_TRANSITION_DEPTH` (100) — a cycling decision raises `IllegalStateException` naming the edge instead of overflowing the stack.

Each backend's own `scope` (for dispatching `PlaybackAction`s to the native player) is a **separate** injectable parameter and must stay main-thread bound — ExoPlayer rejects off-main calls and `AVPlayer` mutation off-main is undefined. The `KMediaManager` scope built in `KMediaManagerBuilder.build()` is deliberately *not* injectable: `release()` cancels it unconditionally, so it must stay library-owned.

The platform (Android/iOS) is responsible for the auto-play after buffering: when `PlaybackEvent.Ready` fires and `playWhenReady == true`, the platform calls `play()`, which eventually fires `PlaybackEvent.PlaybackStarted` → `PlaybackStatus.Playing`.

### Platform specifics

- **Android**: `AudioAttributes` with `handleAudioFocus=true` delegates audio focus to ExoPlayer. "Headphones unplugged" = `ACTION_AUDIO_BECOMING_NOISY` broadcast (not handled by ExoPlayer automatically).
- **iOS**: `AVAudioSessionCategoryPlayback` activated at init. KVO on `rate`, `status`, and `playbackLikelyToKeepUp`. Headphone disconnect = `AVAudioSessionRouteChangeReasonOldDeviceUnavailable`.
- **iOS tests**: `IosVideoPlayer.testResourceLoaderDelegate` allows injecting an in-memory `AVAssetResourceLoaderDelegate` under the `mockfile://` scheme so tests don't need real network access.

### `VideoSource` variants

`Url`, `FilePath`, `AndroidUriString`, `Custom` — each platform's `toAndroidUri()` / `toIosUrl()` maps these to the native type.

## Audio Player Architecture (`:audio`)

The same shape as `:video` plus one extra layer — an engine seam that `:video` does not have yet:

```
:core   MediaPlayer (interface)
          └── AbstractMediaPlayer<S>   — PlaybackStateMachine<S> + SharedFlow<feedback>
              └── EngineMediaPlayer<S> — ALL backend logic, driving a MediaEngine
:audio            ├── AndroidAudioPlayer  → ExoAudioEngine  (ExoPlayer)
                  ├── IosAudioPlayer      → AvAudioEngine   (AVPlayer)
                  └── (tests)             → FakeMediaEngine
                  AudioPlayer.jvm.kt      — stub that rejects every command via feedback
```

Everything above the engines lives in `:core`'s `kplayer.player` and is shared with `:video`
(ADR 0001). `:audio` owns only `AudioPlayerState` and its two engines.

`AudioPlayer(interruptionConfig, audioSessionMode)` is the `expect fun` entry point; it
returns the backend already wrapped in a `KMediaManager`, exactly like `VideoPlayer()`.

**`AndroidAudioPlayer` and `IosAudioPlayer` contain no logic** — they name an engine and expose its
native handle (`exoPlayer` / `avPlayer`). Everything else is in `EngineMediaPlayer`: action
dispatch, collecting the engine's events, buffering de-duplication, `playWhenReady`
auto-play, volume clamping, and one shared position-sync loop that polls
`MediaEngine.currentPositionMs()` (replacing both the Android coroutine loop and iOS's
`addPeriodicTimeObserverForInterval`).

**Facts come up as a flow, not a listener.** An engine reports through `MediaEngine.events`, and
`AbstractMediaEngine` (in `:core`'s `kplayer.player`) owns that flow — extend it and call
`reportPlaying` / `reportBuffering` / `reportReady` / `reportCompleted` / `reportError`, or `report`
for a medium-specific event like `SubtitleCueChanged`. None of them suspend or care about the calling
thread, which is what lets the desktop engines report from a poll thread. `EngineMediaPlayer` is the
only subscriber and subscribes **undispatched in its constructor**: the flow does not replay, so a
subscription merely scheduled on the action scope would drop whatever the engine reported first.

**Failures have one route out.** `EngineMediaPlayer.execute()` is the single error-handling
boundary: `runAction` wraps the dispatch `when`, describes anything thrown through the `errorMapper`
and hands it — along with every `PlaybackEvent.Failure` an engine reports — to `reportFailure`, the
only place that gives the machine a failure. `PlaybackError` (in `:core`'s `kplayer.state`) describes
*what* failed and carries no retry flag; `PlaybackRetryPolicy` decides whether the action runs again,
and defaults to `None`. A synchronous throw retries the action itself; an engine fault retries a
reload, because a faulted engine has thrown its prepared item away.

**Engines extract, `:core` classifies, and one `expect` joins them.** Whatever a platform hands an
engine — a media3 `errorCode`, a failed item's `NSError`, a GStreamer bus message, a `MediaError`
code, a plain throw — the engine wraps in a `NativeError` and calls `toPlaybackError()`. That
extension is the *only* `expect` in the error path. `NativeError` itself is an `expect class` whose
fields are declared by each `actual`, so `commonMain` names no backend at all and every target's
factories (`NativeError.media3`, `NativeError.avError`, `NativeError.gstreamer`,
`NativeError.mediaElement` / `.rejected`) exist only where they mean something. The tables live with
the actuals: `androidMain` for media3, `wasmJsMain` for the browser, `jvmMain` for GStreamer,
`jvmSharedMain` for the JDK exceptions Android and desktop both throw, and `appleSharedMain` — a
source set only `iosMain` and `jvmMain` see — for the `NSError` table the two Apple engines reach by
completely different interop. An engine with nothing better to say still calls the
`reportError(message: String)` shorthand and gets `PlaybackError.Unknown`.

When adding behaviour, put it in `EngineMediaPlayer` and test it against `FakeMediaEngine`. Only put
something in an engine if it is native translation, and obey two rules there:
1. **Translate quirks in the engine, not upstream** — `ExoAudioEngine` swallows the spurious
   `isPlaying=false` at end-of-media (it would read as a pause and flash `Paused` before
   `Completed`); `AvAudioEngine` re-applies speed after `play()` since `AVPlayer` resets `rate` to 1.0.
2. **Never report state you were told to enter** — wait for the native callback, or `PlaybackState`
   describes intentions instead of facts.

What differs from `:video`, and why:

- **`AudioPlayerState` carries no `activeSubtitle`**, so it passes no `reduceCustom` / `onLoad`
  hooks to the shared machine and `SubtitleCueChanged` is absorbed. Video supplies both hooks
  from `VideoPlaybackStateMachine.kt`.
- **Audio attributes are actually applied**: `ExoPlayer.Builder.setAudioAttributes(attrs,
  handleAudioFocus = false)`. `AndroidVideoPlayer` computes them but never sets them.
- **ExoPlayer, not `android.media.MediaPlayer`**, even with nothing rendering — audio apps
  stream, so adaptive HLS/DASH and real buffering behaviour matter.
- **`AVPlayer`, not `AVAudioPlayer`**, on iOS, for the same reason: `AVAudioPlayer` only
  plays fully-available local data.
- **KVO observers are still `:audio`-local** (`AudioRateObserver`, `AudioItemStatusObserver`,
  `AudioBufferingObserver` in `kplayer.audioplayer`) with their own copy of the
  `nskeyvalueobserving` cinterop def. Distinct names and package from `:video`'s so both
  can be linked into one iOS framework; they are the last engine-adjacent duplication, and
  collapse into `:core` once `:video` moves onto the seam.

Known gap: `bufferedPositionMs` is never populated, the same as in `:video`.

### Testing the backends

`:audio:jvmTest` runs with no device: `AudioPlayerStateMachineTest` covers transitions and the
synchrony of `onEvent`, `EngineMediaPlayerTest` covers the backend — including the error boundary
and retry — using `FakeMediaEngine` to play the native player's part (`throwingCalls` arms a method
to throw, which is how a synchronous native failure is reproduced).

Three things that will bite when extending them:

- **Cancel the test scopes in a `finally`** (the `withPlayer` helper does). The position-sync loop
  shares the test scheduler, so a failed assertion that skips cancellation leaves `runTest`
  advancing virtual time against an infinite `delay` — the test hangs instead of failing.
- **Buffering de-duplication is invisible in `state`.** `StateFlow` conflates equal values, so N
  `BufferingStarted`s look like one. Assert that repeats leave the state object unchanged instead.
- **Kotlin/Native rejects `,` in backticked test names.** A name that compiles for `jvmTest` can
  still fail `:audio:compileTestKotlinIosSimulatorArm64`; run that as well.

`:video` is not on the seam yet, so only its state machine is tested. Porting
`AndroidVideoPlayer` / `IosVideoPlayer` onto `MediaEngine` would let them reuse
`EngineMediaPlayerTest` wholesale — the obvious next step.
