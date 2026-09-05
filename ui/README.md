# `:ui` — Compose Multiplatform player UI

The layer you actually put on screen: a video player composable, a state holder for controls,
and two ready-made control templates — all of it engine-agnostic.

Targets: Android, iOS, Web (wasmJs), JVM (compiles; the surface renders nothing without an engine).
Depends on `:video` via `api`, so depending on `:ui` gives you the whole stack.

```kotlin
implementation(project(":ui"))
```

---

## The one-liner

```kotlin
val player = rememberVideoPlayer(audioSessionMode = AudioSessionMode.Movie)
LaunchedEffect(Unit) { player.load(MediaSource.Url(url)) }

FlexibleVideoPlayer(
    player = player,
    modifier = Modifier.fillMaxWidth(),
    surfaceConfig = VideoSurfaceConfig(targetAspectRatio = 16f / 9f),
)
```

`rememberVideoPlayer` builds the `:core`/`:video` engine and releases it on dispose. It returns
the engine itself — there is no controller wrapper, because everything the UI needs is already on
`MediaPlayer`: `state` to read, `onAction` to command.

> On Android, call `initializeContext(this)` once before any player is built.
> For playback that must survive configuration changes, build the engine in a `ViewModel` with
> `VideoPlayer(...)` instead and pass it to the `player` overload.

## The three layers

`FlexibleVideoPlayer` stacks three slots:

```
┌─────────────────────────────────────┐
│ controlsOverlay   → PlayerState     │  the transport chrome
│ contentOverlay    → PlayerState     │  drawn under the tap layer
│ videoSurface      → the frames      │  the only platform-specific part
└─────────────────────────────────────┘
```

| Slot | Receiver | Typical content |
|---|---|---|
| `videoSurface` | `BoxScope.(VideoSurfaceConfig)` | supplied for you by the `player` overload |
| `contentOverlay` | `PlayerState` | subtitles, watermarks, "casting to…" badges |
| `controlsOverlay` | `PlayerState` | play/pause, seek bar, fullscreen |

Both overlays take the same receiver: one type to learn, and a badge that needs to become tappable
does not have to move between slots. They differ in *where they sit*, not in what they can reach.

Between the content and controls layers sits a full-size tap layer that toggles the controls, so
a subtitle never swallows the gesture. Controls auto-hide after `autoHideDelayMillis` (default 3s)
of uninterrupted playback, and never while paused or scrubbing.

## `PlayerState` — the state holder

Shaped like `LazyListState`: a `@Stable` class you create with `rememberPlayerState`, read
properties from, and call functions on. It holds an immutable `PlaybackState` and forwards
`PlaybackAction`s to a lambda, so it never knows what is decoding.

```kotlin
val state = rememberPlayerState(
    playbackState = uiState.playback,
    onPlaybackAction = viewModel::onPlaybackAction,
)

FlexibleVideoPlayer(state = state)
```

Use this overload when the player lives in a ViewModel, when playback is driven from somewhere the
composable cannot see (a Cast session, a playback service), or when rendering chrome with no engine
at all.

**Read:**

| | |
|---|---|
| `playbackState` | latest engine state (snapshot-backed — reading it subscribes you) |
| `displayPositionMs` | what controls should render: the finger's target while scrubbing, the seek target while one is in flight, the engine's position otherwise. **Always prefer this to `playbackState.positionMs` in a template.** |
| `isScrubbing` | true while the thumb is held |
| `uiState` | the `PlayerUiStateHolder` |

**Act:** `playPause()`, `play()`, `pause()`, `seekTo(ms)`, `seekBy(deltaMs)`, `setVolume`,
`setPlaybackSpeed`, `stop()` — all of which funnel through `dispatch(PlaybackAction)`. Wrapping the
handler you pass to `rememberPlayerState` intercepts every one of them at once.

UI-only commands (`toggleControls`, `cycleScalingMode`, `toggleFullscreen`, …) never reach the
engine; they go straight to the UI state holder.

## The built-in controls

`PlayerControls.kt` holds them as **ordinary composables taking a `state` parameter** — not members
of `PlayerState`, not extensions on it. So they have no access your own controls lack, and
`PlayerState` stays a state holder rather than a state holder that also draws:

`PlayPauseButton`, `TimeSeekBar`, `DurationText`, `ScalingModeButton`, `FullscreenButton`,
`ErrorText` — each `@Composable fun …(state: PlayerState<*>, modifier: Modifier = Modifier, …)`.

A parameter rather than a receiver because a receiver resolves against whatever implicit receiver
is in scope, and inside a `Box { Row { … } }` that is a `BoxScope`/`RowScope`, not the player. With
a parameter the call reads the same everywhere:

```kotlin
@Composable
fun SpeedButton(state: PlayerState<*>, modifier: Modifier = Modifier) {
    val speed = state.playbackState.playbackSpeed
    TextButton(onClick = { state.setPlaybackSpeed(if (speed == 1f) 2f else 1f) }, modifier = modifier) {
        Text("${speed}×")
    }
}
```

Templates stay extensions, because that is the shape of the `controlsOverlay` slot. They capture the
receiver first and pass it down:

```kotlin
@Composable
fun PlayerState<*>.MyTemplate(modifier: Modifier = Modifier) {
    val state = this
    Box(modifier.fillMaxSize()) {
        PlayPauseButton(state, modifier = Modifier.align(Alignment.Center))
        TimeSeekBar(state, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
```

`kplayer.ui.model.formatPlaybackTime(millis)` gives you the same `m:ss` / `h:mm:ss` formatting
anywhere else — a notification, a log line, a Swift consumer.

### Templates

Two complete arrangements, both plain extension functions on `PlayerState<*>`:

```kotlin
FlexibleVideoPlayer(player = player) { DefaultControlsTemplate() }   // conventional media chrome

FlexibleVideoPlayer(player = player) {
    ReelsControlsTemplate(actions = { LikeButton(); CommentButton() })  // short-form vertical video
}
```

Copy either one and rearrange — that is the intended path to custom chrome. Templates open their
own `Box`, because `PlayerState` carries no `BoxScope`.

## `VideoSurfaceConfig` — how frames are drawn

Plain, `null`-free data describing the *native surface*, normally decided once per screen:

| Field | Default | Notes |
|---|---|---|
| `scalingMode` | `FIT` | **Initial value only** — the UI state holder owns it afterwards, so a `ScalingModeButton` tap sticks |
| `targetAspectRatio` | `null` | e.g. `16f/9f`. Applied as a layout constraint, so the player has a size *before* media loads and the layout does not jump |
| `backgroundColor` | `Black` | letterbox bars and the idle/buffering area |
| `keepScreenOn` | `true` | only while playing — a paused player has no claim on the battery |
| `showNativeSubtitles` | `true` | see below |
| `showNativeControls` | `false` | hands the whole transport to Media3 / AVKit |
| `renderMode` | `VideoRenderMode.Default` (`DIRECT`) | **Initial value only** — hoisted into the holder like `scalingMode`, so `setRenderMode` sticks. `SurfaceView` vs `TextureView` on Android; view controller vs drawn frames on iOS; not a choice on desktop |
| `allowsPip` | `true` | **iOS only** — Android PiP is an Activity concern |

### `renderMode`

- **`DIRECT`** — frames go straight to the system compositor. Cheapest, lowest latency, and the
  only mode that keeps a secure DRM path (Widevine L1). The video is *not* part of your drawing
  pass: it ignores alpha/rotation/scale, does not clip to rounded corners, and can z-fight during
  shared-element transitions.
- **`TEXTURE`** — frames go through a texture the UI composites. Behaves like any other Compose
  content (rounded corners, animation, transforms) at the cost of a GPU copy per frame and **no**
  secure DRM path.

Picking wrong shows up as either a thermal/power cost on long playback or a visual bug that looks
like a Compose problem and is not.

### `showNativeControls`

`true` replaces the Compose overlay entirely: neither `controlsOverlay` nor the tap layer renders,
and `controlsVisible` stops driving anything. Two stacked control sets would fight for the same
taps, so it is an either/or. Useful for getting the platform's stock look — including iOS's
AirPlay, PiP and subtitle-track buttons — for free.

### Subtitles

Subtitles get no dedicated parameter; they are just the most common content overlay.

```kotlin
FlexibleVideoPlayer(
    player = player,
    surfaceConfig = VideoSurfaceConfig(showNativeSubtitles = false),
    contentOverlay = {
        playbackState.activeSubtitle?.let { text ->
            Box(Modifier.fillMaxSize()) {
                Text(text, Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp))
            }
        }
    },
)
```

The platforms differ here and it matters: Android decodes the text track either way, so
`activeSubtitle` keeps flowing regardless. On iOS, routing cues to code *replaces* AVFoundation's
rendering — with `showNativeSubtitles = true`, `activeSubtitle` stays `null` even while captions
are visible.

## `PlayerUiStateHolder` — presentation state

Scaling, render mode, controls visibility and fullscreen are decisions the UI makes and the engine
has no opinion about, so they live here rather than in `PlaybackState`. Consequences worth the split:
they survive player recreation, they are Compose-native (a position update four times a second
recomposes nothing here), and they are hoistable.

```kotlin
val ui = rememberPlayerUiStateHolder(scalingMode = VideoScalingMode.CROP)

TopAppBar(actions = {
    IconButton(onClick = { ui.cycleScalingMode() }) { … }
    IconButton(onClick = { ui.toggleRenderMode() }) { … }
    IconButton(onClick = { ui.toggleFullscreen() }) { … }
})
FlexibleVideoPlayer(player = player, uiStateHolder = ui)
```

`renderMode` is on the holder because it is worth changing *while playing*, not only at
composition. The cheap mode is right for ordinary playback; the drawn mode is what a blur, a
rounded corner or a shared-element transition needs — so an app animating its player into
fullscreen switches for the duration and switches back:

```kotlin
LaunchedEffect(ui.isFullscreen) {
    ui.setRenderMode(if (ui.isFullscreen) VideoRenderMode.TEXTURE else VideoRenderMode.DIRECT)
}
```

Switching rebuilds the native surface, which costs a frame or two of black — flip it for a
transition, not per frame.

### Drawing frames

On desktop, and on iOS under `VideoRenderMode.TEXTURE`, the video is drawn by Compose rather than
handed to a native view — `ComposeVideoFrameSurface` (shared by both in `src/skikoMain/`) reads
`VideoFrameSource.latestFrame()`, wraps the BGRA bytes with `Image.makeRaster`, and paints them in a
`Canvas`.

A `Canvas` rather than an `Image` deliberately. `Image(bitmap = …)` takes the frame as a parameter,
so every new frame invalidates composition and re-runs layout; reading it inside the draw lambda
invalidates only the **draw phase**. At 60fps that is the difference between redrawing a bitmap and
rebuilding a subtree. Scaling is applied there too — `FIT` letterboxes, `CROP` covers and clips,
`FILL` stretches — and `ComposeVideoFrameSurfaceDrawTest` renders the composable offscreen through
`ImageComposeScene` and reads the pixels back, so all three are checked against real output rather
than assumed.

`VideoFrame` is constructible, which is what a fake `VideoFrameSource` needs: that is how you render
a player in a `@Preview` or a screenshot test with no decoder behind it.

### When the drawn surface is black

Nothing in the frame path reports through `PlaybackState`: a dropped frame is not a playback error,
so `status` stays `Playing` and `errorMessage` stays null while the surface shows nothing. Every way
this can go wrong therefore looks identical from the outside, which is what
`rememberVideoFrameDiagnostics(player)` exists to separate:

```kotlin
val frames = rememberVideoFrameDiagnostics(player)
frames.outputFailure?.let { Text("no picture: $it") }
```

| Reading | Diagnosis |
|---|---|
| `frameSourceAvailable == false` | this engine composites its own picture — Android, Media Foundation, or the `DIRECT` path |
| `outputEnabled == false` | no surface asked for pixels; the render mode is probably not `TEXTURE` |
| `outputFailure != null` | the decoder side gave up, and says why — `VideoFrameSource.frameOutputFailure`, first-one-wins and cleared per item |
| `decoded == null`, nothing failed | the decoder has produced no frame yet |
| `decoded` advancing, `drawnFrames` stuck | frames arrive and the renderer is not consuming them |
| `renderFailure != null` | frames arrive and Skia refuses to make a bitmap of them |

The sample's **Frame output** card (`FrameOutputDescription`) is this on screen, and opens itself
when either failure is set.

The same story reaches the console under one tag, for a run with no panel in it:

```
$ grep kplayer/frames
kplayer/frames: output enabled by the drawn surface
kplayer/frames: first frame drawn: 1920x1080, stride 7680
kplayer/frames: output disabled — surface left the composition
```

Only edges are logged — a failure appearing, output going on or off, the first frame landing —
never anything per frame. A black surface with no `output enabled` line above it never asked for
pixels; one with `output enabled` and no `first frame drawn` is waiting on the decoder, and the
engine's own `kplayer/frames: output failed:` line says why if it knows.

### Choosing the default

Both modes stay available everywhere; which one you *start* in is configuration, decided in one
place:

```kotlin
VideoRenderMode.Default        // DIRECT — the library-wide starting point
```

`VideoSurfaceConfig`, `PlayerUiStateHolder` and `rememberPlayerUiStateHolder` all defer to it rather
than repeating a literal, so they cannot disagree — which they previously did, the constructor and
the `remember` factory having drifted to opposite values. A test pins that.

Three levels, narrowest first:

| Scope | How |
|---|---|
| One player | `VideoSurfaceConfig(renderMode = VideoRenderMode.TEXTURE)` |
| While playing | `ui.setRenderMode(…)` / `ui.toggleRenderMode()` |
| Whole app | change `VideoRenderMode.Default` |

`DIRECT` is the default because it is right for ordinary playback — cheapest, lowest latency, and
the only mode that can keep a secure DRM path. Moving the library-wide default to `TEXTURE` costs a
copy per frame and gives up DRM for every player that never asked, so prefer the narrower scopes.

`isFullscreen` is a flag the library only *tracks* — hiding system bars and locking orientation is
app policy, so observe it from your screen and react there. The holder is `rememberSaveable`, so it
survives configuration changes and process death.

## Scrubbing

A drag never reaches the engine. Finger down updates local state only; exactly one `SeekTo` goes
out on release, and the target is held until the engine reports a position within 500 ms of it (or
2 s elapse) so the thumb never rubber-bands.

The **rule** is `kplayer.ui.model.SeekInteraction` — an immutable value with no Compose and no
coroutines. `SeekInteractionState` in `kplayer.ui` is only the snapshot-backed container around it,
which is what a SwiftUI or TV consumer would rewrite (a few lines) while reusing the same
behaviour:

```kotlin
var seek = SeekInteraction.Idle
seek = seek.onDrag(positionMs)
val (next, target) = seek.onDragEnd()
seek = next
target?.let(player::seekTo)              // exactly one seek
if (seek.hasCaughtUp(enginePositionMs)) seek = seek.settled()
```

Snapshot state rather than a `StateFlow` is deliberate: the in-flight drag belongs to whichever
toolkit owns the gesture, and no cross-platform consumer wants it — only the committed seek is
meaningful outside this screen.

Hoist the container via `rememberSeekInteractionState()` when a parent needs to read `isScrubbing`,
e.g. to suppress its own gestures mid-drag.

## Previews and tests

```kotlin
@Preview
@Composable
private fun PlayingPreview() {
    val player = rememberFakeVideoPlayer(
        VideoPlayerState(status = PlaybackStatus.Playing, positionMs = 42_000, durationMs = 225_000)
    )
    FlexibleVideoPlayer(player = player, modifier = Modifier.aspectRatio(16f / 9f))
}
```

`FakeVideoPlayer` (in `kplayer.ui.preview`) implements `MediaPlayer` with no engine behind it:
commands mutate its state directly, nothing touches ExoPlayer, an audio session or the network.
`update { }` stages arbitrary state and `advanceBy(millis)` ticks the position without a clock.
It is not a behavioural double — `load` fabricates a duration and buffering never resolves on
its own.

The native surfaces render nothing for a player they can't find a handle in, so previews show the
control overlay over plain black. `PlayerControlsPreviews.kt` has worked examples.

## Platform notes

- **Android** builds the view hierarchy by hand (`AspectRatioFrameLayout` + `SurfaceView`/
  `TextureView` + `SubtitleView` + optional `PlayerControlView`) rather than using Media3's
  `PlayerView`, whose `surface_type` is XML-only and therefore can never switch render modes at
  runtime. It also pauses on `ON_PAUSE` — routed as a `PlaybackAction`, so the state machine and
  interruption engine observe it — and **detaches without releasing** on dispose.
- **iOS** hosts an `AVPlayerViewController` with its own controls switched off.
- **Web** composes the engine's `<video>` element into the Compose scene with `WebElementView`,
  Compose Multiplatform's (experimental) HTML interop. Interop gives layout, position and clipping
  but no compositing: elements land *above* the canvas with nothing drawn to make room for them, so
  the surface builds the hole itself — a negative z-index puts the element behind the canvas and a
  `BlendMode.Clear` rect erases the canvas across the player's bounds. Overlays, the tap layer and
  scrolling then behave as they do everywhere else. `showNativeControls` inverts it and leaves the
  element on top so the browser's transport stays clickable. `renderMode` and `allowsPip` are
  no-ops.
- **`keepScreenOn` is Compose's, not this module's** (`KeepScreenOn.kt`). Every surface applies
  `modifier.then(keepScreenOnModifier(player, config))`, and all that helper adds is the gate —
  requested *and* `PlaybackStatus.Playing`, collected as its own `Boolean` flow so the modifier is
  not rebuilt on every position sync. The holding is `androidx.compose.ui.keepScreenOn()`:
  `View.keepScreenOn` on Android, `UIApplication.idleTimerDisabled` on iOS, each ref-counted across
  every node that asks, so nested players and anything else in the app compose rather than fight.
  **On web and desktop it does nothing** — Compose's owner has no screen-on hook on either — and
  this module deliberately adds no wake lock of its own to cover for that; a browser's own
  keep-the-screen-awake-for-`<video>` behaviour is the fallback.

## Files worth reading

| File | Why |
|---|---|
| `FlexibleVideoPlayer.kt` | the entry point and both overloads |
| `PlayerState.kt` | the state holder — state and commands, no drawing |
| `PlayerControls.kt` | the built-in controls, and the pattern for your own |
| `VideoSurfaceConfig.kt` | the full trade-off documentation for render modes |
| `KeepScreenOn.kt` | the shared gate, and what each platform does with it |
| `template/DefaultControlsTemplate.kt` | the reference for writing your own chrome |
| `preview/FakeVideoPlayer.kt` | rendering the UI with no engine |
| `model/` | the toolkit-neutral rules — no Compose, no coroutines, no platform |

## `kplayer.ui.model` — toolkit-neutral UI rules

Presentation *logic* that is a rule, not a container. Plain Kotlin: no Compose, no coroutines, no
platform. It sits in its own package so a Compose player, a SwiftUI player and a test share the
behaviour and each write their own ~60-line state holder over it, instead of reimplementing the
behaviour three times and letting it drift.

| | |
|---|---|
| `SeekInteraction` | the scrub rule: drag -> exactly one commit on release -> hold the target until the engine catches up |
| `SeekCommit` | what `onDragEnd()` returns: the next state, and the one seek to issue (or `null`) |
| `SeekInteractionDefaults` | `SETTLE_TOLERANCE_MS` (500) and `SETTLE_TIMEOUT_MS` (2000) — the numbers every container should agree on |
| `formatPlaybackTime(millis)` | `m:ss`, or `h:mm:ss` past an hour; `0:00` for unknown/negative |
| `VideoScalingMode` | `FIT` / `CROP` / `FILL`, plus `next()` for a cycle button |

Enforcing `SETTLE_TIMEOUT_MS` needs a clock, so that stays in the container (`PlayerControls` uses a
`LaunchedEffect`); the constant lives here so everyone uses the same deadline.

Two boundaries worth stating, because they are the reason this package is small:

- **The in-flight drag is never published across a boundary.** It is a per-frame gesture owned by
  whichever toolkit handles the touch; only the committed seek is meaningful to anyone else.
- **`:core` holds none of this.** Chrome is presentation, so it belongs above the engine, not
  beside it; a Swift consumer that wants neither already has `state: StateFlow<VideoPlayerState>` +
  `PlaybackAction` as its Compose-free contract from `:core`.

## What is *not* here

Nothing engine-side. `:ui` renders and gathers gestures; playback state, the audio session and the
interruption policy all stay in `:core`, reached through `MediaPlayer` / `PlaybackAction`.

Within this module the split is between the two packages above: `kplayer.ui.model` holds the rules,
Compose-free on purpose, and `kplayer.ui` holds the snapshot-backed containers — `PlayerState`,
`PlayerUiStateHolder`, `SeekInteractionState` — because those are what Compose, and only Compose,
needs.
