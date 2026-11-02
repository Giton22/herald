package dev.hermeskotlin.ui.inbox

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Words a view model picks and a composable says: a view model has no language, so it names a catalogue entry
 * and its arguments, and the screen turns that into text in the language the app is in. Text from the gateway (an
 * error it sent) stays as it came.
 */
sealed interface Words {
    /** [res], with [args] in its placeholders; an argument that is [Words] itself is said first. */
    data class Of(val res: StringResource, val args: List<Any> = emptyList()) : Words

    /** Text as it came, such as the gateway's own error. */
    data class Raw(val text: String) : Words

    /** [first], then the gateway's [detail] after it when there is one ("Couldn't send." and why). */
    data class Then(val first: Words, val detail: String?) : Words
}

fun words(res: StringResource, vararg args: Any): Words = Words.Of(res, args.toList())

/** [res], then [detail] (an error's message) when it says anything. */
fun wordsThen(res: StringResource, detail: String?, vararg args: Any): Words = Words.Then(Words.Of(res, args.toList()), detail)

/** These words in the app's language. */
@Composable
fun Words.text(): String = when (this) {
    is Words.Raw -> text
    is Words.Of -> if (args.isEmpty()) stringResource(res) else stringResource(res, *args.map { if (it is Words) it.text() else it }.toTypedArray())
    is Words.Then -> {
        val head = first.text()
        detail?.trim()?.takeIf { it.isNotEmpty() }?.let { "$head $it" } ?: head
    }
}
