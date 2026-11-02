package dev.hermeskotlin.designsystem

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the components lay out for a desktop window, a mouse and a keyboard: sheets open as dialogs in the
 * middle of the window, rows sit tighter, icon buttons name themselves on hover. The app provides it.
 */
val LocalDesktopLayout = staticCompositionLocalOf { false }
