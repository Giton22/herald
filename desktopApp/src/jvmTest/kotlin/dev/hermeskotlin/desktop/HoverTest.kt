package dev.hermeskotlin.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.designsystem.hermesTheme
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertTrue

/** Phones never hover: on the desktop the theme's hover tint must stay a tint, not cover what it's over. */
@OptIn(ExperimentalTestApi::class)
class HoverTest {

    @Test
    fun hoveringARowOnlyTintsIt() = runComposeUiTest {
        setContent {
            hermesTheme(AccentPalette.Blue)(ColorScheme.Dark) {
                Box(Modifier.size(200.dp, 48.dp).testTag("row").clickable {})
            }
        }
        onNodeWithTag("row").performMouseInput { moveTo(center) }
        mainClock.advanceTimeBy(1_000)
        val image = onNodeWithTag("row").captureToImage().toAwtImage()
        val pixel = Color(image.getRGB(image.width / 2, image.height / 2), true)
        // A dark theme's 8% white over nothing, or over its dark background, is far from white.
        assertTrue(pixel.alpha < 64 || pixel.red < 80, "hovered row is $pixel")
    }
}
