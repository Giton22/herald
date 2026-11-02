package dev.hermeskotlin.designsystem.components

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.designsystem.HermesTheme
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
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PasswordTextFieldTest {
    private val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val state = TextFieldState()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(RegisterHostActivity()).around(composeRule)

    private fun show(value: String, password: Boolean = true) {
        state.edit { replace(0, length, value); selection = TextRange(value.length) }
        composeRule.setContent {
            HermesTheme(ColorScheme.Dark) { TextField(state = state, password = password, label = "Password") }
        }
        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.runOnIdle { state.edit { selection = TextRange(value.length) } }
    }

    private fun backspace() = composeRule.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Backspace) }

    private fun assertValue(value: String, selection: TextRange = TextRange(value.length)) = composeRule.runOnIdle {
        assertEquals(value, state.text.toString())
        assertEquals(selection, state.selection)
    }

    @Test
    fun hiddenPasswordBackspaceRemovesOnlyThePreviousCharacter() {
        show("correct-horse")
        backspace()
        assertValue("correct-hors")
    }

    @Test
    fun hiddenPasswordCanDeleteAndInsertInTheMiddle() {
        show("abcd")
        composeRule.onNode(hasSetTextAction()).performTextInputSelection(TextRange(2))
        backspace()
        assertValue("acd", TextRange(1))
        composeRule.onNode(hasSetTextAction()).performTextInput("X")
        assertValue("aXcd", TextRange(2))
    }

    @Test
    fun hiddenPasswordCanReplaceASelectedRange() {
        show("abcdef")
        composeRule.onNode(hasSetTextAction()).performTextInputSelection(TextRange(1, 4))
        composeRule.onNode(hasSetTextAction()).performTextInput("X")
        assertValue("aXef", TextRange(2))
    }

    @Test
    fun backspaceDoesNotLeaveHalfOfASupplementaryCharacter() {
        show("a😀b")
        backspace()
        assertValue("a😀")
        backspace()
        assertValue("a")
    }

    @Test
    fun hiddenPasswordCanSelectAndReplaceASupplementaryCharacter() {
        show("a😀b")
        composeRule.onNode(hasSetTextAction()).performTextInputSelection(TextRange(1, 3))
        composeRule.onNode(hasSetTextAction()).performTextInput("X")
        assertValue("aXb", TextRange(2))
    }

    @Test
    fun aCursorPlacedOnTheBulletsStaysACursorAroundASupplementaryCharacter() {
        show("a😀b")
        // On-screen offsets, as TalkBack or arrow keys place them: in "•••" the cursor after the second bullet is after the emoji.
        composeRule.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.SetSelection) { it(2, 2, false) }
        assertValue("a😀b", TextRange(3))
        composeRule.onNode(hasSetTextAction()).performTextInput("X")
        assertValue("a😀Xb", TextRange(4))
    }

    @Test
    fun revealingAndHidingKeepsTheTextAndDeletionPosition() {
        show("abcd")
        composeRule.onNodeWithContentDescription("Show password").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInputSelection(TextRange(2))
        backspace()
        assertValue("acd", TextRange(1))
        composeRule.onNodeWithContentDescription("Hide password").performClick()
        assertValue("acd", TextRange(1))
        backspace()
        assertValue("cd", TextRange(0))
    }

    @Test
    fun anOrdinaryFieldStillDeletesOneCharacter() {
        show("abcd", password = false)
        backspace()
        assertValue("abc")
    }

    @Test
    fun anEmptyHiddenPasswordCanBeTypedAndDeleted() {
        show("")
        backspace()
        assertValue("")
        composeRule.onNode(hasSetTextAction()).performTextInput("ab")
        backspace()
        assertValue("a")
        backspace()
        assertValue("")
    }

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
}
