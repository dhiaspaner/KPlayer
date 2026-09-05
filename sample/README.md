# `:sample` — the demo app

A Compose Multiplatform app running on **Android and iOS** from one shared UI, showing the same
engine and the same control DSL driving two completely different playback experiences.

---

## Running it

### Android

```bash
./gradlew :sample:installDebug
```

Entry point: `sample/src/androidMain/kotlin/MainActivity.kt`, which calls
`initializeContext(this)` before any player is built — the one piece of Android setup the library
requires.

### iOS

The Xcode project is checked in — just open it:

```bash
open sample/iosApp/iosApp.xcodeproj
```

Build and run the `iosApp` scheme on a simulator or device. A build phase runs
`./gradlew :sample:embedAndSignAppleFrameworkForXcode`, so the Kotlin framework is rebuilt and
linked automatically (set `OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES` to skip it).

Entry points: `sample/src/iosMain/kotlin/MainViewController.kt` on the Kotlin side,
`iosApp/iOSApp.swift` + `ContentView.swift` on the Swift side.

> Earlier revisions generated this project with [xcodegen](https://github.com/yonaskolb/XcodeGen)
> from `project.yml`. That file was removed and the `.xcodeproj` committed instead, so any
> instruction telling you to run `xcodegen generate` is out of date.

## The two screens

### Normal — the workbench

`screen/NormalPlayerScreen.kt`. Conventional 16:9 playback with every knob the library exposes:

- **Bring your own media.** `component/MediaSourcePicker.kt` takes a pasted URL or opens the
  platform's video picker ([FileKit](https://github.com/vinceglb/FileKit)), and both routes end in
  the same `PlaybackAction.Load(MediaSource)`. What a picked file *is* differs per platform — a
  `content://` URI on Android, a temp-copied file URL on iOS, an absolute path on desktop, a
  `blob:` object URL on web — so `source/PickedFile.kt` has one `toMediaSource` actual per target
  and the player never learns a picker was involved.
- **Hoisted chrome.** A row of buttons *outside* the player drives the same
  `PlayerUiStateHolder` the overlay reads — scaling, controls visibility, fullscreen.
- **Live surface reconfiguration.** Toggle `VideoRenderMode.DIRECT`/`TEXTURE`, native vs Compose
  subtitles, and native vs Compose controls, and watch the surface rebuild.
- **Transport as data.** Load / play / pause / stop / ±10s issued as `PlaybackAction` through
  `onAction`, the same pipeline the overlay uses.
- **Raw engine state** printed underneath, so you can watch the state machine move.
- **Policy editor** (`component/PlaybackPolicyAdvancedEditor.kt`) that swaps `InterruptionConfig`
  at runtime through a `MutableStateFlow` — background it, unplug headphones, take a call, and see
  each policy behave differently.

### Reels — the feed

`screen/ReelsScreen.kt`. A `VerticalPager` of short-form clips using `ReelsControlsTemplate`.

The interesting part is **player handover**. A feed cannot give every page its own engine: each
would build an ExoPlayer/AVPlayer, claim the audio session and start decoding, so three pages would
mean three players fighting over one session. Only the *settled* page hosts a player —
`pagerState.settledPage`, not `currentPage`, so an engine is never torn down mid-gesture while the
user can still see it. Swiping away disposes it through `rememberVideoPlayer`'s `DisposableEffect`;
the arriving page builds its own.

A production feed would pre-warm the next page's media instead of starting cold on every swipe,
which needs a player pool the library does not offer yet.

## What to copy from it

| Want to… | Look at |
|---|---|
| set up Android | `MainActivity.kt` — `initializeContext` |
| set up iOS | `MainViewController.kt` and `iosApp/project.yml` |
| drive chrome from outside the player | the top button rows in `NormalPlayerScreen` |
| change interruption policy at runtime | the `MutableStateFlow<InterruptionConfig>` in `NormalPlayerScreen` |
| render Compose subtitles | the `contentOverlay` in `NormalPlayerScreen` |
| build a video feed | `ReelsScreen` |

## Note on the media

Both screens stream the same public clip over HTTPS, so the app needs network access — the sample
manifest declares `INTERNET` and `ACCESS_NETWORK_STATE`. Nothing else in the app is persistent.
