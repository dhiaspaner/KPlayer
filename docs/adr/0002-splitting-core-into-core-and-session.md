# ADR 0002 — Splitting `:core` into `:core` and `:session`

**Status:** Accepted — implemented
**Date:** 2026-08-29

---

## Context

`:core` had grown to 4,560 lines across 67 files and seven top-level packages, and its own README
opened by listing five responsibilities. That is a smell, but a long module is not on its own a
reason to split one; the reason has to come from the dependency graph.

Counting every `import kplayer.*` between packages, across all source sets, produced a graph with
two clusters and **one-way traffic between them**:

| Cluster | Packages | Lines |
|---|---|---:|
| playback | `kplayer.state`, `kplayer.event`, `kplayer.player`, the `MediaPlayer` contract | 2,156 |
| session | `kplayer.core.audio`, `kplayer.interruption`, `kplayer.observers`, `kplayer.engine` | 2,404 |

Every arrow crossing the clusters ran session → playback. Not one file under `state`, `event` or
`player` named the audio session, the policy engine, the observers or `KMediaManager`. There was
exactly one exception, and it was a convenience rather than a design: the `MediaPlayer { }` factory
sat in the same file as the `MediaPlayer` interface and reached up into `engine.dsl` to build a
manager.

Three further findings shaped the cut:

- **`observers` → `interruption` is 25 imports**, by far the heaviest edge in the module. Any cut
  separating those two was unavailable, which ruled out the smaller "extract just the observers"
  variant.
- **Every third-party dependency belonged to the session cluster.** JNA and
  `androidx.lifecycle-process` were used only by `observers`; the `org.w3c.dom` bindings only by the
  web observers and focus controller; `appContext` only by the Android focus controller and hardware
  observer.
- **No module could consume half of `:core`.** `AudioPlayer()` and `VideoPlayer()` both end in
  `MediaPlayer { }`, and `:ui` unwraps `KMediaManager` in four platform files.

That last finding is the argument *against* splitting, and it is a real one: the boundary was
already being respected, so a module split enforces something nothing was violating, and no
consumer's dependency graph gets smaller today.

## Decision

Split anyway, on the cluster line: `:core` keeps playback, a new `:session` takes ownership,
policy, observers and wiring. `:session` api-exposes `:core`; the reverse is impossible.

The deciding arguments over "keep it and add a layering test":

1. **The playback half keeps growing and the session half does not.** ADR 0001 moved the shared
   state machine, abstract player and engine seam into `kplayer.player`; porting `:video` onto the
   seam moves more there still. The session half has been stable.
2. **The cost only rises.** POM coordinates are still template placeholders and nothing consumes the
   library externally, so this is the cheapest the move will ever be.
3. **It is nearly invisible to consumers.** Packages are unchanged — Kotlin lets one package span
   modules — and `:audio` / `:video` already used `api(project(":core"))`, so api-exposing both
   leaves their consumers' imports untouched.

## Consequences

**`:core` now has no third-party dependency at all** beyond coroutines and `:state-machine`. JNA,
`androidx.lifecycle-process` and `kotlinx.browser` moved to `:session` with the code that used them.
`kotlin-coroutines-swing` stays in `:core`, because `EngineMediaPlayer`'s action scope defaults to
`Dispatchers.Main`; it is an `implementation` dependency and still reaches `:session` at runtime,
which is all a dispatcher needs.

**The `MediaPlayer { }` factory moved to `:session` but kept the `kplayer` package**
(`MediaPlayerFactory.kt`), so `import kplayer.MediaPlayer` still finds both the interface and the
function and not one call site changed. Moving it into `kplayer.engine.dsl` was tried first and
broke all eight backend entry points — the package, not the file location, is what call sites see.

**`:session` needs no intermediate source sets**, so unlike `:core` it does not configure `iosMain`
by hand and the default hierarchy template applies untouched. `:core` keeps `jvmSharedMain` and
`appleSharedMain` for the error tables, and with them the manual iOS edges that the template
otherwise skips.

**Tests split with the code**, and both halves stayed runnable on a host: `:core` keeps the error
tables (`DesktopErrorMappingTest`, `Media3ErrorMappingTest`, `AppleErrorCodesTest`), `:session` takes
the whole of the old `commonTest` — `KMediaManager*`, `DefaultPlaybackInterruptionHandlerTest`,
`KeepPlayingThroughDisconnectTest` and the `FakePlayer` / `FakeAudioSession` / `FakeMediaEngine`
fixtures — plus the observer and audio-session tests from `jvmTest`, `iosTest` and `androidHostTest`.

**One known failure travelled with the code, unrelated to the move:**
`KMediaManagerEventsTest › an action goes through audio-session arbitration too` fails because
`KMediaManager` does not override `onAction`, so `by player` forwards past the audio-session check.
It failed in `:core` before the split and fails in `:session` after it.

**What is not enforced.** The split makes the direction a compiler constraint, but nothing prevents
a future package inside `:core` from wanting to reach upward. When that happens the answer is that
the design is wrong, not the boundary: a playback concern that needs the audio session is a session
concern that has been put in the wrong module.

The rejected alternatives — keeping one module behind a layering test, and the shallower cut that
extracts only `observers` + `engine` — are written up with the full evidence in the architecture
note that preceded this ADR.
