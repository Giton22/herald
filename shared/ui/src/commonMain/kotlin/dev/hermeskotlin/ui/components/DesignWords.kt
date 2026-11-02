package dev.hermeskotlin.ui.components

import androidx.compose.runtime.Composable
import dev.hermeskotlin.designsystem.DesignWords
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
