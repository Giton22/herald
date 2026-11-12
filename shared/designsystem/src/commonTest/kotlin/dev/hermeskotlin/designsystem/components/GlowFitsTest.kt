package dev.hermeskotlin.designsystem.components

import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlowFitsTest {

    @Test
    fun aButtonAndAShortPromptGlow() {
        assertTrue(glowFits(Size(160f, 160f), radius = 54f, spread = 0f))
        assertTrue(glowFits(Size(900f, 400f), radius = 54f, spread = 0f))
    }

    @Test
    fun aPromptAFewScreensTallStillGlows() {
        assertTrue(glowFits(Size(900f, 7_000f), radius = 54f, spread = 0f))
    }

    @Test
    fun aPromptTooTallForItsShadowBitmapSitsFlat() {
        // A pasted prompt of thousands of lines: its shadow would be a bitmap of over 200 MB, which Android
        // refuses to draw.
        assertFalse(glowFits(Size(900f, 230_000f), radius = 54f, spread = 0f))
        assertFalse(glowFits(Size(900f, 20_000f), radius = 54f, spread = 0f))
    }

    @Test
    fun theBlurAndSpreadCountTowardTheBitmap() {
        assertTrue(glowFits(Size(2_000f, 4_000f), radius = 0f, spread = 0f))
        assertFalse(glowFits(Size(2_000f, 4_000f), radius = 54f, spread = 0f))
        assertFalse(glowFits(Size(2_000f, 4_000f), radius = 0f, spread = 10f))
    }

    @Test
    fun aSizeBeyondAnIntsPixelsDoesNotWrapAround() {
        assertFalse(glowFits(Size(100_000f, 100_000f), radius = 0f, spread = 0f))
    }
}
