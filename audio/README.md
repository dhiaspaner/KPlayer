# `:audio` — audio-only playback backends

The audio sibling of `:video`: ExoPlayer on Android, `AVPlayer` on iOS, both behind the same
`MediaPlayer` contract from `:core`. No render surface, no subtitles — everything else is the same
player.

Targets: Android, iOS, JVM (rejecting stub).

> Audio **session ownership** (`AudioSession`, focus, `AVAudioSession`) used to live here. It moved
> down into `:core` as `kplayer.core.audio` when this module became a player: `:video` and `:audio`
> are siblings, so anything both need has to sit below both.

---

## Quick start

```kotlin
val player = AudioPlayer(audioSessionMode = AudioSessionMode.Speech)

player.load(MediaSource.Url("https://example.com/episode.mp3"))

player.state.collect { state ->
    println("${state.status} ${state.positionMs}/${state.durationMs}")
}
```

That is a complete player: audio session ownership, focus arbitration and interruption handling are
already wired, because `AudioPlayer()` returns the backend wrapped in a `KMediaManager`.

```kotlin
expect fun AudioPlayer(
    interruptionConfig: StateFlow<InterruptionConfig> = MutableStateFlow(InterruptionConfig.MediaPlayerDefault),
    audioSessionMode: AudioSessionMode = AudioSessionMode.Music,
): MediaPlayer<MediaSource, AudioPlayerState>
```

The default policy is `MediaPlayerDefault`, not video's `StrictManualResume`: a music or podcast
listener expects playback to come back on its own after a phone call, where a viewer generally does
not. Pass a `StateFlow` to change policy live — see `:core`'s README for the policy engine.

`audioSessionMode` decides the native audio category and focus attributes:
`AudioSessionMode.Speech` for podcasts and audiobooks, `Music` for music.

## Layering

```
:core   MediaPlayer (interface)
          └── AbstractMediaPlayer<S>   — PlaybackStateMachine<S> + SharedFlow<feedback>
              └── EngineMediaPlayer<S> — ALL the backend logic, driving a MediaEngine
:audio            ├── AndroidAudioPlayer  → ExoAudioEngine  (ExoPlayer)
                  ├── IosAudioPlayer      → AvAudioEngine   (AVPlayer)
                  └── (tests)             → FakeMediaEngine
                  AudioPlayer.jvm.kt      — stub, rejects every command via feedback
```

The top three layers live in `:core` and are shared with `:video`; only the engines and the state
type belong to this module.

`EngineMediaPlayer` is where the player actually lives: action dispatch, the native-callback to
`PlaybackEvent` mapping, buffering bookkeeping, the `playWhenReady` auto-play, volume clamping and
the position-sync loop. `AndroidAudioPlayer` and `IosAudioPlayer` contain **no logic at all** — they
name an engine and expose its native handle.

That split is what makes the backend testable: swap in `FakeMediaEngine` and the whole contract runs
on the JVM with no device and no media. See [Testing](#testing).

### The `MediaEngine` seam

```kotlin
interface MediaEngine {
    val events: Flow<PlaybackEvent>               // facts out; never replays
    fun setSource(source: MediaSource): Boolean   // false = cannot represent it
    fun prepare()
    fun play(); fun pause(); fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float); fun setVolume(volume: Float)   // volume pre-clamped
    fun currentPositionMs(): Long
    fun release()
}
```

Engines extend `AbstractMediaEngine`, which owns `events` and names each fact, so the native
callback reads as what the player observed rather than as an event constructor:

```kotlin
reportPlaying(isPlaying)      // PlaybackStarted / PlaybackPaused
reportBuffering(isBuffering)  // BufferingStarted / BufferingEnded — repeats are collapsed upstream
reportReady(durationMs)       // 0 = unknown, e.g. live
reportCompleted()
reportError(error)            // a PlaybackError, or a String where that is all the stack gives
report(event)                 // anything medium-specific, e.g. SubtitleCueChanged
```

`report…` never suspends and is safe from any thread — which is what lets the desktop engines report
from a poll thread — and `EngineMediaPlayer` is the flow's only subscriber, collecting on its own
scope. Since the flow does not replay, it subscribes in its constructor: an engine that faults on its
first native call still reaches the state machine.

Two rules for an implementation, both of them things a native player will get wrong if you let it:

1. **Translate quirks here, not upstream.** `ExoAudioEngine` swallows the spurious
   `isPlaying = false` ExoPlayer emits at end-of-media (upstream that reads as a pause, and the
   player visibly steps through `Paused` before `Completed`); `AvAudioEngine` re-applies playback
   speed after `play()`, because `AVPlayer` silently resets `rate` to 1.0. Neither quirk is visible
   above the seam.
2. **Never report state you were told to enter.** `play()` must not call `reportPlaying(true)`
   itself — wait for the native callback, or `PlaybackState` starts describing intentions instead of
   facts.

### Data flow

```
Caller     →  MediaPlayer.play()
           →  EngineMediaPlayer.execute(PlaybackAction.Play)   [dispatched to platform]
           →  platform calls player.play()
           →  native callback fires  →  onEvent(PlaybackEvent.PlaybackStarted)
           →  PlaybackStateMachine     →  updates StateFlow<AudioPlayerState>
```

Commands go down as `PlaybackAction`, facts come up as `PlaybackEvent`, and the two never cross.
`state` therefore reflects what the engine *did*, not what was asked of it.

### `AudioPlayerState`

A plain `PlaybackState` with no extra fields — `VideoPlayerState` adds `activeSubtitle`, and audio
has no equivalent. It is a separate type from the video state so the two can diverge (playlists,
gapless, metadata) without either module inheriting the other's concerns.

### State machine

`:core`'s `PlaybackStateMachine<AudioPlayerState>` uses `:state-machine`'s `graph { }` DSL: one node per `PlaybackStatus`,
transitions keyed by `PlaybackEvent` type. Four events bypass the graph:

| Event | Why |
|---|---|
| `PositionSynced` | fires twice a second, carries no status meaning |
| `SpeedChanged` / `VolumeChanged` | status-neutral |
| `Failure` / `ReleaseRequested` | valid from *any* state, so they use `transitionTo` directly |
| `SubtitleCueChanged` | cannot happen without a surface — absorbed, not dispatched |

**The machine takes no scope at all.** `onEvent` applies the event on the calling thread, under the
graph's re-entrant transition lock, and returns only once the state has been committed — so a
platform callback can read the resulting state on the next line, and no dispatcher choice can make
that untrue. It also means the machine can be driven from a plain JVM test with no main dispatcher
installed; `AudioPlayerStateMachineTest` pins the synchrony down.

The one scope a backend does take is its **action** scope, which dispatches commands to the native
player and must stay main-thread bound — ExoPlayer rejects calls from anywhere else, and `AVPlayer`
mutation off the main thread is undefined.

## Platform specifics

Each engine is a thin adapter; the player class above it holds nothing but the native accessor.

### Android — `ExoAudioEngine`

- **ExoPlayer, not `android.media.MediaPlayer`**, even though nothing is rendered: audio apps
  stream, and ExoPlayer brings adaptive HLS/DASH audio, usable buffering, and the same
  `Player.Listener` model the video backend already maps.
- `setAudioAttributes(attrs, handleAudioFocus = false)` — the attributes are applied so the output
  stream (routing, volume curve, Bluetooth profile) matches what was negotiated for focus, but
  focus arbitration itself stays with `AudioSession`.
- At end-of-media ExoPlayer flips `isPlaying` to false *and* enters `STATE_ENDED`. The engine
  swallows that report so `EngineMediaPlayer` never sees a pause and the status goes straight to
  `Completed`.
- `AndroidAudioPlayer.exoPlayer` is exposed for integrations that need the real `Player` — a
  `MediaSession` for the lock screen, an analytics listener. Do not issue transport commands or
  release it through that handle.

### iOS — `AvAudioEngine`

- **`AVPlayer`, not `AVAudioPlayer`**: the latter only plays fully-available local data, so it
  cannot stream a URL or handle HLS.
- KVO on `rate` (play/pause), `status` (ready/failed) and `playbackLikelyToKeepUp` (buffering),
  plus `AVPlayerItemDidPlayToEndTimeNotification` for completion.
- Observers live in `kplayer.audioplayer` (`AudioRateObserver`, `AudioItemStatusObserver`,
  `AudioBufferingObserver`) with their own copy of the `nskeyvalueobserving` cinterop def —
  distinct names and package from `:video`'s equivalents so both modules can be linked into one
  iOS framework. `observeValueForKeyPath` cannot be overridden on `NSObject` from Kotlin/Native,
  hence the cinterop.
- `play()` re-applies a non-default speed *after* starting, because `AVPlayer.play()` always
  resumes at rate 1.0. `setSpeed` on a paused player only records the speed, since setting a
  non-zero rate would start playback.
- `setSource` builds the `AVPlayerItem` and `prepare()` hands it to the player — handing it over is
  what begins loading, so the two-step seam maps cleanly onto AVFoundation.
- Position comes from polling `currentTime()` in `EngineMediaPlayer`'s loop rather than
  `addPeriodicTimeObserverForInterval`, so both platforms share one implementation that a test can
  drive on virtual time.
- Nothing here configures `AVAudioSession` — it is a process-wide singleton owned by `:core`'s
  `IosAudioSession`.

### JVM

`AudioPlayer()` returns a stub whose every command emits
`PlaybackFeedback.Rejected("JVM target has no audio engine implementation")` and whose state stays
`Idle`. It exists so shared code compiles and runs on the JVM target.

## Relationship to `:video`

The two modules are siblings and **cannot see each other**, so everything both need lives in
`:core`'s `kplayer.player` — the state machine, the abstract player, the engine seam and the
`MediaSource` URI mapping are all shared, not duplicated. See
[ADR 0001](../docs/adr/0001-sharing-player-logic-between-audio-and-video.md).

What is left in each module is only what genuinely differs:

| | `:audio` | `:video` |
|---|---|---|
| State | `AudioPlayerState` | `VideoPlayerState` + `activeSubtitle` |
| Machine hooks | none | `VideoCueReducer`, `VideoOnLoad` |
| Engines | `ExoAudioEngine`, `AvAudioEngine` | inline in the players — **not yet on the seam** |

Two places where this module is still ahead and `:video` has not caught up:

- **No engine seam in `:video`.** `AndroidVideoPlayer` and `IosVideoPlayer` still hold their logic
  inline, so only their state machine is tested. Porting them onto `MediaEngine` is the obvious next
  step, and would let them reuse `EngineMediaPlayerTest` wholesale.
- `:audio` applies its ExoPlayer audio attributes; `AndroidVideoPlayer` computes them and never
  sets them.
- `:video`'s iOS buffering observer guards on `keyPath == "playbackBufferEmpty"` while being
  registered for `playbackLikelyToKeepUp`, and casts the change value straight to `Boolean`, so it
  never fires. The observers here fix both — they stay module-local until `:video` is ported.

## Testing

Two suites, 53 tests, both on `:audio:jvmTest` — no device, no simulator, no media:

| Suite | Covers |
|---|---|
| `AudioPlayerStateMachineTest` (24) | every status transition, plus the synchrony of `onEvent` |
| `EngineMediaPlayerTest` (29) | the backend: load, auto-play, buffering, position sync, transport, failures, the error boundary and retry |

`FakeMediaEngine` records what the player asked of it and exposes the `report…` calls as `emit…`, so
a test plays the native player's part:

```kotlin
f.player.load(source)
f.engine.emitReady(durationMs = 100_000)          // engine says "loaded"

assertEquals(1, f.engine.playCount)               // player auto-played
assertEquals(PlaybackStatus.Ready, f.state.status) // but status waits for confirmation

f.engine.emitPlaying(true)
assertEquals(PlaybackStatus.Playing, f.state.status)
```

Because the events are driven by hand, orderings a real engine produces only rarely are cheap to
assert on — a failure arriving mid-buffer, a completion immediately after a pause, a source the
engine refuses.

Position sync runs on virtual time: both scopes use `UnconfinedTestDispatcher(testScheduler)`, so
`advanceTimeBy(600)` steps the 500 ms loop. Two traps worth knowing if you extend these tests:

- **Always cancel the scopes in a `finally`** — the `withPlayer` helper does. The sync loop shares
  the test scheduler, so an assertion failure that skipped cancellation leaves `runTest` advancing
  virtual time against an infinite `delay`, and the test hangs instead of reporting the failure.
- **Buffering de-duplication is not observable through `state`.** `StateFlow` conflates equal
  values, so three `BufferingStarted`s in a row look identical to one. The test asserts the
  observable invariant instead: repeated reports leave the state object unchanged.

Kotlin/Native rejects `,` in backticked test names, so a name that compiles on JVM can still break
`:audio:compileTestKotlinIosSimulatorArm64` — worth running that too.

## Build and test

```bash
./gradlew :audio:jvmTest                                  # 38 tests, no device needed

./gradlew :audio:compileKotlinJvm
./gradlew :audio:assembleAndroidMain
./gradlew :audio:compileKotlinIosSimulatorArm64
./gradlew :audio:compileTestKotlinIosSimulatorArm64
```

## Known gaps

- `bufferedPositionMs` is never populated (same as `:video`).
- No playlist / queue support — one `MediaSource` at a time.
- No `MediaSession` or `MPNowPlayingInfoCenter` integration for lock-screen controls; the exposed
  `exoPlayer` / `avPlayer` handles are the seam for building it.
- **The engines themselves are untested.** `EngineMediaPlayerTest` covers everything above the
  seam, but the media3 and AVFoundation translation inside `ExoAudioEngine` / `AvAudioEngine` is
  only verified by compiling — that needs a device or simulator.
