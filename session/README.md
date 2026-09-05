# `:session` — audio session ownership, interruption policy and the wiring

Everything above "what a player is" and below "how one decodes": who is allowed to play audio
right now, what should happen when that changes, and the `KMediaManager` that puts the answer in
front of a backend.

Depends on [`:core`](../core/README.md) and api-exposes it, so a consumer naming this module gets
the player contract too. The dependency runs one way only — `:core` cannot see this module — which
is what keeps the playback stack free of policy.

**No engines here either.** This module decorates a `MediaPlayer`; it never implements one.

Everything platform-specific in the library that is not an engine lives here: the Android
`appContext`, `ProcessLifecycleOwner`, the becoming-noisy receiver, `AVAudioSession`, and the
CoreAudio route observer on desktop — with JNA, `androidx.lifecycle-process` and the
`org.w3c.dom` bindings to match.

---

## Audio session ownership — `kplayer.core.audio`

One question — *are we allowed to play audio right now?* — and a report when the answer changes.
It makes **no playback decisions**; that is the interruption engine's job, further down this file.

```kotlin
interface AudioSession {
    val interruptions: Flow<AudioInterruption>

    /** Call immediately before starting or resuming playback. */
    fun acquire(config: AudioSessionConfig): Boolean

    /** Re-acquire after an interruption, reusing the last acquire config. */
    fun reacquire(): Boolean

    fun release()
}

expect fun createAudioSession(): AudioSession
```

`acquire` must be called at **actual playback start/stop**, not at player init/teardown. Returning
`false` means another app holds ownership and the caller should stay paused — `KMediaManager`
honours that by not starting playback at all.

It lives below the engines rather than beside one because `:video` and `:audio` are siblings that
cannot see each other, and focus arbitration has to behave identically for both. That is also why
it is not in `:core`: nothing about *what a player is* depends on who owns the speakers.

### Configuration

```kotlin
AudioSessionConfig(
    mode = AudioSessionMode.Movie,                  // what kind of audio is this?
    coexistence = AudioCoexistence.Exclusive,       // how should it treat other apps' audio?
    output = AudioOutputPreference.System,          // where should it come out?
)
```

Three independent axes, mapped independently by each platform. Nothing crosses over: mode never
decides focus, coexistence never decides a category. Each mapping is a pure function returning
plain data (`AndroidFocusPlan`, `AvAudioSessionSettings`), which is what makes them testable
without a device — see `AndroidFocusMappingTest` and `AvAudioSessionSettingsTest`.

`AudioSessionMode` describes the *kind* of content, so each platform can pick the right native
category/mode/content type:

| Mode | Android usage / content type | iOS category / mode |
|---|---|---|
| `Music` | `USAGE_MEDIA` / `CONTENT_TYPE_MUSIC` | `Playback` / `Default` |
| `Speech` | `USAGE_MEDIA` / `CONTENT_TYPE_SPEECH` | `Playback` / `SpokenAudio` |
| `Movie` | `USAGE_MEDIA` / `CONTENT_TYPE_MOVIE` | `Playback` / `MoviePlayback` |
| `VoiceCommunication` | `USAGE_VOICE_COMMUNICATION` / `CONTENT_TYPE_SPEECH` | `PlayAndRecord` / `VoiceChat` |

`AudioCoexistence` says how this session treats other apps' audio. Modelling it as an enum makes
the previously-impossible "mix and duck at once" combination unrepresentable:

| Value | Android | iOS |
|---|---|---|
| `Exclusive` (default) | `AUDIOFOCUS_GAIN` — pauses other apps | plain `Playback` |
| `Mix` | no focus request at all | `.mixWithOthers` |
| `Duck` | `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` | `.duckOthers` |

Coexistence is the **only** input to the focus decision on Android. `VoiceCommunication` used to
short-circuit it to `AUDIOFOCUS_GAIN_TRANSIENT`, which was wrong twice: an ongoing communication
session is not transient, and because the mode was checked first, `VoiceCommunication` + `Duck`
never reached the duck branch at all.

The mapping from `AudioFocusPolicy` to a value lives in `engine/dsl/toAudioSessionConfig.kt`, so
`kplayer.core.audio` itself stays free of any knowledge that policies exist.

`AudioOutputPreference` is the routing axis, and exists for exactly one reason: `VoiceCommunication`
maps to `.playAndRecord` on iOS, which defaults to the **receiver** (earpiece) rather than the
loudspeaker. No combination of the other two axes can say "play this out of the speaker", which is
the test for whether a third axis earns its place.

| Value | Android | iOS |
|---|---|---|
| `System` (default) | — | no routing option |
| `Speaker` | — | `.defaultToSpeaker`, and only with `.playAndRecord` |

Android ignores it: playback already reaches the loudspeaker, and the only way to force it is
`AudioManager.setCommunicationDevice`, which mutates device-wide routing outliving this session.
iOS drops it for the playback-only modes, because `setCategory` fails outright — leaving the session
unconfigured — when `.defaultToSpeaker` accompanies anything but `.playAndRecord`.

`allowBluetooth` was considered and deliberately left out: on iOS it means HFP, whose 8/16 kHz mono
is a downgrade for a media player, while `.allowBluetoothA2DP` — the thing you would actually want —
is output-only and fights `.playAndRecord`'s input path. A knob whose correct setting cannot be
stated is a pass-through, not an abstraction.

### Interruptions

Both platforms map their native concepts into one shape, so no "audio focus" terminology leaks
out of `kplayer.core.audio`:

```kotlin
sealed interface AudioInterruption {
    data object Began                                      // lost the ability to play
    data class  Ended(val systemAllowsResume: Boolean)     // OS no longer requires it
    data object DuckBegan                                  // lower volume, do not pause
    data object DuckEnded
}
```

`systemAllowsResume = true` means only that the platform considers playback permissible again — it
does **not** imply playback should resume. That decision belongs to the policy engine.

### Android — `AndroidAudioSession`

- `AudioFocusRequest` (API 26+) or the deprecated `requestAudioFocus` below that.
- Focus gain type derives from `coexistence` alone (`focusPlanFor`): `GAIN_TRANSIENT_MAY_DUCK` for
  `Duck`, `GAIN` otherwise. `Mix` holds no request at all, so it neither interrupts nor is
  interrupted. The mode is not consulted.
- `setWillPauseWhenDucked(false)`, always, and *not* derived from `AudioCoexistence` — the two point
  in opposite directions. `AudioCoexistence` says how we treat other apps; this says how we react to
  being ducked, and `true` means "don't duck me, send `LOSS_TRANSIENT` and I'll pause". It used to
  be `coexistence != Duck`, so the default `Exclusive` config asked never to be told about ducking:
  `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` stopped arriving, the whole `DuckBegan`/`DuckEnded` machine
  was dead on API 26+, and `DuckPolicy.LowerVolume` — the library default — silently became a pause.
- One subtlety worth knowing: `AUDIOFOCUS_GAIN` ends both a pause *and* a duck, so the session
  tracks which one is in progress to emit the matching "ended" event — and if a full loss arrives
  mid-duck it emits `DuckEnded` first, so volume is restored before the pause.
- Headphone disconnect is **not** handled here; it arrives as `ACTION_AUDIO_BECOMING_NOISY` in the
  hardware observer.

### iOS — `IosAudioSession`

- Configures and activates `AVAudioSession.sharedInstance()` on `acquire`, deactivating with
  `NotifyOthersOnDeactivation` on `release`.
- Observes `AVAudioSessionInterruptionNotification`, mapping `.shouldResume` to
  `systemAllowsResume`. `Ended` is emitted **unconditionally**, including when reactivation fails —
  it is the only thing that clears `AudioFocusLoss` from the handler's active set, and withholding
  it left the cause active with nothing able to end it, blocking every later auto-resume. A failed
  reactivation is reported as `systemAllowsResume = false`, which is exactly what it means.
- **Reactivation and the interruption lifecycle are separate concepts.** `reacquire()` re-applies
  the last config and activates; it emits nothing. `UIApplicationDidBecomeActive` used to emit
  `Ended(systemAllowsResume = true)` on every activation, so returning to the app cleared a focus
  loss that had never ended — a player paused by a phone call could resume over the top of the call
  as soon as the user glanced at the app. Foregrounding now recovers only when we are *actually*
  interrupted (a `Began` was seen and no `Ended` followed), which is the concrete case the fallback
  was written for: Siri can leave the session deactivated without ever posting an `Ended`.
- Headphone disconnect is **not** handled here, the same as on Android: it arrives as an
  `AVAudioSessionRouteChangeNotification` in the hardware observer. Everything this session emits
  reaches the handler as `AudioFocusLoss`, so reporting a route change here judged an unplug
  against `audioFocusPolicy` — overruling a `headphonesPolicy` of `Ignore` — and the invented loss
  had no `Ended` to clear it from the active set.
- `AVAudioSession` is a process-wide singleton, so this session's category/mode governs every
  player in the app — which is why `IosVideoPlayer` and `IosAudioPlayer` need no per-instance
  audio wiring.

### JVM

`DesktopAudioSession` always grants and never interrupts. That is the *correct* implementation
rather than a stub: Windows, macOS and Linux all let every process play and mix simultaneously, so
there is no ownership to arbitrate and `AudioCoexistence` has nothing to map onto. Tests inject
`FakeAudioSession` (in `commonTest`) when they need to drive interruptions.

The **observers**, however, are real on desktop, and they are where the platform differences live.

`DesktopLifecycleObserver` reads app lifecycle from **AWT**, not from any OS API. "Backgrounded"
means something different on a desktop — the process keeps running and keeps its audio — so the
honest equivalent is *no window of this app is active*, which `WINDOW_ACTIVATED` /
`WINDOW_DEACTIVATED` report on every desktop OS with no native code. It debounces by 200 ms, and
that is not a nicety: moving between two of the app's own windows deactivates one and activates the
other with no ordering guarantee, so the naive reading is a background immediately followed by a
foreground, and every such pair would push an interruption through the policy engine.

`MacHardwareObserver` detects headphone changes through **CoreAudio**. Two properties, because
neither covers the other: `kAudioHardwarePropertyDefaultOutputDevice` catches a switch to AirPods,
USB or HDMI, while `kAudioDevicePropertyDataSource` catches the 3.5mm jack — where the device never
changes and the transport stays `'bltn'`, so a device-only implementation misses it entirely. The
classification is a pure function, `outputRouteInterruption`, mirroring iOS's
`routeChangeInterruption`: only external→built-in and built-in→external mean anything, and swapping
one pair of headphones for another is deliberately silent.

CoreAudio rather than `NSNotificationCenter`, and that choice is forced. JNA can neither define an
Objective-C class nor synthesise a block, so the notification API is unreachable — but CoreAudio's
listener takes an ordinary C function pointer, which is exactly what a JNA `Callback` compiles to.
The callback must be held in a field: JNA frees the native trampoline once the Kotlin object is
unreachable, and CoreAudio calling a freed trampoline takes the process down.

Windows and Linux get a no-op hardware observer — `IMMNotificationClient` and PulseAudio/PipeWire
sink events are not written — so a player there simply never sees `HeadphonesDisconnected`.

## Assembling a player

`MediaPlayer { }` is the builder. Most callers never touch it — `:video`'s `VideoPlayer()` and
`:audio`'s `AudioPlayer()` call it for you — but it is the seam for a custom backend, a custom
audio session, or custom observers:

```kotlin
val player: MediaPlayer<MediaSource, VideoPlayerState> = MediaPlayer {
    player { AndroidVideoPlayer(appContext) }            // required
    interruptionConfig(configStateFlow)                  // required
    mode(AudioSessionMode.Movie)                         // required, no default
    audioSession { createAudioSession() }                // optional override
    observers { handler -> DefaultObservers(handler) }   // optional override
}
```

`build()` returns a `KMediaManager` decorating your backend:

```
KMediaManager (by player)
├── AudioSessionCoordinator     — acquires/releases ownership, forwards interruptions
├── PlaybackInterruptionHandler — decides pause/resume from policy
├── observers                   — lifecycle + hardware, started at construction
└── the backend you supplied
```

What the decorator actually changes:

| Call | Behaviour |
|---|---|
| `load` / `play` | acquire audio ownership first; **if denied, nothing happens** |
| `pause` | no release — pause is transient, so resume needn't re-arbitrate |
| `stop` | releases ownership; the interruption subscription stays alive |
| `release` | releases ownership, stops observers, releases the backend, cancels the scope |

## The interruption engine

This is the part of the library that is hard to get right by hand, so it is worth reading.

### Sources

Anything that can interrupt playback reports through one shape — `InterruptionEvent.Began(cause)`
/ `Ended(cause, systemAllowsResume)` — regardless of where it came from:

| Cause | Android | iOS |
|---|---|---|
| `AudioFocusLoss` | `AudioManager` focus change | `AVAudioSession` interruption notification |
| `AppBackgrounded` | `ProcessLifecycleOwner` | `UIApplication` lifecycle notifications |
| `HeadphonesDisconnected` | `ACTION_AUDIO_BECOMING_NOISY` broadcast | route change, `OldDeviceUnavailable` |

Audio interruptions arrive via `AudioSession.interruptions` (the single source of truth on both
platforms, since that is also what owns session configuration). Lifecycle and hardware arrive via
`DefaultObservers`. Adding a source is one `data object` in `InterruptionCause`, one branch in
`responseFor`, and one observer — the decision engine never changes.

### Policies

```kotlin
InterruptionConfig(
    backgroundPolicy = BackgroundPolicy.PauseAndRestore,
    audioFocusPolicy = AudioFocusPolicy.RestoreIfPlayingBefore,
    headphonesPolicy = HeadphonesPolicy.PauseAndRequireManualResume,
    duckPolicy       = DuckPolicy.LowerVolume(level = 0.2f),
)
```

| Policy | Options |
|---|---|
| `BackgroundPolicy` | `KeepState` · `PauseAndRestore` · `PauseAndStayPaused` |
| `AudioFocusPolicy` | `Ignore` · `RestoreIfPlayingBefore` · `AlwaysResume` · `PauseAndStayPaused` |
| `HeadphonesPolicy` | `Ignore` · `PauseAndRequireManualResume` · `PauseAndRestoreOnReconnect` · `ContinuePlayback` |
| `DuckPolicy` | `Ignore` · `LowerVolume(level)` |

Presets: `MediaPlayerDefault`, `VideoLesson`, `AutoPlay`, `StrictManualResume`, `Uninterruptible`.

Pass a `MutableStateFlow<InterruptionConfig>` and change it at any time — every decision reads
`config.value` at the moment it is made.

### The decision rules

`DefaultPlaybackInterruptionHandler` is two rules:

**Began** — if the cause's policy pauses, record it in the active set and pause.
The *first* interruption of a chain also captures `wasPlayingBeforeChain`.

**Ended** — drop it from the active set, then auto-resume only if **all** of:

1. the active set is now empty (nothing else still holds us paused),
2. the player was playing when the chain started,
3. the strictest policy seen across the chain permits it, and
4. `audioSession.reacquire()` succeeds.

That "resume only when the active set empties" rule is what makes stacked interruptions correct: a
phone call that ends while the app is still backgrounded does **not** resume playback, because
backgrounding is still active. Each policy maps to a `ResumePolicy` (`Always` <
`WhenSystemAllows` < `Never`), and the chain takes `maxOf(...)` — the strictest wins.

Ducking is separate: it lowers volume and restores it, never touches the active set, and never
pauses.

**One exception to "two rules", for one cause.** `Ignore` and `ContinuePlayback` are answered by
*not pausing*, which is the whole story wherever the library is the only thing that would have
paused — Android's becoming-noisy broadcast and the web's route change are pure notifications. iOS
is not like that: `AVPlayer` stops itself when the output device it was playing to disappears, so
the policy's decision is overruled a moment later by AVFoundation, with no library call involved.
So a non-pausing `HeadphonesPolicy` arms a short watch (500ms) for exactly that pause and undoes
it. It is limited to `HeadphonesDisconnected` on purpose: a pause under `AudioFocusLoss` is another
app taking the output, and one under `AppBackgrounded` is the OS enforcing that the app may not
play in the background — neither is a decision any policy may overrule. The watch needs a scope,
which `KMediaManagerBuilder` supplies; construct the handler without one and it stays entirely
synchronous.

`InterruptionManager.active` is a public `StateFlow<Set<InterruptionCause>>`, so UI can show *why*
playback is paused ("Paused — phone call").

## Android specifics

- `appContext` lives here (`kplayer.AndroidContext`) because this is the lowest module that needs
  it: audio focus and the becoming-noisy receiver are both in this module, and the ExoPlayer
  instances in `:video` / `:audio` sit above it. Assignment is private to the file, so it can only
  be set through `initializeContext(...)` — call that once at startup, and it stores the
  *application* context. `:core` does not need it and no longer carries it.
- The lifecycle observer uses `ProcessLifecycleOwner`, i.e. the *whole app* going to background,
  not a single Activity.

## Tests

```bash
./gradlew :session:jvmTest
./gradlew :session:jvmTest --tests "kplayer.DefaultPlaybackInterruptionHandlerTest"
./gradlew :session:testAndroidHostTest   # the Android focus mapping
```

`FakePlayer` and `FakeAudioSession` in `commonTest` make the policy engine testable with no
platform at all — which is the point of keeping the engine free of native types.
`KMediaManagerAudioFocusTest` covers the acquire/release contract.
