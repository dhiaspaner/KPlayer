# `:core` — the playback contract and the shared backend

The bottom of the playback stack (only `:state-machine` sits below it): the `MediaPlayer` contract
every backend implements, the vocabulary (`PlaybackAction` / `PlaybackEvent` / `PlaybackState`),
the state machine that turns one into the other, and the engine seam every backend is built from.

**No ExoPlayer or AVPlayer here.** This module knows what a player *is*, not how one decodes.
The engines live in the two sibling modules above it, `:video` and `:audio`.

**No audio session or interruption policy here either.** Those moved to
[`:session`](../session/README.md), which depends on this module and never the other way round —
nothing in `kplayer.state`, `kplayer.event` or `kplayer.player` may name the audio session, the
policy engine, the observers or `KMediaManager`. That direction is now a module boundary rather
than a convention, which is the whole reason the split exists.

One consequence worth knowing up front: this module has no third-party dependency at all beyond
coroutines and `:state-machine`. JNA, `androidx.lifecycle-process` and the Android `appContext`
all belong to `:session`.

api-exposes `:state-machine`, because `PlaybackStatus` and `PlaybackEvent` implement its
`State` / `Event` interfaces.

---

## The contract

```kotlin
interface MediaPlayer<S : MediaSource, T : PlaybackState> {
    val state: StateFlow<T>
    val events: SharedFlow<PlaybackEvent>

    fun load(source: S)
    fun play()
    fun pause()
    fun stop()
    fun release()
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float)
    fun setVolume(volume: Float)

    fun onAction(action: PlaybackAction)   // has a default body
}
```

`onAction` is the MVI input half: the UI never calls `play()`/`seekTo()` directly, it emits a
`PlaybackAction` that ends here. That buys **one** interception point for analytics, logging,
remote transports or policy ("no seeking past the ad break") instead of one per call site.

Its default body is implemented in terms of the methods above rather than beside them, so a
decorator that overrides only `play()` is still honoured by actions. **A decorator built with
interface delegation (`by player`) must override `onAction`**, or Kotlin's generated forwarder
hands the action straight to the wrapped player and skips the decoration — `KMediaManager` does
exactly this override, and the audio session arbitration depends on it.

### Vocabulary

| Type | Direction | Notes |
|---|---|---|
| `PlaybackAction` | caller → player | `Load`, `Play`, `Pause`, `Stop`, `Release`, `SeekTo`, `SetPlaybackSpeed`, `SetVolume` |
| `PlaybackEvent` | native player → state machine | `LoadRequested`, `Ready`, `PlaybackStarted`, `BufferingStarted`, `PositionSynced`, `Failure`, … You never construct these. |
| `PlaybackFeedback` | player → caller, one-shot | `Accepted`, `Rejected(reason)`, `Failed(message)`. Not on the interface; a backend that needs it exposes its own flow. |
| `PlaybackState` | player → UI, continuous | the single source of truth |
| `PlaybackStatus` | | `Idle`, `Buffering`, `Ready`, `Playing`, `Paused`, `Stopped`, `Completed`, `Error`, `Released` — each one is a `:state-machine` `State` |
| `MediaSource` | | `Url`, `FilePath`, `AndroidUriString`, `Custom` |

### Read-side helpers

In `kplayer.state.Utils`, deliberately not members of `PlaybackState` so `:core` stays free of
presentation concerns:

```kotlin
state.isPlaying      player.isPlaying     // same question, asked of a player
state.isBuffering
state.hasError
state.isSeekable     // durationMs > 0
state.progress            // 0f..1f
state.bufferedProgress    // 0f..1f
```

## `kplayer.player` — the shared backend

Every backend in the library is built from these, so nothing engine-free is written twice. See
[ADR 0001](../docs/adr/0001-sharing-player-logic-between-audio-and-video.md) for why they live here.

| Type | Role |
|---|---|
| `PlayerState<Self>` (in `kplayer.state`) | a `PlaybackState` the shared machine can `copyBase()` |
| `PlaybackStateMachine<S>` | the status graph — one node per `PlaybackStatus` |
| `AbstractMediaPlayer<S>` | machine + feedback flow + the `MediaPlayer` calls → `PlaybackAction` |
| `MediaEngine` + `AbstractMediaEngine` | the seam a native player implements, and the `events` flow it reports through |
| `EngineMediaPlayer<S>` | a complete player given only a `MediaEngine` |
| `PlaybackError` (in `kplayer.state`) | what failed, described — the cause behind `PlaybackStatus.Error` |
| `PlaybackRetryPolicy` | whether a failed `PlaybackAction` runs again |
| `MediaSource.toAndroidUri()` / `toIosUrl()` | source → native URI, platform SDK only |

`PlayerState<Self>` exists because `PlaybackStateMachine`'s core operation is "this state, with a new
status" — `copy()` on a data class, which is not polymorphic. `Self` is the implementing type, so
`copyBase` returns the caller's own state type:

```kotlin
data class AudioPlayerState(/* flat fields */) : PlayerState<AudioPlayerState> {
    override fun copyBase(/* … */) = copy(/* … */)
}
```

It is deliberately **separate** from `PlaybackState`: `MediaPlayer<S, T : PlaybackState>`,
`KMediaManager` and `KMediaManagerBuilder` keep their existing generics, and read-only consumers —
the whole `:ui` module — never see it.

Two hooks let a medium differ without a shared class full of nulls. `reduceCustom` applies
status-neutral events the shared vocabulary does not model, and `onLoad` adjusts the state built when
a new source loads. Video uses both for subtitle cues; audio passes neither.

`MediaEngine` implementations follow two rules: translate native quirks inside the engine rather than
upstream, and never report state you were merely told to enter — wait for the native callback. Facts
travel up one way only, as `MediaEngine.events`; `AbstractMediaEngine` owns that flow and gives an
engine the `report…` calls that fill it, none of which suspend or care which thread they are on.
`EngineMediaPlayer` is its only subscriber and subscribes in its constructor, because the flow does
not replay.

### Failure has one route out

`EngineMediaPlayer.execute()` is the only error-handling boundary. The dispatch `when` says what each
action does and nothing about what happens when it throws, because `runAction` wraps the whole thing,
describes anything thrown through the `errorMapper` and reports it. A `PlaybackEvent.Failure` the
engine puts on its own flow lands in the same `reportFailure`, so a fault reported half a minute later
is handled exactly like one thrown on the spot — and `PlaybackEvent.Failure` reaches the state machine
from one place instead of eight. The difference between the two is only what a retry re-runs: the
action itself for a synchronous throw, a reload for an engine that faulted, since a faulted engine has
thrown its prepared item away.

The two halves stay apart on purpose:

- **`PlaybackError` describes the failure.** `Network`, `Decoder`, `Source`, `Unknown`, each with a
  uniform `message` / `cause` so a caller that only wants something to show never needs a `when`.
  It carries no retry flag — "is this worth another go" depends on the action and the app, not on
  the failure alone.
- **`PlaybackRetryPolicy` decides what happens next.** It is handed the original `PlaybackAction`,
  so retrying is re-executing it; there is no second switch over action kinds to keep in sync. The
  default is `PlaybackRetryPolicy.None` — a silent reload is visible to the user, so it is opted
  into, with `PlaybackRetryPolicy.transient()` or your own.

Extraction is the engine's job, since only it can read `PlaybackException.errorCode` or an `NSError`
domain; classification is `:core`'s. The seam between them is one `expect` function:

```kotlin
expect class NativeError(cause: Throwable?)          // fields are the actual's business
expect fun NativeError.toPlaybackError(): PlaybackError
```

An engine builds a `NativeError` in whatever terms its platform failed — `NativeError.media3(…)`,
`NativeError.avError(item.error)`, `NativeError.gstreamer(code, message)`,
`NativeError.mediaElement(code)`, `NativeError.rejected(text)`, or plain `NativeError(throwable)` —
and maps it with the same call everywhere. `commonMain` therefore names no backend: each `actual`
declares only the fields its own platform can fill in, and the classification table sits beside it
(`androidMain` for media3, `wasmJsMain` for the browser, `jvmMain` for GStreamer, `jvmSharedMain` for
the JDK exceptions Android and desktop share, and `appleSharedMain` — visible only to `iosMain` and
`jvmMain` — for the one `NSError` table both Apple engines classify through). Adding a backend to one
platform changes nothing shared.

Because every table is keyed on primitives rather than on a native exception type, all of them are
testable on a host with no device and no media: `Media3ErrorMappingTest` in `androidHostTest`,
`DesktopErrorMappingTest` in `jvmTest`, and `AppleErrorCodesTest` in `iosTest`, which pins the Apple
literals against the real `platform.*` constants.

An engine with nothing better to say calls the `onError(message: String)` shorthand and gets
`PlaybackError.Unknown`.


## Tests

```bash
./gradlew :core:jvmTest              # the desktop error tables
./gradlew :core:testAndroidHostTest  # the media3 error table
```

Everything here is a pure function over primitives, so all of it runs on a host with no device, no
emulator and no media: `DesktopErrorMappingTest` (`jvmTest`) covers the GStreamer and Apple tables,
`Media3ErrorMappingTest` (`androidHostTest`) covers media3's, and `AppleErrorCodesTest` (`iosTest`)
pins the Apple literals against the real `platform.*` constants.

The player itself is tested where the engines are: `EngineMediaPlayerTest` in `:audio` drives this
module's `EngineMediaPlayer` against a fake engine, and covers both backends because neither has
any logic of its own left.
