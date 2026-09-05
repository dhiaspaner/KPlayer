// commonMain
package kplayer.core.audio

/**
 * The three axes of an [AudioSessionConfig], kept deliberately independent:
 *
 * ```
 * AudioSessionConfig
 *   ├── mode         what kind of audio is this?
 *   ├── coexistence  how should it interact with other apps' audio?
 *   └── output       where should it come out?
 * ```
 *
 * Each platform maps the axes separately. Nothing here names a native type —
 * `AVAudioSessionCategory`, `AudioFocusRequest` and friends live in the platform
 * source sets, and the mapping in each direction is a small pure function so it
 * can be tested without a device.
 */

/**
 * Describes the *kind* of content being played, so each platform can pick
 * the right native audio session mode / content type.
 *
 * Mode says nothing about how we compete with other apps — that is
 * [AudioCoexistence]. In particular [VoiceCommunication] is not a reason to take
 * transient focus: an ongoing communication session is long-lived and asks for
 * ordinary focus like anything else.
 */
enum class AudioSessionMode {
    /** Music, generic media playback. Default. */
    Music,
    /** Podcasts, audiobooks — optimized for spoken word. */
    Speech,
    /** Video/movie content with a mixed music+dialogue track. */
    Movie,
    /** Live commentary / karaoke-style — needs mic + speaker simultaneously. */
    VoiceCommunication,
}

/**
 * How this session interacts with other apps' audio.
 *
 * Pure audio-session vocabulary; the mapping from an interruption policy to a
 * value lives in the coordinator, keeping `core.audio` free of any dependency on
 * the interruption layer. Modeling the three states as an enum makes the
 * previously-impossible "mix and duck at once" combination unrepresentable.
 *
 * This is the *only* thing that decides audio focus on Android, and the only
 * thing that contributes `MixWithOthers` / `DuckOthers` on iOS.
 */
enum class AudioCoexistence {
    /**
     * Take exclusive audio focus — pause other apps while playing. Default.
     */
    Exclusive,

    /**
     * Coexist with other apps at full volume rather than arbitrating exclusive
     * focus. iOS mixes (`.mixWithOthers`), Android skips the focus request — so
     * the session neither interrupts nor is interrupted by other audio.
     */
    Mix,

    /**
     * Lower other apps' volume while playing rather than pausing them
     * (e.g. for short clips).
     */
    Duck,
}

/**
 * Where the audio should come out — the routing axis, and the smallest thing
 * that can express it.
 *
 * This exists for exactly one reason: [AudioSessionMode.VoiceCommunication] maps
 * to `.playAndRecord` on iOS, and that category defaults to the **receiver** (the
 * earpiece you hold to your head), not the loudspeaker. A media player playing
 * live commentary out of the earpiece is simply broken, and no combination of
 * [AudioSessionMode] and [AudioCoexistence] can say otherwise — which is the
 * test for whether a third axis earns its place.
 *
 * It is intentionally two values. `allowBluetooth` was considered and left out:
 * on iOS it means HFP, whose 8/16 kHz mono is a downgrade for a media player,
 * while the thing you would actually want — `.allowBluetoothA2DP` — is
 * output-only and fights `.playAndRecord`'s input path. A knob whose correct
 * setting we cannot state is not an abstraction, it is a pass-through.
 */
enum class AudioOutputPreference {
    /**
     * Let the platform route as it sees fit. Default, and correct for all three
     * playback-only modes — they already reach the speaker.
     */
    System,

    /**
     * Prefer the built-in loudspeaker over the earpiece.
     *
     * Only meaningful for [AudioSessionMode.VoiceCommunication], the one mode
     * that opens an input; the platforms ignore it otherwise rather than
     * fighting a route that is already correct.
     */
    Speaker,
}

/**
 * @param mode what kind of audio this is.
 * @param coexistence how it should interact with other apps' audio.
 * @param output where it should be routed. Defaults to [AudioOutputPreference.System],
 *   so existing callers are unaffected.
 */
data class AudioSessionConfig(
    val mode: AudioSessionMode,
    val coexistence: AudioCoexistence = AudioCoexistence.Exclusive,
    val output: AudioOutputPreference = AudioOutputPreference.System,
)
