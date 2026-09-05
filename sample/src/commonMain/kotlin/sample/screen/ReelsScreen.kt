package sample.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kplayer.core.audio.AudioSessionMode
import kplayer.core.event.PlaybackAction
import kplayer.core.state.MediaSource
import kplayer.ui.FlexibleVideoPlayer
import kplayer.ui.model.VideoScalingMode
import kplayer.ui.VideoRenderMode
import kplayer.ui.VideoSurfaceConfig
import kplayer.ui.rememberVideoPlayer
import kplayer.ui.template.ReelsControlsTemplate

/** One item in the feed. */
private data class Reel(
    val url: String,
    val author: String,
    val caption: String,
)

// The same clip three times: the point of this screen is the *feed* mechanics —
// paging, player handover, chrome — not the content.
private const val CLIP_URL =
    "https://www.dropbox.com/scl/fi/n28b6ljo0wn163pubsqtg/5-minutes-relaxing-with-Quran-sourate-ta_ha-Ayate-1080p.mp4?rlkey=s9i59rit112tf5tngska8o74p&st=unrfbp0w&raw=1"

/**
 * A swipeable short-form feed — the context `ReelsControlsTemplate` is designed
 * for, as opposed to the fixed 16:9 frame on the other tab.
 *
 * The interesting part is the player handover. A feed cannot give every page its
 * own engine: each one would build an ExoPlayer/AVPlayer, claim the audio
 * session and start decoding, so three pages would mean three players fighting
 * over one session and the wrong clip winning. Instead only the **settled** page
 * hosts a player; swiping away disposes it via `rememberVideoPlayer`'s
 * `DisposableEffect` and the arriving page builds its own.
 *
 * A production feed would go further and pre-warm the next page's media rather
 * than starting cold on every swipe, but that needs a player pool the library
 * does not offer yet.
 */
@Composable
fun ReelsScreen(modifier: Modifier = Modifier) {
    val reels = remember {
        listOf(
            Reel(CLIP_URL, "@kplayer", "Same engine, completely different chrome."),
            Reel(CLIP_URL, "@compose", "The rail on the right is the template's `actions` slot."),
            Reel(CLIP_URL, "@kmp", "Only the settled page owns a player."),
        )
    }

    val pagerState = rememberPagerState { reels.size }

    VerticalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize().background(Color.Black),
    ) { page ->
        val reel = reels[page]
        // settledPage, not currentPage: during a drag both pages are on screen,
        // and swapping the engine mid-gesture would tear down a player the user
        // can still see.
        if (page == pagerState.settledPage) {
            ReelPage(reel)
        } else {
            ReelPlaceholder(reel)
        }
    }
}

/** The page that currently owns an engine. */
@Composable
private fun ReelPage(reel: Reel) {
    val player = rememberVideoPlayer(audioSessionMode = AudioSessionMode.Movie)

    // Keyed on the URL rather than Unit: were the same page ever to change its
    // clip, this reloads instead of silently keeping the old one.
    LaunchedEffect(player, reel.url) {
        player.onAction(PlaybackAction.Load(MediaSource.Url(reel.url)))
    }

    Box(Modifier.fillMaxSize()) {
        FlexibleVideoPlayer(
            player = player,
            modifier = Modifier.fillMaxSize(),
            surfaceConfig = VideoSurfaceConfig(
                // Fill the page and clip the sides — a portrait feed never
                // letterboxes, and no targetAspectRatio because the page itself
                // is the frame.
                scalingMode = VideoScalingMode.CROP,
                // The feed scrolls; PiP here would fight the gesture.
                allowsPip = false,
                renderMode = VideoRenderMode.TEXTURE
            ),
            // Short-form chrome is meant to stay put, not fade after 3s.
            autoHideControls = false,
        ) {
            ReelsControlsTemplate(actions = { ReelActionRail() })
        }

        ReelCaption(
            reel = reel,
            modifier = Modifier
                .align(Alignment.BottomStart)
                // Above the template's own elapsed-time label and hairline
                // seek bar, which sit at the very bottom.
                .padding(start = 16.dp, end = 88.dp, bottom = 44.dp),
        )
    }
}

/**
 * What an off-screen page shows: no engine, no decoding, no audio session.
 *
 * Kept visually similar to a loading reel so paging does not flash black.
 */
@Composable
private fun ReelPlaceholder(reel: Reel) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF121218)),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = Color.White.copy(alpha = 0.4f))
        ReelCaption(
            reel = reel,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, end = 88.dp, bottom = 44.dp),
        )
    }
}

@Composable
private fun ReelCaption(reel: Reel, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = reel.author,
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = reel.caption,
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The app-specific half of the right-hand rail.
 *
 * `ReelsControlsTemplate` stacks these above its own play/pause, scaling and
 * fullscreen buttons — the player has no opinion about likes or shares, which is
 * why they are a slot rather than a fixed set. Inert here; a real feed would
 * wire them to its own state.
 */
@Composable
private fun ReelActionRail() {
    ReelActionButton(glyph = "♥", label = "12k")
    ReelActionButton(glyph = "◌", label = "348")
    ReelActionButton(glyph = "↗", label = "Share")
}

@Composable
private fun ReelActionButton(glyph: String, label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.width(44.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(Color.Black.copy(alpha = 0.28f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = glyph, color = Color.White, fontSize = 18.sp)
        }
        Text(text = label, color = Color.White, fontSize = 9.sp)
    }
}
