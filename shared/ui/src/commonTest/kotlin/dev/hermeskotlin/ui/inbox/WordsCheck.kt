package dev.hermeskotlin.ui.inbox

import org.jetbrains.compose.resources.StringResource

/**
 * Whether these words carry [text]: the gateway's own words, or something the app put in a catalogue entry's
 * placeholder (a name, a version). What the catalogue itself says is the translators', so tests don't read it.
 */
fun Words.mentions(text: String): Boolean = when (this) {
    is Words.Raw -> text in this.text
    is Words.Of -> args.any { (it as? Words)?.mentions(text) ?: (text in it.toString()) }
    is Words.Then -> first.mentions(text) || detail?.contains(text) == true
}

/** The catalogue entry these words open with, or null for the gateway's own words. */
val Words.entry: StringResource?
    get() = when (this) {
        is Words.Raw -> null
        is Words.Of -> res
        is Words.Then -> first.entry
    }
