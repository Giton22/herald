package dev.hermeskotlin.ui.sessions

import androidx.compose.runtime.Composable
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A line a view model shows the user, kept as a catalogue key until a screen draws it, so the view model (and its
 * tests) stays free of Compose and of any one language.
 */
sealed interface Said {
    /** [res] with its [args]; [detail], the gateway's or an exception's own words, follows after a colon. */
    data class Line(val res: StringResource, val args: List<Any> = emptyList(), val detail: String? = null) : Said

    /** Words that come as they are, from the gateway or an exception, with nothing of the app's around them. */
    data class Verbatim(val text: String) : Said
}

internal fun said(res: StringResource, vararg args: Any, detail: String? = null): Said = Said.Line(res, args.toList(), detail)

/** These words as they are, or null without any. */
internal fun String?.verbatim(): Said? = this?.let { Said.Verbatim(it) }

/** [Said.Verbatim] for [text] when there is any, else [fallback]. */
internal fun saidOr(text: String?, fallback: Said): Said = text?.takeIf { it.isNotBlank() }?.let { Said.Verbatim(it) } ?: fallback

/** The line in the user's language. */
@Composable
fun Said.text(): String = when (this) {
    is Said.Verbatim -> text
    is Said.Line -> {
        val head = stringResource(res, *args.toTypedArray())
        if (detail.isNullOrBlank()) head else stringResource(Res.string.sessions_with_detail, head, detail)
    }
}
