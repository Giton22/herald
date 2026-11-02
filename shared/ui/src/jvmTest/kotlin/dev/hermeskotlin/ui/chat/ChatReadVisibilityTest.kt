package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.designsystem.AccentPalette
import dev.hermeskotlin.designsystem.LocalDesktopLayout
import dev.hermeskotlin.designsystem.hermesTheme
import dev.hermeskotlin.ui.preview.PreviewChatActions
import dev.hermeskotlin.ui.voice.DictationState
import dev.hermeskotlin.ui.voice.VoiceChatState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The same transcript viewport is used for regular sessions and bot chats. */
@OptIn(ExperimentalTestApi::class)
class ChatReadVisibilityTest {
    @Test
    fun openingAtUnreadDoesNotReadTheUnseenTail() = runComposeUiTest {
        var reads = 0
        setContent { Conversation(150.0) { reads++ } }
        waitForIdle()
        onNodeWithText("New messages").assertIsDisplayed()
        onNodeWithText("Reply 16").assertIsDisplayed()
        runOnIdle { assertEquals(0, reads) }
        onNode(hasScrollToIndexAction()).performScrollToIndex(39)
        waitForIdle()
        onNodeWithText("Reply 40").assertIsDisplayed()
        runOnIdle { assertTrue(reads > 0) }
    }

    @Test
    fun loadingAnOlderPageStillAllowsReadingThroughToTheBottom() = runComposeUiTest {
        var first by mutableStateOf(21)
        var reads = 0
        setContent { Conversation(250.0, first = first) { reads++ } }
        waitForIdle()
        runOnIdle { assertEquals(0, reads); first = 1 }
        waitForIdle()
        onNode(hasScrollToIndexAction()).performScrollToIndex(39)
        waitForIdle()
        runOnIdle { assertTrue(reads > 0) }
    }

    @Test
    fun aSharedWatermarkBeyondTheTailReadsAtTheBottom() = runComposeUiTest {
        var reads = 0
        setContent { Conversation(500.0, dark = false) { reads++ } }
        waitForIdle()
        onNodeWithText("Reply 40").assertIsDisplayed()
        runOnIdle { assertTrue(reads > 0) }
    }

    @Test
    fun cachedAndRunningTranscriptsWaitUntilTheReadSignalIsEnabled() = runComposeUiTest {
        var enabled by mutableStateOf(false)
        var reads = 0
        setContent { Conversation(null, onReadLatest = if (enabled) ({ reads++ }) else null) }
        waitForIdle()
        runOnIdle { assertEquals(0, reads); enabled = true }
        waitForIdle()
        runOnIdle { assertTrue(reads > 0) }
    }

    @androidx.compose.runtime.Composable
    private fun Conversation(readUpTo: Double?, dark: Boolean = true, first: Int = 1, onReadLatest: (() -> Unit)?) {
        hermesTheme(AccentPalette.Blue)(if (dark) ColorScheme.Dark else ColorScheme.Light) {
            CompositionLocalProvider(LocalDesktopLayout provides true) {
                Box(Modifier.size(360.dp, 640.dp)) {
                    ChatView(
                        title = "Conversation", state = ChatState(storedSessionId = "chat", historyLoaded = true,
                            messages = (first..40).map { ChatMessage.Assistant("row-$it", text = when (it) {
                                17 -> "```kotlin\r\nval value = 1\r\n```"
                                18 -> "longword".repeat(80)
                                19 -> "| Item | Value |\n| --- | --- |\n" + (1..20).joinToString("\n") { row -> "| $row | Value |" }
                                20 -> ""
                                else -> "Reply $it"
                            }, timestamp = it * 10.0) }),
                        picker = ModelPickerState(), connected = true,
                        attachments = emptyList(), attachmentError = null, voiceChat = VoiceChatState(),
                        dictation = DictationState(), suggestions = emptyList(), sprite = null,
                        placeholder = "Message", notice = null, actions = PreviewChatActions(),
                        readUpTo = readUpTo, onReadLatest = onReadLatest,
                        onOpenSidebar = {}, onNewChat = null, onOpenMenu = null, onOpenModels = {},
                        onAttach = {}, onDictate = {}, onVoiceChat = {}, onOpenPets = {}, onViewImage = {}, onNotice = {},
                    )
                }
            }
        }
    }
}
