package dev.hermeskotlin.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.designsystem.components.LinkUnderPointer
import dev.hermeskotlin.designsystem.components.MarkdownText
import dev.hermeskotlin.designsystem.hermesTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A right-click on a link in a reply offers to open or copy it, as a desktop's apps do. */
@OptIn(ExperimentalTestApi::class)
class LinkMenuTest {

    @Test
    fun rightClickOnALinkOffersToOpenAndCopyIt() = runComposeUiTest {
        useAppTextContextMenu()
        setContent {
            hermesTheme(AccentPalette.Blue)(ColorScheme.Dark) {
                CompositionLocalProvider(LocalDesktopLayout provides true) {
                    PlatformTextContextMenu {
                        SelectionContainer {
                            Box(Modifier.width(480.dp).testTag("reply")) {
                                MarkdownText("[docs](https://example.com) and a good many more words after the link")
                            }
                        }
                    }
                }
            }
        }
        val onLink = Offset(12f, 10f)
        val offLink = Offset(400f, 10f)

        onNodeWithTag("reply").performMouseInput { moveTo(onLink) }
        waitForIdle()
        assertEquals("https://example.com", LinkUnderPointer.link?.url)

        onNodeWithTag("reply").performMouseInput { moveTo(offLink) }
        waitForIdle()
        assertNull(LinkUnderPointer.link)

        // As a hand does: on the link for a moment, then the click.
        onNodeWithTag("reply").performMouseInput { moveTo(onLink) }
        waitForIdle()
        onNodeWithTag("reply").performMouseInput { rightClick(onLink) }
        waitForIdle()
        onNodeWithText("Select all").assertExists()
        onNodeWithText("Open link").assertExists()
        onNodeWithText("Copy link").assertExists()

        // Off the link: the text's own rows only.
        // A click outside closes it (headless, a right-click there has no window to go on to).
        onNodeWithTag("reply").performMouseInput { click(offLink) }
        waitForIdle()
        onNodeWithText("Open link").assertDoesNotExist()
        onNodeWithTag("reply").performMouseInput { rightClick(offLink) }
        waitForIdle()
        onNodeWithText("Select all").assertExists()
        onNodeWithText("Open link").assertDoesNotExist()
    }
}
