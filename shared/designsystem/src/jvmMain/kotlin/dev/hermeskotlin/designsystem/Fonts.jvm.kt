package dev.hermeskotlin.designsystem

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

// The same .ttf files as the Android resources, on the desktop classpath (see the build file).

internal actual val Geist = FontFamily(
    Font("geist_regular.ttf", FontWeight.Normal),
    Font("geist_medium.ttf", FontWeight.Medium),
    Font("geist_semibold.ttf", FontWeight.SemiBold),
)

internal actual val GeistMono = FontFamily(
    Font("geist_mono_regular.ttf", FontWeight.Normal),
    Font("geist_mono_medium.ttf", FontWeight.Medium),
)
