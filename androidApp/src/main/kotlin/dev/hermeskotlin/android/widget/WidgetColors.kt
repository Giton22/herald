package dev.hermeskotlin.android.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.toArgb
import dev.hermeskotlin.android.R
import dev.hermeskotlin.designsystem.AccentPalette

/**
 * The colors one widget render draws with (docs/WIDGETS-DESIGN.md §3): the neutral and semantic
 * roles come from `values/colors.xml` / `values-night/colors.xml` (widgets don't inherit the app
 * theme), and the accent comes from `AccentPalette.named(settings.accent)`, resolved once per
 * render. The accent pill/action backgrounds can't be set as plain colors without losing their
 * corner radius, so the six presets exist as small shape drawables per theme and this picks one.
 */
class WidgetColors(private val context: Context, accentName: String?) {

    private val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES

    private val palette = AccentPalette.named(accentName)
    private val accentColors = (if (night) palette.dark else palette.light)

    val accent: Int = accentColors.accent.toArgb()
    val onAccent: Int = accentColors.onAccent.toArgb()
    val accentText: Int = accentColors.text.toArgb()
    val accentSoft: Int = accentColors.soft.toArgb()

    val surface: Int = context.getColor(R.color.widget_surface)
    val surface2: Int = context.getColor(R.color.widget_surface_2)
    val well: Int = context.getColor(R.color.widget_well)
    val text: Int = context.getColor(R.color.widget_text)
    val textSecondary: Int = context.getColor(R.color.widget_text_secondary)
    val textTertiary: Int = context.getColor(R.color.widget_text_tertiary)
    val stroke: Int = context.getColor(R.color.widget_stroke)
    val danger: Int = context.getColor(R.color.widget_danger)
    val warning: Int = context.getColor(R.color.widget_warning)
    val success: Int = context.getColor(R.color.widget_success)

    /** The accent-soft rounded background for the Running count pill and waiting accents. */
    fun accentPillBackground(): Int = when (palette.name.lowercase() to night) {
        "blue" to false -> R.drawable.widget_pill_accent_blue_light
        "blue" to true -> R.drawable.widget_pill_accent_blue_dark
        "violet" to false -> R.drawable.widget_pill_accent_violet_light
        "violet" to true -> R.drawable.widget_pill_accent_violet_dark
        "green" to false -> R.drawable.widget_pill_accent_green_light
        "green" to true -> R.drawable.widget_pill_accent_green_dark
        "orange" to false -> R.drawable.widget_pill_accent_orange_light
        "orange" to true -> R.drawable.widget_pill_accent_orange_dark
        "pink" to false -> R.drawable.widget_pill_accent_pink_light
        "pink" to true -> R.drawable.widget_pill_accent_pink_dark
        "teal" to false -> R.drawable.widget_pill_accent_teal_light
        "teal" to true -> R.drawable.widget_pill_accent_teal_dark
        else -> R.drawable.widget_pill_neutral
    }

    /** The accent-fill rounded background for the primary quick action. */
    fun accentActionBackground(): Int = when (palette.name.lowercase() to night) {
        "blue" to false -> R.drawable.widget_action_accent_blue_light
        "blue" to true -> R.drawable.widget_action_accent_blue_dark
        "violet" to false -> R.drawable.widget_action_accent_violet_light
        "violet" to true -> R.drawable.widget_action_accent_violet_dark
        "green" to false -> R.drawable.widget_action_accent_green_light
        "green" to true -> R.drawable.widget_action_accent_green_dark
        "orange" to false -> R.drawable.widget_action_accent_orange_light
        "orange" to true -> R.drawable.widget_action_accent_orange_dark
        "pink" to false -> R.drawable.widget_action_accent_pink_light
        "pink" to true -> R.drawable.widget_action_accent_pink_dark
        "teal" to false -> R.drawable.widget_action_accent_teal_light
        "teal" to true -> R.drawable.widget_action_accent_teal_dark
        else -> R.drawable.widget_action_secondary
    }
}
