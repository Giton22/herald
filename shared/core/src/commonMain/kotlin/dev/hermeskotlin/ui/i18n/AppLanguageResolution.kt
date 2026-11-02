package dev.hermeskotlin.ui.i18n

import dev.hermeskotlin.core.settings.AppLanguage

/** The language the device itself is set to, for [AppLanguage.System]. */
expect fun deviceLanguage(): AppLanguage
