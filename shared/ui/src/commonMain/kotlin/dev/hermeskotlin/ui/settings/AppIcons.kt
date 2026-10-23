package dev.hermeskotlin.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import dev.hermeskotlin.ui.resources.Res
import org.jetbrains.compose.resources.StringResource

/** The rows of the app icon picker. */
enum class AppIconGroup(val titleRes: StringResource) {
    Colors(Res.string.app_icon_group_colors),
    Patterns(Res.string.app_icon_group_patterns),
}

/**
 * The launcher icons Herald comes with. [Blue] is the brand icon every install starts with; the colors are the
 * accents', and the patterns put the outlined mark over a print.
 */
enum class AppIcon(val labelRes: StringResource, val group: AppIconGroup) {
    Blue(Res.string.app_icon_blue, AppIconGroup.Colors),
    Violet(Res.string.app_icon_violet, AppIconGroup.Colors),
    Green(Res.string.app_icon_green, AppIconGroup.Colors),
    Orange(Res.string.app_icon_orange, AppIconGroup.Colors),
    Pink(Res.string.app_icon_pink, AppIconGroup.Colors),
    Teal(Res.string.app_icon_teal, AppIconGroup.Colors),
    Hearts(Res.string.app_icon_hearts, AppIconGroup.Patterns),
    Marks(Res.string.app_icon_marks, AppIconGroup.Patterns),
    Bubbles(Res.string.app_icon_bubbles, AppIconGroup.Patterns),
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
