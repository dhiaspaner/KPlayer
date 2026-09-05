package kplayer.ui

import kplayer.ui.model.VideoScalingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Render mode as runtime state, not just a construction argument.
 *
 * It was previously only a [VideoSurfaceConfig] field, so switching it meant the
 * caller rebuilding the config and threading its own `var` through — which the
 * sample did. Hoisting it into the holder puts it on the same footing as
 * `scalingMode`: drivable from outside the player, and preserved across
 * configuration changes.
 */
class PlayerUiStateHolderRenderModeTest {

    /**
     * The invariant that actually broke: three entry points each carried their own
     * copy of the default, and two of them drifted apart — the holder's
     * constructor said one thing and its `remember` factory said another, so the
     * same call site meant different things depending on which you reached for.
     *
     * Deliberately does not assert *which* mode it is. That is configuration —
     * `VideoRenderMode.Default` is the one place it is decided, and this only
     * insists that everyone defers to it.
     */
    @Test
    fun `every default defers to the one configured mode`() {
        assertEquals(
            VideoRenderMode.Default,
            PlayerUiStateHolder().renderMode,
            "the holder's constructor must not carry its own default",
        )
        assertEquals(
            VideoRenderMode.Default,
            VideoSurfaceConfig().renderMode,
            "the surface config must not carry its own default",
        )
    }

    /**
     * A state saved before the property existed restores to the configured
     * default too, rather than to a literal frozen into the saver.
     */
    @Test
    fun `the legacy restore path uses the configured default`() {
        val restored = with(PlayerUiStateHolder.Saver) {
            restore(listOf(VideoScalingMode.CROP.ordinal, false, true))
        }

        assertEquals(VideoRenderMode.Default, assertNotNull(restored).renderMode)
    }

    @Test
    fun `the initial value is honoured`() {
        val holder = PlayerUiStateHolder(renderMode = VideoRenderMode.TEXTURE)

        assertEquals(VideoRenderMode.TEXTURE, holder.renderMode)
    }

    @Test
    fun `setting the mode takes effect`() {
        val holder = PlayerUiStateHolder(renderMode = VideoRenderMode.DIRECT)

        holder.setRenderMode(VideoRenderMode.TEXTURE)

        assertEquals(VideoRenderMode.TEXTURE, holder.renderMode)
    }

    @Test
    fun `toggling moves between the two modes and back`() {
        // Started explicitly rather than from the default, so this tests the
        // toggle rather than whatever the default happens to be.
        val holder = PlayerUiStateHolder(renderMode = VideoRenderMode.DIRECT)

        holder.toggleRenderMode()
        assertEquals(VideoRenderMode.TEXTURE, holder.renderMode)

        holder.toggleRenderMode()
        assertEquals(VideoRenderMode.DIRECT, holder.renderMode)
    }

    @Test
    fun `render mode is independent of the other chrome state`() {
        val holder = PlayerUiStateHolder(renderMode = VideoRenderMode.DIRECT)

        holder.setRenderMode(VideoRenderMode.TEXTURE)

        // Changing how frames are drawn must not disturb scaling, controls or
        // fullscreen — they are separate decisions that happen to share a holder.
        assertEquals(VideoScalingMode.FIT, holder.scalingMode)
        assertEquals(true, holder.controlsVisible)
        assertEquals(false, holder.isFullscreen)
    }

    @Test
    fun `the mode survives a save and restore`() {
        val holder = PlayerUiStateHolder(
            scalingMode = VideoScalingMode.CROP,
            controlsVisible = false,
            isFullscreen = true,
            renderMode = VideoRenderMode.TEXTURE,
        )

        val restored = assertNotNull(saveAndRestore(holder))

        assertEquals(VideoRenderMode.TEXTURE, restored.renderMode)
        assertEquals(VideoScalingMode.CROP, restored.scalingMode)
        assertEquals(false, restored.controlsVisible)
        assertEquals(true, restored.isFullscreen)
    }

    /**
     * A state saved before the render mode existed is three entries long. Indexing
     * past its end would throw during restore — a crash on rotation rather than a
     * lost preference — so the saver reads it defensively.
     */
    @Test
    fun `a state saved before render mode existed still restores`() {
        @Suppress("UNCHECKED_CAST")
        val restored = with(PlayerUiStateHolder.Saver) {
            restore(listOf(VideoScalingMode.CROP.ordinal, false, true))
        }

        assertEquals(VideoRenderMode.Default, assertNotNull(restored).renderMode)
        assertEquals(VideoScalingMode.CROP, restored.scalingMode)
    }

    private fun saveAndRestore(holder: PlayerUiStateHolder): PlayerUiStateHolder? {
        val saver = PlayerUiStateHolder.Saver
        val saved = with(saver) {
            object : androidx.compose.runtime.saveable.SaverScope {
                override fun canBeSaved(value: Any) = true
            }.save(holder)
        }
        return saver.restore(assertNotNull(saved))
    }
}
