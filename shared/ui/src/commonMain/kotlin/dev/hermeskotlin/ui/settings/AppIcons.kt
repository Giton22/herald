package dev.hermeskotlin.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/** The rows of the app icon picker. */
enum class AppIconGroup(val title: String) {
    Colors("Colors"),
    Patterns("Patterns"),
}

/**
 * The launcher icons Herald comes with. [Blue] is the brand icon every install starts with; the colors are the
 * accents', and the patterns put the outlined mark over a print.
 */
enum class AppIcon(val label: String, val group: AppIconGroup) {
    Blue("Blue", AppIconGroup.Colors),
    Violet("Violet", AppIconGroup.Colors),
    Green("Green", AppIconGroup.Colors),
    Orange("Orange", AppIconGroup.Colors),
    Pink("Pink", AppIconGroup.Colors),
    Teal("Teal", AppIconGroup.Colors),
    Hearts("Hearts", AppIconGroup.Patterns),
    Marks("Marks", AppIconGroup.Patterns),
    Bubbles("Bubbles", AppIconGroup.Patterns),
}

/** Which launcher icon Herald shows, and switching it. */
interface AppIcons {
    val current: AppIcon

    /** [icon] as the launcher draws it, [sizePx] square, in the phone's own icon shape; null if it can't be drawn. */
    suspend fun picture(icon: AppIcon, sizePx: Int): ImageBitmap?

    /** Shows [icon] in the launcher instead of [current], and opens Herald again from it. */
    fun apply(icon: AppIcon)
}

/** Reads the icon again after [AppIcons.apply], so [AppIcons.current] follows. */
@Composable
expect fun rememberAppIcons(): AppIcons
