package dev.hermeskotlin.ui.i18n

import dev.hermeskotlin.core.settings.AppLanguage
import java.util.Locale

actual fun deviceLanguage(): AppLanguage =
    if (Locale.getDefault().language == "zh") AppLanguage.Chinese else AppLanguage.English
