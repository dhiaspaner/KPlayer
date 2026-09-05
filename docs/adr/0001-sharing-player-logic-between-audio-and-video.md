# ADR 0001 — Sharing player logic between `:audio` and `:video`

**Status:** Accepted — steps 1–5 and 7 implemented; step 6 outstanding
**Date:** 2026-08-10

---

## Implementation notes

Landed as described, with all 106 existing tests green (`:core` 53, `:audio` 38, `:video` 15) and
every target compiling — Android, JVM, iOS, plus iOS test compilation.

Now shared in `:core`:

| `kplayer.player` | Lines |
|---|---:|
| `PlaybackStateMachine<S>` | 202 |
| `EngineMediaPlayer<S>` | 202 |
| `MediaEngine` + `Listener` | 101 |
| `AbstractMediaPlayer<S>` | 77 |
| `MediaSource.toAndroidUri()` / `toIosUrl()` | 49 |
| `kplayer.state.PlayerState<Self>` | 42 |

Deleted outright: `VideoPlayerStateMachine.kt` (180), `AudioPlayerStateMachine.kt` (185),
`AbstractAudioPlayer.kt` (71), and four private URI mappings. `:video`'s `commonMain` is down to 138
lines, `:audio`'s to 77.

Three things worth recording that the proposal did not anticipate:

- **Video's hooks needed their own home.** Inlining `reduceCustom` / `onLoad` in
  `AbstractVideoPlayer` would have meant `VideoPlayerStateMachine()` in a test built the *audio*
  configuration. They live in `VideoPlaybackStateMachine.kt` as `VideoCueReducer` / `VideoOnLoad`,
  with an internal `VideoPlayerStateMachine()` factory both the test and the JVM stub use — so a
  test exercises the same machine the real player runs.
- **`toIosUrl` was not identical after all.** `IosVideoPlayer` special-cased `mockfile://` for its
  tests while audio did not. The shared version generalises it to "a `FilePath` containing `://` is
  really a URL", which preserves video's behaviour and fixes audio's silently-broken one.
- **The `println("onEvent: $event")` in `AbstractVideoPlayer` is gone** with the class that held it.

Step 6 — porting `:video` onto `MediaEngine` — is untouched, so `:video`'s KVO observers and its
inline backend logic remain its own.

---

## Context

`:video` and `:audio` are siblings in the module graph. Neither can see the other, and the only
module both can see is `:core`, which is deliberately free of ExoPlayer and AVFoundation. The result
is that every piece of engine-free player logic exists twice.

Measured, today:

| Duplicated pair | Lines | Difference |
|---|---:|---|
| `VideoPlayerStateMachine` / `AudioPlayerStateMachine` | 180 + 185 | 3 subtitle spots, otherwise identical |
| `AbstractVideoPlayer` / `AbstractAudioPlayer` | 57 + 71 | comments only |
| iOS KVO observers (3 classes each) | 74 + 78 | names only |
| `exoPlayerAudioAttributesFor` / `exoAudioAttributesFor` | 35 + 35 | names only |
| `MediaSource.toAndroidUri` / `toIosUrl` | 7 × 4 | none |

That is **~370 lines** of duplication. A further **~190** will appear the moment `:video` gets the
engine seam `:audio` now has (`EngineAudioPlayer`), because that sequencing logic is entirely
engine-free and would be copied wholesale.

The duplication is not laziness — it is forced by one technical fact:

> The state machine's core operation is `prev.copy(status = …)`. `VideoPlayerState` and
> `AudioPlayerState` are two distinct data classes, and `copy()` is not polymorphic. No shared code
> can produce an updated state of an unknown state type.

### Hard constraint

Any solution must keep the **flat data classes**. `:ui` depends on their shape:

- `ui/.../preview/FakeVideoPlayer.kt:78` — `it.copy(status = PlaybackStatus.Playing)`
- `ui/.../preview/PlayerControlsPreviews.kt:78` — `VideoPlayerState(status = …, positionMs = …, activeSubtitle = …)`

A redesign to composition (`VideoPlayerState(snapshot, activeSubtitle)`) breaks both, and every
preview and fake with them.

---

## Options considered

### A — One shared concrete state in `:core`

Delete `VideoPlayerState` and `AudioPlayerState`; both modules use a single concrete `PlayerState`
carrying `activeSubtitle` (always null for audio).

*Simplest possible outcome: no generics anywhere, one machine, one abstract player.*
Cost: audio permanently carries a video field, and the two states can never diverge — no room for
the playlist, gapless or metadata fields an audio player will eventually want.

### B — Additive `PlayerState<Self>` interface, generic shared machine ✅ **chosen**

Add one interface in `:core` that gives the shared machine a polymorphic copy, and generify the
shared classes over it. Both flat data classes survive untouched in shape.

### C — Merge `:video` and `:audio` into one module

Removes the constraint rather than working around it.

Worth noting because the usual argument for splitting them is weaker than it looks:
`media3-exoplayer` ships video decoders regardless of which module depends on it, and AVFoundation
is a system framework — so an audio-only app saves a handful of Kotlin classes, not binary size.
Rejected anyway: it collapses a published module story (`io.github.kotlin:audio` /
`:video`) that consumers would already be depending on, and orthogonally, B is still wanted inside
a merged module to keep the two state types apart.

---

## Decision

**Option B.** Add `PlayerState<Self>` to `:core` and move every engine-free shared class there,
generified over it.

### The one new abstraction

`PlaybackState` itself is **not** touched — that keeps `MediaPlayer<S, T>`, `KMediaManager` and
`KMediaManagerBuilder` generics exactly as they are, and `:ui` compiles unchanged.

```kotlin
// :core — additive
interface PlayerState<Self : PlayerState<Self>> : PlaybackState {
    /**
     * Polymorphic copy of the fields the shared state machine owns.
     * Implementations forward to their data class's own copy().
     */
    fun copyBase(
        status: PlaybackStatus = this.status,
        playWhenReady: Boolean = this.playWhenReady,
        positionMs: Long = this.positionMs,
        durationMs: Long = this.durationMs,
        bufferedPositionMs: Long = this.bufferedPositionMs,
        playbackSpeed: Float = this.playbackSpeed,
        volume: Float = this.volume,
        errorMessage: String? = this.errorMessage,
        source: MediaSource? = this.source,
    ): Self
}
```

Each module writes one forwarding method; the data class keeps its flat shape and its `copy()`:

```kotlin
// :video
data class VideoPlayerState(
    override val status: PlaybackStatus = PlaybackStatus.Idle,
    // … unchanged …
    val activeSubtitle: String? = null,
) : PlayerState<VideoPlayerState> {

    override fun copyBase(
        status: PlaybackStatus, playWhenReady: Boolean, positionMs: Long, durationMs: Long,
        bufferedPositionMs: Long, playbackSpeed: Float, volume: Float,
        errorMessage: String?, source: MediaSource?,
    ) = copy(
        status = status, playWhenReady = playWhenReady, positionMs = positionMs,
        durationMs = durationMs, bufferedPositionMs = bufferedPositionMs,
        playbackSpeed = playbackSpeed, volume = volume,
        errorMessage = errorMessage, source = source,
    )
}
```

Two details that make this work:

- **An override may not restate default values**, so the defaults live once, on the interface.
- **An explicitly passed `null` is distinguishable from an omitted argument**, so
  `copyBase(errorMessage = null)` still clears an error — which is what the `Buffering` node needs
  when a new source loads.

### What moves into `:core`

All of it is engine-free, so none of it violates the "no ExoPlayer/AVPlayer in `:core`" rule:

| New in `:core` | Replaces |
|---|---|
| `PlaybackStateMachine<S : PlayerState<S>>` | both state machines |
| `AbstractMediaPlayer<S : PlayerState<S>>` | both abstract players |
| `MediaEngine` + `MediaEngine.Listener` | `AudioEngine` |
| `EngineMediaPlayer<S>` | `EngineAudioPlayer`, and `:video`'s inline backend logic |
| `MediaSource.toAndroidUri()` / `toIosUrl()` | 4 private copies |
| `KvoObserver` (Foundation only) | 6 KVO observer classes |

`KvoObserver` reports the raw KVO change value and takes the key path as a constructor argument;
the engines map it to `AVPlayerItemStatus`, `rate`, `playbackLikelyToKeepUp`. That is what keeps
AVFoundation out of `:core` while still sharing the cinterop plumbing.

### How module-specific behaviour survives

Two narrow seams, both driven by `:video`'s subtitles — the only genuine behavioural difference:

```kotlin
// :core
class PlaybackStateMachine<S : PlayerState<S>>(
    initialState: S,
    scope: CoroutineScope = …,
    /** Status-neutral, module-specific events (video: SubtitleCueChanged). */
    private val reduceCustom: (S, PlaybackEvent) -> S? = { _, _ -> null },
    /** Applied when a new source starts loading (video: clear activeSubtitle). */
    private val onLoad: (S) -> S = { it },
)
```

and one escape hatch on the engine listener, so an engine can report a fact the shared vocabulary
does not name:

```kotlin
interface MediaEngine {
    interface Listener {
        // … onPlayingChanged, onBufferingChanged, onReady, onCompleted, onError …
        fun onCustomEvent(event: PlaybackEvent)
    }
}
```

### Accepted duplication

The media3 audio-attributes mapping stays duplicated (35 lines each). It needs media3, so it cannot
live in `:core`, and a shared Gradle module for a lookup table costs more than it saves.

---

## Migration order

Each step compiles on its own and keeps the existing suites green (`:core` 53, `:audio` 38,
`:video` 15).

1. Add `PlayerState<Self>` and `copyBase` to both state types. No behaviour change.
2. Generify `AudioPlayerStateMachine` into `:core`'s `PlaybackStateMachine<S>`; point `:audio` at
   it. `:audio`'s 38 tests are the safety net.
3. Point `:video` at it, passing the two lambdas. `:video`'s 15 tests must stay green.
4. Replace both abstract players with `AbstractMediaPlayer<S>`. Drop the stray
   `println("onEvent: $event")` at `AbstractVideoPlayer.kt:22` while doing it — it logs every event
   on every platform.
5. Move `AudioEngine`/`EngineAudioPlayer` into `:core` as `MediaEngine`/`EngineMediaPlayer<S>`.
   `:audio` is left with its state type, two engines and a factory.
6. Port `:video` onto the seam (`ExoVideoEngine`, `AvVideoEngine`) and reuse
   `EngineAudioPlayerTest` as a shared `EngineMediaPlayerTest`. **This is where `:video` finally
   gets backend tests**, which is the real prize.
7. Share the `MediaSource` URI mapping and `KvoObserver`.

Steps 1–4 are mechanical. Step 6 is the substantial one: `:video` also needs surface attachment
(today via the `exoPlayer` / `avPlayer` handles, so `MediaEngine` needs nothing for it) and
subtitle-cue routing, which is the `AVPlayerItemLegibleOutput` either/or described in
`video/README.md`.

## Consequences

**Good**

- ~370 duplicated lines removed; the ~190 pending ones never get written.
- `:video` gains ~17 backend tests for free, closing the gap that `:audio` closed in tier 2.
- One place to fix a bug in event mapping instead of two that silently drift.
- Each module reduces to: a state type, two engines, a factory.

**Costs**

- One recursive generic (`PlayerState<Self>`) that a contributor has to understand.
- A ~12-line `copyBase` per state type, which must be updated when a field is added to
  `PlaybackState`. Forgetting it is a compile error, not a silent bug.
- `:core` grows, and its iosMain gains the KVO cinterop.
