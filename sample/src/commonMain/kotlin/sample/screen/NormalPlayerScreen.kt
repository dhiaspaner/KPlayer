package sample.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kplayer.core.audio.AudioSessionMode
import kplayer.core.event.PlaybackAction
import kplayer.interruption.InterruptionConfig
import kplayer.ui.FlexibleVideoPlayer
import kplayer.ui.model.VideoScalingMode
import kplayer.ui.VideoSurfaceConfig
import kplayer.core.state.isPlaying
import kplayer.ui.VideoRenderMode
import kplayer.ui.rememberPlayerUiStateHolder
import kplayer.ui.rememberVideoFrameDiagnostics
import kplayer.ui.rememberVideoPlayer
import kplayer.ui.template.DefaultControlsTemplate
import sample.component.FrameOutputDescription
import sample.component.MediaSourcePicker
import sample.component.PlaybackPolicyAdvancedEditor
import sample.component.TinyButton
import sample.component.VideoPlayerStateDescription
import kotlin.time.Duration.Companion.seconds

/** Only the picker's prefill — the screen has no fixed source of its own. */
private const val SAMPLE_URL =
    "https://www.dropbox.com/scl/fi/n28b6ljo0wn163pubsqtg/5-minutes-relaxing-with-Quran-sourate-ta_ha-Ayate-1080p.mp4?rlkey=s9i59rit112tf5tngska8o74p&st=unrfbp0w&raw=1"

/**
 * Conventional 16:9 playback, plus every knob the library exposes.
 *
 * This is the workbench half of the demo: hoisted UI state driven from outside
 * the player, live surface reconfiguration, transport issued as
 * [PlaybackAction] data, and the raw engine state printed underneath so you can
 * watch the state machine move.
 */
@Composable
fun NormalPlayerScreen(modifier: Modifier = Modifier) {

    val config = remember { MutableStateFlow(InterruptionConfig.MediaPlayerDefault) }
    val configState = config.collectAsState()

    // The engine itself — no controller wrapper. One call builds the :core /
    // :video stack (audio session, interruption policy, state machine) and
    // releases it when this screen leaves the composition, which is exactly what
    // happens when you switch to the Reels tab.
    val player = rememberVideoPlayer(
        interruptionConfig = config,
        audioSessionMode = AudioSessionMode.Movie,
    )

    // The one source of truth for playback. There is no second, flattened copy
    // of this: the control chrome reads exactly the same object the debug panel
    // at the bottom of this screen prints.
    val playbackState by player.state.collectAsState()

    // The other half of the truth, and the one PlaybackState cannot carry: whether
    // pixels are actually reaching the screen in TEXTURE mode. Cheap to hold — it
    // polls the latest frame twice a second and watches one StateFlow.
    val frameDiagnostics = rememberVideoFrameDiagnostics(player)

    // Presentation state, hoisted so the bar below can drive the same chrome
    // FlexibleVideoPlayer draws. Pass nothing and the player creates its own.
    val ui = rememberPlayerUiStateHolder(
        scalingMode = VideoScalingMode.FIT,
        renderMode = VideoRenderMode.TEXTURE
    )

    // Surface-layer knobs, kept as plain state: VideoSurfaceConfig is a value,
    // so flipping either of these just rebuilds it on the next recomposition.
    // Render mode is *not* here — it lives on the holder above, so it survives
    // rotation and can be driven from anywhere that can reach `ui`.
    var nativeSubtitles by remember { mutableStateOf(true) }
    var nativeControls by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current

    var isBlurred by remember {
        mutableStateOf(false)
    }


    val blurModifier = if (isBlurred) Modifier.blur(20.dp) else Modifier
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .then(blurModifier)
            .pointerInput(Unit) {
                detectTapGestures { focusManager.clearFocus() }
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        // ── Source selection ──────────────────────────────────────────────────
        // A pasted URL and a file off the device arrive as the same thing: a
        // MediaSource. Nothing below this line knows which one the user chose.
        MediaSourcePicker(
            onLoad = { source -> player.onAction(PlaybackAction.Load(source)) },
            initialUrl = SAMPLE_URL,
        )

        // ── External chrome control ───────────────────────────────────────────
        // Nothing here is inside the player, yet it drives the player's overlay —
        // that is the whole point of hoisting PlayerUiStateHolder.
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TinyButton(text = "Scale: ${ui.scalingMode}", onClick = { ui.cycleScalingMode() })

            TinyButton(
                text = if (ui.controlsVisible) "Hide chrome" else "Show chrome",
                onClick = { ui.toggleControls() }
            )

            TinyButton(
                text = if (ui.isFullscreen) "Exit FS" else "Fullscreen",
                onClick = { ui.toggleFullscreen() },
            )

            Checkbox(
                checked = isBlurred,
                onCheckedChange = {
                    isBlurred = it
                },
            )
        }

        // ── Surface configuration ─────────────────────────────────────────────
        // Everything here is about the native video view, not the chrome.
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Worth toggling with the blur checkbox on: DIRECT hands frames to
            // the system compositor, which draws them *over* the Compose scene, so
            // the blur cannot reach the video. TEXTURE draws them as ordinary
            // Compose content and the video blurs with everything else.
            TinyButton(
                text = "Render: ${ui.renderMode.name.lowercase()}",
                onClick = { ui.toggleRenderMode() },
            )
            TinyButton(
                text = if (nativeSubtitles) "Subs: native" else "Subs: Compose",
                onClick = { nativeSubtitles = !nativeSubtitles },
            )

            // Flipping this hands the whole transport to Media3 / AVKit: the
            // Compose overlay below stops rendering and the chrome buttons in
            // the row above stop having anything to act on.
            TinyButton(
                text = if (nativeControls) "Controls: native" else "Controls: Compose",
                onClick = { nativeControls = !nativeControls },
            )
        }

        // ── The player ────────────────────────────────────────────────────────
        FlexibleVideoPlayer(
            player = player,
            modifier = Modifier.fillMaxWidth(),
            surfaceConfig = VideoSurfaceConfig(
                targetAspectRatio = 16f / 9f,
                // Seeds the holder on first composition; the holder owns it after
                // that, so the button above is not undone by a recomposition.
                renderMode = ui.renderMode,
                showNativeSubtitles = nativeSubtitles,
                showNativeControls = nativeControls,
            ),
            uiStateHolder = ui,
            // Subtitles are not a player feature — they are just a content
            // overlay, so the player has no `subtitleOverlay` parameter. This
            // draws nothing while nativeSubtitles is true, because then the
            // platform owns rendering and `activeSubtitle` stays null.
            contentOverlay = {
                playbackState.activeSubtitle?.takeIf { it.isNotBlank() }?.let { text ->
                    Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Text(
                            text = text,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(start = 16.dp, end = 16.dp, bottom = 48.dp)
                                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            },
        ) { DefaultControlsTemplate() }

        // ── Transport, driven as data ─────────────────────────────────────────
        // These go through the same onAction pipeline the overlay uses, so an
        // interceptor placed on it would see both.
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TinyButton(
                text = if (playbackState.isPlaying) "Pause" else "Play",
                onClick = {
                    player.onAction(
                        if (playbackState.isPlaying) PlaybackAction.Pause else PlaybackAction.Play
                    )
                },
            )
            TinyButton(
                text = "Stop",
                onClick = { player.onAction(PlaybackAction.Stop) }
            )
        }

        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {

            TinyButton(
                text = "-10s",
                onClick = {
                    player.onAction(
                        PlaybackAction.SeekTo(
                            (playbackState.positionMs - 10.seconds.inWholeMilliseconds)
                                .coerceAtLeast(0)
                        )
                    )
                },
            )

            TinyButton(
                text = "+10s",
                onClick = {
                    player.onAction(
                        PlaybackAction.SeekTo(
                            (playbackState.positionMs + 10.seconds.inWholeMilliseconds)
                                .coerceAtMost(playbackState.durationMs)
                        )
                    )
                }
            )
            TinyButton(text = "Restart", onClick = { player.onAction(PlaybackAction.SeekTo(0)) })
        }

        HorizontalDivider()

        Text(
            text = "Engine state — the chrome above reads this exact object.",
            color = Color.Gray,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        VideoPlayerStateDescription(playbackState)

        // The panel above cannot explain a black TEXTURE surface: a frame that
        // never arrives is not a playback error, so `status` stays Playing and
        // `errorMessage` stays null while nothing is on screen. This one reads the
        // frame path instead — the engine's frameOutputFailure and what the
        // renderer did with the frames it got.
        FrameOutputDescription(frameDiagnostics)

        PlaybackPolicyAdvancedEditor(
            config = configState.value,
            onChange = { config.value = it },
        )
    }
}
