package dev.hermeskotlin.wear

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * A line the watch shows: one of its own ([Res]), in the watch's language, or text that came as it is ([Raw]),
 * such as an error from the phone. What to say is chosen without a Context, so tests check the choice; the words
 * are looked up where the line is drawn.
 */
sealed interface WatchLine {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : WatchLine
    data class Raw(val text: String) : WatchLine
}

internal fun WatchLine.text(context: Context): String = when (this) {
    is WatchLine.Res -> context.getString(id, *args.toTypedArray())
    is WatchLine.Raw -> text
}

@Composable
internal fun WatchLine.text(): String = when (this) {
    is WatchLine.Res -> stringResource(id, *args.toTypedArray())
    is WatchLine.Raw -> text
}
