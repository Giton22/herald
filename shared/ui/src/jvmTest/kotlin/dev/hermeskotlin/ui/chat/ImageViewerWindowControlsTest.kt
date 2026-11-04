package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.media.GatewayFile
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.ui.LocalWindowControls
import dev.hermeskotlin.ui.WindowControls
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** Native window controls are simulated in pixels, as the desktop provides them to the shared viewer. */
@OptIn(ExperimentalTestApi::class)
class ImageViewerWindowControlsTest {
    @Test fun leftControls() = toolbarClears(128, 0)
    @Test fun rightControls() = toolbarClears(0, 128)
    @Test fun leftControlsAtLargeZoom() = toolbarClears(128, 0, zoom = 2f)
    @Test fun rightControlsAtSmallZoom() = toolbarClears(0, 128, zoom = 0.75f)
    @Test fun leftControlsInLightTheme() = toolbarClears(128, 0, scheme = ColorScheme.Light)
    @Test fun noCustomTitleBar() = toolbarClears(0, 0)
    @Test fun narrowWindowWithLeftControls() = toolbarClears(100, 0, width = 360)
    @Test fun narrowWindowWithRightControls() = toolbarClears(0, 100, width = 360, scheme = ColorScheme.Light)

    @Test fun fileViewerWithLeftControls() = fileHeaderClears(128, 0)
    @Test fun fileViewerWithRightControls() = fileHeaderClears(0, 128)

    /** The file viewer opens full screen too, its header in the title bar's row. */
    private fun fileHeaderClears(start: Int, end: Int) = runDesktopComposeUiTest(width = 1024, height = 240) {
        val name = "notes.txt"
        setContent {
            HermesTheme(ColorScheme.Dark) {
                CompositionLocalProvider(LocalWindowControls provides WindowControls(start, end, 40)) {
                    Box(Modifier.fillMaxSize().testTag("window")) {
                        FileViewer(
                            GatewayFile("/home/u/$name"),
                            onDismiss = {},
                            load = { FileLoad.Failed("Not here") },
                            download = { FileDownload.Failed("Not here") },
                        )
                    }
                }
            }
        }
        waitForIdle()
        val windowWidth = onNodeWithTag("window").fetchSemanticsNode().boundsInRoot.width
        val close = onNodeWithContentDescription("Close").fetchSemanticsNode().boundsInRoot
        // Save and Share at the header's end, both named for the file.
        val actions = onAllNodes(hasContentDescription(name, substring = true)).fetchSemanticsNodes().map { it.boundsInRoot }
        assertTrue(actions.size >= 2, "Save and Share should be in the header: $actions")
        assertTrue(close.left >= start, "Close $close overlaps the left $start pixels")
        actions.forEach { assertTrue(it.right <= windowWidth - end, "$it runs under the right $end pixels of $windowWidth") }
    }

    private fun toolbarClears(start: Int, end: Int, zoom: Float = 1f, scheme: ColorScheme = ColorScheme.Dark, width: Int = 1024) = runDesktopComposeUiTest(width = width, height = 240) {
        val name = "Example picture.png"
        val bytes = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        setContent {
            HermesTheme(scheme) {
                CompositionLocalProvider(
                    LocalWindowControls provides WindowControls(start, end, if (start + end > 0) 40 else 0),
                    LocalDensity provides Density(zoom),
                ) {
                    Box(Modifier.fillMaxSize().testTag("window")) {
                        var showing by remember { mutableStateOf(true) }
                        if (showing) ImageViewer(ViewerImage(name, bytes = bytes), onDismiss = { showing = false })
                    }
                }
            }
        }
        waitForIdle()
        val windowWidth = onNodeWithTag("window").fetchSemanticsNode().boundsInRoot.width
        val close = onNodeWithContentDescription("Close").fetchSemanticsNode().boundsInRoot
        val title = onNodeWithText(name).fetchSemanticsNode().boundsInRoot
        val save = onNodeWithContentDescription("Save").fetchSemanticsNode().boundsInRoot
        val open = onNodeWithContentDescription("Open in app").fetchSemanticsNode().boundsInRoot
        assertTrue(close.left >= start, "Close $close overlaps the left $start pixels")
        assertTrue(title.left >= close.right && title.right <= save.left, "Filename must fit between the actions")
        assertTrue(open.right <= windowWidth - end, "Open $open overlaps the right $end pixels of $windowWidth")
        assertTrue(save.width > 0 && title.width > 0, "The toolbar must keep space for its actions and filename")
        if (start + end == 0) assertTrue(close.left < 16 * zoom, "No native controls should mean no extra inset")
        onNodeWithContentDescription("Close").performMouseInput { click() }
        onNodeWithText(name).assertDoesNotExist()
    }
}
