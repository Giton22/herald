package dev.hermeskotlin.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.hermeskotlin.designsystem.DesignWords
import dev.hermeskotlin.designsystem.LocalDesignWords
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** The design system's own words (a spinner's "Loading", a field's "Clear"), from the catalogue. */
@Composable
fun rememberDesignWords(): DesignWords = DesignWords(
    loading = stringResource(Res.string.app_loading),
    clear = stringResource(Res.string.app_clear),
    showPassword = stringResource(Res.string.app_show_password),
    hidePassword = stringResource(Res.string.app_hide_password),
    dismiss = stringResource(Res.string.app_dismiss),
    copy = stringResource(Res.string.app_copy),
    copied = stringResource(Res.string.app_copied),
    openPicture = stringResource(Res.string.app_open_picture),
    pictureFailed = stringResource(Res.string.app_picture_failed),
)

/**
 * The app's language for [content]'s design-system words and for dates and times. Each window or screen of the
 * app's own that isn't under the main window's composition (a desktop pop-out, the assistant overlay, a call)
 * wraps itself in this, so it isn't left with the English defaults.
 */
@Composable
fun ProvideAppWords(content: @Composable () -> Unit) {
    TimeWords.current = rememberTimeWords()
    CompositionLocalProvider(LocalDesignWords provides rememberDesignWords(), content = content)
}
