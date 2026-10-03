package dev.hermeskotlin.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import com.composeunstyled.theme.ThemeProperty
import com.composeunstyled.theme.ThemeToken

/*
 * Design tokens. Components read these through `Theme[colors][...]` — never raw literals.
 * Values are tuned in HermesTheme.kt; the look follows Hermes Desktop's default "Nous" skin
 * (GitHub neutrals, Nous blue, near-square corners, spaced capitals), not its assets or fonts.
 */

val colors = ThemeProperty<Color>("colors")

/** App backdrop. */
val background = ThemeToken<Color>("background")

/** Default panel/card surface, one step above [background]. */
val surface = ThemeToken<Color>("surface")

/** Floating panels: sheets, dialogs, menus. */
val surfaceElevated = ThemeToken<Color>("surface_elevated")

/** Text inputs. */
val input = ThemeToken<Color>("input")

/** The sessions drawer, a step darker than the chat in dark mode (Desktop's sidebar). */
val sidebar = ThemeToken<Color>("sidebar")

/** The boxed fill and outline of the user's own messages. */
val userBubble = ThemeToken<Color>("user_bubble")
val userBubbleStroke = ThemeToken<Color>("user_bubble_stroke")

val text = ThemeToken<Color>("text")
val textSecondary = ThemeToken<Color>("text_secondary")
val textTertiary = ThemeToken<Color>("text_tertiary")

val accent = ThemeToken<Color>("accent")
val onAccent = ThemeToken<Color>("on_accent")

/** Tinted fill for selected rows, secondary buttons, user bubbles. */
val accentSoft = ThemeToken<Color>("accent_soft")

/** The one hairline used to separate or outline. */
val stroke = ThemeToken<Color>("stroke")

/** Stronger hairline for focused inputs and outlined controls. */
val strokeStrong = ThemeToken<Color>("stroke_strong")

val danger = ThemeToken<Color>("danger")
val success = ThemeToken<Color>("success")
val warning = ThemeToken<Color>("warning")

val radii = ThemeProperty<Dp>("radii")
val radiusSmall = ThemeToken<Dp>("radius_small")
val radiusMedium = ThemeToken<Dp>("radius_medium")
val radiusLarge = ThemeToken<Dp>("radius_large")
val radiusFull = ThemeToken<Dp>("radius_full")

val typography = ThemeProperty<TextStyle>("typography")
val display = ThemeToken<TextStyle>("display")
val title = ThemeToken<TextStyle>("title")
val heading = ThemeToken<TextStyle>("heading")
val body = ThemeToken<TextStyle>("body")
val bodySmall = ThemeToken<TextStyle>("body_small")
val label = ThemeToken<TextStyle>("label")
val caption = ThemeToken<TextStyle>("caption")
val code = ThemeToken<TextStyle>("code")

/** Small spaced capitals for section labels ("SESSIONS"); set the text in upper case. */
val eyebrow = ThemeToken<TextStyle>("eyebrow")

/** Heavy spaced capitals for the empty-chat lettering; sized by the caller to fit. */
val wordmark = ThemeToken<TextStyle>("wordmark")
