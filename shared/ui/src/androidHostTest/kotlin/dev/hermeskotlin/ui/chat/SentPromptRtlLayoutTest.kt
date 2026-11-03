package dev.hermeskotlin.ui.chat

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.ui.preview.PreviewChatActions
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Rendered geometry of a prompt that has already been sent, on the production [UserBubble] the chat draws,
 * hosted by Robolectric with real Compose measurement.
 *
 * Guards the body style's per-paragraph direction: one direction for the whole prompt would lay a mostly
 * Hebrew prompt's English paragraph after a newline out on the right with the Hebrew, not at the left where
 * an English paragraph belongs, and the same in reverse. Every assertion reads the line edges of the actual
 * rendered prompt text; the text itself is asserted byte-for-byte, so no change may insert direction controls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SentPromptRtlLayoutTest {

    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    // The library ships no manifest declaring an activity, so register one before createComposeRule.
    @get:Rule
    val rules: TestRule = RuleChain.outerRule(RegisterHostActivity()).around(composeRule)

    private val english = "Thanks!"
    private val hebrew = "שלום עולם זהו משפט ארוך בעברית כדי שרוב האותיות יהיו מימין לשמאל"

    private fun show(text: String, ambient: LayoutDirection) {
        composeRule.setContent {
            HermesTheme(ColorScheme.Light) {
                CompositionLocalProvider(LocalLayoutDirection provides ambient) {
                    Box(Modifier.width(WIDTH).background(Color.White)) {
                        UserBubble(
                            message = ChatMessage.User(key = "prompt", text = text),
                            actions = PreviewChatActions(),
                            connected = true,
                            editLabel = "Edit",
                            onEdit = null,
                            onBranch = null,
                            editing = false,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun englishAfterHebrewStaysLeftInLtrAmbient() {
        val text = "$hebrew\n$english"
        show(text, LayoutDirection.Ltr)
        assertPromptParagraphs(text, LayoutDirection.Ltr)
    }

    @Test
    fun englishAfterHebrewStaysLeftInRtlAmbient() {
        val text = "$hebrew\n$english"
        show(text, LayoutDirection.Rtl)
        assertPromptParagraphs(text, LayoutDirection.Rtl)
    }

    @Test
    fun hebrewAfterEnglishStaysRightInLtrAmbient() {
        val text = "$english\n$hebrew"
        show(text, LayoutDirection.Ltr)
        assertPromptParagraphs(text, LayoutDirection.Ltr)
    }

    @Test
    fun hebrewAfterEnglishStaysRightInRtlAmbient() {
        val text = "$english\n$hebrew"
        show(text, LayoutDirection.Rtl)
        assertPromptParagraphs(text, LayoutDirection.Rtl)
    }

    /** The English paragraph starts at the left of the prompt box; the Hebrew one ends at its right. */
    private fun assertPromptParagraphs(text: String, ambient: LayoutDirection) {
        val node = composeRule.onAllNodes(hasText(text, substring = false), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .maxByOrNull { it.boundsInRoot.width }
            ?: fail("no rendered prompt with the exact sent text under $ambient ambient")
        assertEquals(
            text,
            node.config[SemanticsProperties.Text].joinToString("") { it.text },
            "the sent prompt's text changed under $ambient ambient",
        )
        val layout = layoutOf(node) ?: fail("no text layout result for the sent prompt")
        val englishLine = layout.getLineForOffset(text.indexOf(english))
        val hebrewLine = layout.getLineForOffset(text.indexOf(hebrew))
        val tol = tolPx()
        val detail = "ambient=$ambient box=${layout.size.width} " +
            (0 until layout.lineCount).joinToString("; ") { "L$it[${layout.getLineLeft(it)}..${layout.getLineRight(it)}]" }

        assertTrue(
            layout.getLineLeft(englishLine) <= tol,
            "the English paragraph starts at ${layout.getLineLeft(englishLine)}px, expected the prompt's left " +
                "content edge ($detail)",
        )
        assertTrue(
            layout.getLineRight(hebrewLine) >= layout.size.width - tol,
            "the Hebrew paragraph ends at ${layout.getLineRight(hebrewLine)}px, expected the prompt's right " +
                "content edge ${layout.size.width}px ($detail)",
        )
    }

    private fun layoutOf(node: SemanticsNode): TextLayoutResult? {
        val results = mutableListOf<TextLayoutResult>()
        if (!node.config.contains(SemanticsActions.GetTextLayoutResult)) return null
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.firstOrNull()
    }

    private fun tolPx() = with(composeRule.density) { TOLERANCE.toPx() }

    private companion object {
        val WIDTH = 320.dp
        val TOLERANCE = 2.dp
    }
}

/** Registers the Compose host activity Robolectric would otherwise fail to resolve. */
private class RegisterHostActivity : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val app = RuntimeEnvironment.getApplication()
            Shadows.shadowOf(app.packageManager).addActivityIfNotPresent(
                ComponentName(app.packageName, ComponentActivity::class.java.name),
            )
            base.evaluate()
        }
    }
}
