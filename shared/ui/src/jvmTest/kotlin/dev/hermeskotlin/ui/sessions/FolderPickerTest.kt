package dev.hermeskotlin.ui.sessions

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.projects.FolderListing
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.designsystem.hermesTheme
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals

/** New folder in the project folder picker. */
@OptIn(ExperimentalTestApi::class)
class FolderPickerTest {

    @Test
    fun aLateNewFolderDoesntPullThePickerBack() = runComposeUiTest {
        val answer = CompletableDeferred<String?>()
        val asked = mutableListOf<String>()
        setContent {
            hermesTheme(AccentPalette.Blue)(ColorScheme.Dark) {
                NewProjectDialog(
                    ProjectDraft(name = "Site", folder = "/srv/a"),
                    busy = false,
                    error = null,
                    onDismiss = {},
                    onCreate = { _, _ -> },
                    listFolders = { _, _ -> FolderListing(emptyList()) },
                    makeFolder = { dir, name ->
                        asked += "$dir$name"
                        answer.await()
                    },
                )
            }
        }
        onNodeWithText("Browse folders").performClick()
        onNodeWithContentDescription("New folder").performClick()
        waitForIdle()
        onAllNodes(hasSetTextAction())[1].performTextInput("site")
        onNodeWithText("Create").performClick()
        waitForIdle()

        // Up before the gateway answers: its answer is for /srv/a/, which the picker has left.
        onNodeWithContentDescription("Up a folder").performClick()
        waitForIdle()
        answer.complete(null)
        waitForIdle()

        assertEquals(listOf("/srv/a/site"), asked)
        onNodeWithText("/srv/").assertExists()
        assertEquals(0, onAllNodesWithText("/srv/a/site/").fetchSemanticsNodes().size)
    }

    @Test
    fun closingNewFolderClearsItAndDropsTheCall() = runComposeUiTest {
        val answer = CompletableDeferred<String?>()
        var asked = 0
        var made = false
        setContent {
            hermesTheme(AccentPalette.Blue)(ColorScheme.Dark) {
                NewProjectDialog(
                    ProjectDraft(name = "Site", folder = "/srv/a"),
                    busy = false,
                    error = null,
                    onDismiss = {},
                    onCreate = { _, _ -> },
                    listFolders = { _, _ -> FolderListing(if (made) listOf("site") else emptyList()) },
                    makeFolder = { _, _ ->
                        asked++
                        answer.await()
                    },
                )
            }
        }
        onNodeWithText("Browse folders").performClick()
        onNodeWithContentDescription("New folder").performClick()
        waitForIdle()
        onAllNodes(hasSetTextAction())[1].performTextInput("site")
        onNodeWithText("Create").performClick()
        waitForIdle()

        // Closed and reopened while the gateway is asked: the name is gone and the field isn't stuck waiting.
        onNodeWithContentDescription("New folder").performClick()
        onNodeWithContentDescription("New folder").performClick()
        waitForIdle()
        assertEquals(0, onAllNodesWithText("site").fetchSemanticsNodes().size)
        onNodeWithText("Create").assertIsNotEnabled()
        onAllNodes(hasSetTextAction())[1].assertIsEnabled()

        // The gateway made it after all: the picker stays put, and the folder shows in the list.
        made = true
        answer.complete(null)
        waitForIdle()
        assertEquals(1, asked)
        onNodeWithText("/srv/a/").assertExists()
        assertEquals(0, onAllNodesWithText("/srv/a/site/").fetchSemanticsNodes().size)
        onNodeWithText("site").assertExists()

        onAllNodes(hasSetTextAction())[1].performTextInput("other")
        onNodeWithText("Create").assertIsEnabled()
    }
}
