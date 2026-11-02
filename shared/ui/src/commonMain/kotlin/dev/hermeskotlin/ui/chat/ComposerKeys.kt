package dev.hermeskotlin.ui.chat

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** What a key press in the composer does on the desktop. */
internal enum class ComposerKey { Send, Queue, Older, Newer }

/**
 * The composer's keys, as Hermes Desktop has them: Enter sends, Shift+Enter starts a line, Ctrl+Enter queues
 * behind the running reply, and Up and Down walk back through the prompts sent before.
 */
internal fun composerKey(event: KeyEvent): ComposerKey? {
    if (event.type != KeyEventType.KeyDown || event.isAltPressed) return null
    val primary = event.isCtrlPressed || event.isMetaPressed
    return when (event.key) {
        Key.Enter, Key.NumPadEnter -> when {
            event.isShiftPressed -> null
            primary -> ComposerKey.Queue
            else -> ComposerKey.Send
        }
        Key.DirectionUp -> if (primary || event.isShiftPressed) null else ComposerKey.Older
        Key.DirectionDown -> if (primary || event.isShiftPressed) null else ComposerKey.Newer
        else -> null
    }
}

/** Ctrl+V (⌘V), or Shift+Insert as Windows has it too. */
internal fun isPasteKey(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown || event.isAltPressed) return false
    val primary = event.isCtrlPressed || event.isMetaPressed
    return when (event.key) {
        Key.V -> primary && !event.isShiftPressed
        Key.Insert -> event.isShiftPressed && !primary
        else -> false
    }
}

/** Down (1) or Up (-1) with no keys held, to move through the suggestions over the composer; null for any other key. */
internal fun suggestionStep(event: KeyEvent): Int? {
    if (event.type != KeyEventType.KeyDown || event.isShiftPressed || event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) return null
    return when (event.key) {
        Key.DirectionDown -> 1
        Key.DirectionUp -> -1
        else -> null
    }
}

/**
 * Where the marked suggestion goes [step] on from [at] in a list of [size]: round the ends, and from none
 * marked (or one past a list that got shorter) Down goes to the first, Up to the last.
 */
internal fun moveHighlight(at: Int, step: Int, size: Int): Int = when {
    size <= 0 -> -1
    at !in 0 until size -> if (step > 0) 0 else size - 1
    else -> (at + step).mod(size)
}

/** Enter or Tab with no keys held: takes the marked suggestion over the composer, else the first. */
internal fun isPickKey(event: KeyEvent): Boolean =
    event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Tab) &&
        !event.isShiftPressed && !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed

/**
 * Up and Down through the prompts sent in this chat, as a shell's history: only from an empty composer, or one
 * still showing the prompt last brought back, so Up never throws away a draft. Past the newest, Down empties it.
 */
internal class PromptRecall {
    // How far back the shown prompt is, 0 the newest; -1 when none is shown.
    private var back = -1
    private var shown: String? = null

    /** The prompt before the one shown, or null when Up should move the cursor instead. [prompts] are oldest first. */
    fun older(current: String, prompts: List<String>): String? {
        if (!walking(current)) return null
        if (back + 1 >= prompts.size) return null
        back++
        return prompts[prompts.size - 1 - back].also { shown = it }
    }

    /** The prompt after the one shown, "" past the newest, or null when nothing was brought back. */
    fun newer(current: String, prompts: List<String>): String? {
        if (back < 0 || current != shown) return null
        back--
        if (back < 0) {
            shown = null
            return ""
        }
        return prompts.getOrNull(prompts.size - 1 - back).also { shown = it } ?: "".also { reset() }
    }

    fun reset() {
        back = -1
        shown = null
    }

    private fun walking(current: String): Boolean {
        if (back >= 0 && current == shown) return true
        if (current.isNotEmpty()) return false
        reset()
        return true
    }
}
