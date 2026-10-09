package dev.hermeskotlin.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import com.composeunstyled.theme.ThemeProperty
import com.composeunstyled.theme.ThemeToken

/*
 * Design tokens. Components read these through `Theme[colors][...]` — never raw literals.
 * Values are tuned in HermesTheme.kt; the look is the Herald "modern" system: graphite surfaces that step
 * up in lightness instead of casting shadows, one Herald blue, generous radii and Geist-sized type.
 */

val colors = ThemeProperty<Color>("colors")

/** App backdrop. */
val background = ThemeToken<Color>("background")

/** Default panel/card surface, one step above [background]. */
val surface = ThemeToken<Color>("surface")

/** One step above [surface]: rows and controls inside a card, the pressed state of a surface. */
val surface2 = ThemeToken<Color>("surface_2")

/** Two steps above [surface]: selected rows, the pressed state of [surface2]. */
val surface3 = ThemeToken<Color>("surface_3")

/** A recessed fill for code, commands and terminal output. */
val well = ThemeToken<Color>("well")

/** The raised pick in a segmented control's [well] track. */
val thumb = ThemeToken<Color>("thumb")

/** Floating panels: sheets, dialogs, menus. */
val surfaceElevated = ThemeToken<Color>("surface_elevated")

/** Text inputs. */
val input = ThemeToken<Color>("input")

/** The sessions drawer. */
val sidebar = ThemeToken<Color>("sidebar")

/** The fill and outline of the user's own messages, and the text on them. */
val userBubble = ThemeToken<Color>("user_bubble")
val userBubbleStroke = ThemeToken<Color>("user_bubble_stroke")
val onUserBubble = ThemeToken<Color>("on_user_bubble")

val text = ThemeToken<Color>("text")
val textSecondary = ThemeToken<Color>("text_secondary")
val textTertiary = ThemeToken<Color>("text_tertiary")

/** Below [textTertiary]: placeholders, disabled labels, the quietest metadata. */
val textMuted = ThemeToken<Color>("text_muted")

/** The accent as a fill: primary buttons, toggles, progress, the user's bubble. Text on it is [onAccent]. */
val accent = ThemeToken<Color>("accent")
val onAccent = ThemeToken<Color>("on_accent")

/** The accent as text or an icon on the background: links, selected labels, checkmarks. */
val accentText = ThemeToken<Color>("accent_text")

/** Tinted fill for selected rows, secondary buttons and accent icon circles. */
val accentSoft = ThemeToken<Color>("accent_soft")

/** The highest-stakes confirm ("Allow once"): a pill that stands out from the blue. */
val inverse = ThemeToken<Color>("inverse")
val onInverse = ThemeToken<Color>("on_inverse")

/** The one hairline used to separate or outline. */
val stroke = ThemeToken<Color>("stroke")

/** Stronger hairline for focused inputs and outlined controls. */
val strokeStrong = ThemeToken<Color>("stroke_strong")

val danger = ThemeToken<Color>("danger")
val success = ThemeToken<Color>("success")
val warning = ThemeToken<Color>("warning")

/** Tints behind a status glyph: the 20–36dp circle or square around it, never a large fill. */
val dangerSoft = ThemeToken<Color>("danger_soft")
val successSoft = ThemeToken<Color>("success_soft")
val warningSoft = ThemeToken<Color>("warning_soft")

val radii = ThemeProperty<Dp>("radii")

/** Inline code and tiny marks. */
val radiusXSmall = ThemeToken<Dp>("radius_x_small")

/** Icon buttons and small tiles. */
val radiusSmall = ThemeToken<Dp>("radius_small")

/** Code blocks and cards in lists. */
val radiusMedium = ThemeToken<Dp>("radius_medium")

/** Cards and message bubbles. */
val radiusLarge = ThemeToken<Dp>("radius_large")

/** The composer and sheets. */
val radiusXLarge = ThemeToken<Dp>("radius_x_large")

/** Pills: buttons, chips, toggles. */
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
