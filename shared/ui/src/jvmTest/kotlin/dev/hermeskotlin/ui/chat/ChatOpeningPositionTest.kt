package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.designsystem.hermesTheme
import dev.hermeskotlin.ui.LocalShortcuts
import dev.hermeskotlin.ui.Shortcut
import dev.hermeskotlin.ui.ShortcutBus
import dev.hermeskotlin.ui.preview.PreviewChatActions
import dev.hermeskotlin.ui.voice.DictationState
import dev.hermeskotlin.ui.voice.VoiceChatState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opening the real transcript while the saved copy gives way to the gateway's paged history. */
@OptIn(ExperimentalTestApi::class)
class ChatOpeningPositionTest {
    private fun rows(range: IntRange) = range.map {
        ChatMessage.Assistant("row-$it", text = "Reply $it", timestamp = it * 10.0)
    }

    private inner class History(range: IntRange) {
        var state by mutableStateOf(ChatState(
            storedSessionId = "chat", historyLoaded = true, savedCopyAt = 1,
            messages = rows(range),
        ))
        var owner by mutableStateOf("first-owner")
        var readUpTo by mutableStateOf<Double?>(50.0)
        val shortcuts = ShortcutBus()
        var pages = 0
        val actions = object : ChatActions by PreviewChatActions() {
            override fun loadOlder() {
                if (!state.loadingOlder) {
                    pages++
                    state = state.copy(loadingOlder = true)
                }
            }
        }
    }

    private fun ComposeUiTest.show(history: History, readUpTo: Double? = 50.0, dark: Boolean = true) {
        history.readUpTo = readUpTo
        setContent {
            hermesTheme(AccentPalette.Blue)(if (dark) ColorScheme.Dark else ColorScheme.Light) {
                CompositionLocalProvider(LocalDesktopLayout provides true, LocalShortcuts provides history.shortcuts) {
                    Box(Modifier.size(360.dp, 640.dp)) { Conversation(history.state, history.actions, history.readUpTo, history.owner) }
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun freshHistoryFindsUnreadMessagesBeforeTheSavedCopy() = runComposeUiTest {
        val history = History(11..30)
        show(history)
        runOnIdle { history.state = history.state.copy(messages = rows(21..40), savedCopyAt = null, olderMessages = true) }
        waitForIdle()

        assertEquals(1, history.pages)
        runOnIdle { history.state = history.state.copy(messages = rows(1..40), olderMessages = false, loadingOlder = false) }
        waitForIdle()
        onNodeWithText("New messages").assertIsDisplayed()
        onNodeWithText("Reply 6").assertIsDisplayed()
    }

    @Test
    fun removingTheCachedUnreadMessagesOpensAtTheCurrentBottom() = runComposeUiTest {
        val history = History(11..30)
        show(history, readUpTo = 150.0)
        runOnIdle { history.state = history.state.copy(messages = rows(1..12), savedCopyAt = null) }
        waitForIdle()

        onNodeWithText("New messages").assertDoesNotExist()
        onNodeWithText("Reply 12").assertIsDisplayed()
    }

    @Test
    fun currentHistoryWithoutAReadBoundaryOpensAtTheBottom() = runComposeUiTest {
        val history = History(11..30)
        show(history, readUpTo = null, dark = false)
        runOnIdle { history.state = history.state.copy(messages = rows(21..40), savedCopyAt = null) }
        waitForIdle()

        onNodeWithText("New messages").assertDoesNotExist()
        onNodeWithText("Reply 40").assertIsDisplayed()
    }

    @Test
    fun keyboardNavigationWhileLoadingTakesPrecedenceOverTheUnreadJump() = runComposeUiTest {
        val history = History(1..40)
        show(history, readUpTo = 150.0)
        runOnIdle { assertTrue(history.shortcuts.fire(Shortcut.ScrollTop)) }
        waitForIdle()
        onNodeWithText("Reply 1").assertIsDisplayed()
        runOnIdle { history.state = history.state.copy(messages = rows(1..45), savedCopyAt = null) }
        waitForIdle()

        onNodeWithText("Reply 1").assertIsDisplayed()
    }

    @Test
    fun wheelNavigationWhileLoadingTakesPrecedenceOverTheUnreadJump() = runComposeUiTest {
        val history = History(1..40)
        show(history, readUpTo = 150.0)
        onNode(hasScrollToIndexAction()).performMouseInput { moveTo(center); scroll(-10000f) }
        waitForIdle()
        onNodeWithText("Reply 1").assertIsDisplayed()
        runOnIdle { history.state = history.state.copy(messages = rows(1..45), savedCopyAt = null) }
        waitForIdle()

        onNodeWithText("Reply 1").assertIsDisplayed()
    }

    @Test
    fun aTapThatDoesNotMoveTheListKeepsTheUnreadJump() = runComposeUiTest {
        val history = History(11..30)
        show(history)
        onNode(hasScrollToIndexAction()).performTouchInput { click(center) }
        waitForIdle()
        runOnIdle { history.state = history.state.copy(messages = rows(1..40), savedCopyAt = null) }
        waitForIdle()

        onNodeWithText("New messages").assertIsDisplayed()
        onNodeWithText("Reply 6").assertIsDisplayed()
    }

    @Test
    fun aTouchDragWhileLoadingTakesPrecedenceOverTheUnreadJump() = runComposeUiTest {
        val history = History(1..40)
        show(history, readUpTo = 150.0)
        onNodeWithText("Reply 16").assertIsDisplayed()
        repeat(3) { onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() } }
        waitForIdle()
        onNodeWithText("Reply 16").assertDoesNotExist()
        runOnIdle { history.state = history.state.copy(messages = rows(1..45), savedCopyAt = null) }
        waitForIdle()

        onNodeWithText("Reply 16").assertDoesNotExist()
    }

    @Test
    fun jumpingToTheLatestWhileLoadingIsNotUndoneByTheUnreadLine() = runComposeUiTest {
        val history = History(1..40)
        show(history, readUpTo = 150.0)
        onNodeWithText("Reply 16").assertIsDisplayed()
        onNodeWithContentDescription("Jump to latest").performClick()
        waitForIdle()
        onNodeWithText("Reply 40").assertIsDisplayed()
        runOnIdle { history.state = history.state.copy(messages = rows(1..45), savedCopyAt = null) }
        waitForIdle()

        onNodeWithText("Reply 16").assertDoesNotExist()
    }

    @Test
    fun theUnreadSearchLimitFallsBackToTheCurrentBottom() = runComposeUiTest {
        val history = History(11..30)
        show(history)
        runOnIdle { history.state = history.state.copy(messages = rows(1000..1100), savedCopyAt = null, olderMessages = true) }
        waitForIdle()

        repeat(5) { page ->
            assertEquals(page + 1, history.pages)
            runOnIdle {
                history.state = history.state.copy(messages = rows((900 - page * 100)..1100), loadingOlder = false)
            }
            waitForIdle()
        }
        assertEquals(5, history.pages)
        onNodeWithText("New messages").assertDoesNotExist()
        onNodeWithText("Reply 1100").assertIsDisplayed()
    }

    @Test
    fun savedHistoryStillShowsTheUnreadPositionWhileOffline() = runComposeUiTest {
        val history = History(1..40)
        history.state = history.state.copy(historyError = "Offline")
        show(history, readUpTo = 150.0, dark = false)

        onNodeWithText("New messages").assertIsDisplayed()
        onNodeWithText("Reply 16").assertIsDisplayed()
        assertEquals(0, history.pages)
    }

    @Test
    fun anotherConversationDoesNotInheritTheReadersNavigation() = runComposeUiTest {
        val history = History(1..40)
        history.state = history.state.copy(savedCopyAt = null)
        show(history, readUpTo = 150.0)
        runOnIdle { history.shortcuts.fire(Shortcut.ScrollTop) }
        waitForIdle()
        onNodeWithText("Reply 1").assertIsDisplayed()
        runOnIdle { history.state = history.state.copy(storedSessionId = "other", messages = rows(1..45)) }
        waitForIdle()

        onNodeWithText("New messages").assertIsDisplayed()
        onNodeWithText("Reply 16").assertIsDisplayed()
    }

    @Test
    fun anotherOwnerWithTheSameStoredIdGetsItsOwnOpeningPosition() = runComposeUiTest {
        val history = History(1..40)
        history.state = history.state.copy(savedCopyAt = null)
        show(history, readUpTo = 150.0)
        runOnIdle { history.shortcuts.fire(Shortcut.ScrollTop) }
        waitForIdle()
        runOnIdle { history.owner = "another-gateway-and-profile" }
        waitForIdle()

        onNodeWithText("New messages").assertIsDisplayed()
        onNodeWithText("Reply 16").assertIsDisplayed()
    }

    @Test
    fun reopeningWithANewReadBoundaryStartsANewVisit() = runComposeUiTest {
        val history = History(1..40)
        history.state = history.state.copy(savedCopyAt = null)
        show(history, readUpTo = 150.0)
        runOnIdle { history.shortcuts.fire(Shortcut.ScrollTop) }
        waitForIdle()
        runOnIdle { history.readUpTo = 250.0 }
        waitForIdle()

        onNodeWithText("New messages").assertIsDisplayed()
        onNodeWithText("Reply 26").assertIsDisplayed()
        runOnIdle { history.shortcuts.fire(Shortcut.ScrollTop) }
        onNode(hasScrollToIndexAction()).performMouseInput { moveTo(center); scroll(-10000f) }
        waitForIdle()
        runOnIdle {
            history.state = history.state.copy(messages = rows(1..40) + ChatMessage.User("remote", "Stored prompt", timestamp = 410.0))
        }
        waitForIdle()
        onNodeWithText("Reply 1").assertIsDisplayed()
    }

    @Test
    fun aStoredPromptArrivingWithHistoryDoesNotPullTheReaderDown() = runComposeUiTest {
        val history = History(1..40)
        show(history, readUpTo = 150.0)
        runOnIdle { history.shortcuts.fire(Shortcut.ScrollTop) }
        waitForIdle()
        runOnIdle {
            history.state = history.state.copy(
                messages = rows(1..40) + ChatMessage.User("remote", "Stored prompt", timestamp = 410.0),
                savedCopyAt = null,
            )
        }
        waitForIdle()

        onNodeWithText("Reply 1").assertIsDisplayed()
    }

    @Test
    fun aPromptSentHereStillJumpsToTheBottomAfterReadingBack() = runComposeUiTest {
        val history = History(1..40)
        show(history, readUpTo = 150.0)
        runOnIdle { history.shortcuts.fire(Shortcut.ScrollTop) }
        waitForIdle()
        runOnIdle {
            history.state = history.state.copy(messages = rows(1..40) + ChatMessage.User("local", "New prompt", pending = true))
        }
        waitForIdle()

        onNodeWithText("New prompt").assertIsDisplayed()
    }

    @Test
    fun aPromptAcknowledgedBeforeDrawingStillTakesPrecedenceOverHistory() = runComposeUiTest {
        val history = History(1..40)
        show(history, readUpTo = 150.0)
        runOnIdle { history.shortcuts.fire(Shortcut.ScrollTop) }
        waitForIdle()
        runOnIdle {
            history.state = history.state.copy(messages = rows(1..40) + ChatMessage.User("local-1", "New prompt", rowId = 41))
        }
        waitForIdle()
        runOnIdle {
            history.state = history.state.copy(messages = rows(1..40) + ChatMessage.User("row-41", "New prompt", timestamp = 410.0), savedCopyAt = null)
        }
        waitForIdle()

        onNodeWithText("New prompt").assertIsDisplayed()
    }

    @androidx.compose.runtime.Composable
    private fun Conversation(state: ChatState, actions: ChatActions, readUpTo: Double?, owner: String) {
        ChatView(
            title = "Conversation", state = state, conversationKey = owner to state.storedSessionId, picker = ModelPickerState(), connected = true,
            attachments = emptyList(), attachmentError = null, voiceChat = VoiceChatState(),
            dictation = DictationState(), suggestions = emptyList(), sprite = null,
            placeholder = "Message", notice = null, actions = actions, readUpTo = readUpTo,
            onOpenSidebar = {}, onNewChat = null, onOpenMenu = null, onOpenModels = {},
            onAttach = {}, onDictate = {}, onVoiceChat = {}, onOpenPets = {}, onViewImage = {}, onNotice = {},
        )
    }
}
