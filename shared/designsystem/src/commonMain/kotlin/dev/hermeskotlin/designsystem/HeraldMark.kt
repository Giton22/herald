package dev.hermeskotlin.designsystem

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The mark's own blue, the lit face of the summit, kept whatever accent the app is drawn in. */
val HeraldBrandBlue = Color(0xFF0053FD)

/**
 * The app mark, the summit at dawn, in its own colors (draw it as an image, never tinted): Mount Kyllene with a
 * lit face, a shaded face and a snowcap, the sun rising off its right shoulder, on a night sky. It is the default
 * launcher icon (ic_launcher_background.xml under ic_launcher_foreground.xml, shapes in launcher_icons.xml) as the
 * launcher shows it: the 108dp adaptive grid's middle 72, square, for the caller to clip to its shape.
 */
val HeraldMark: ImageVector by lazy {
    fun vertical(top: Long, bottom: Long, from: Float, to: Float) =
        Brush.linearGradient(listOf(Color(top), Color(bottom)), start = Offset(0f, from), end = Offset(0f, to))
    val sky = vertical(0xFF0E1C47, 0xFF23409F, 18f, 90f)
    ImageVector.Builder(name = "HeraldMark", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 72f, viewportHeight = 72f)
        .addGroup(translationX = -18f, translationY = -18f)
        .apply {
            addPath(addPathNodes("M0,0h108v108h-108z"), fill = sky)
            addPath(addPathNodes("M60,49a11,11 0,1 0,22 0a11,11 0,1 0,-22 0z"), fill = vertical(0xFFFFC877, 0xFFFF6B3D, 38f, 60f))
            // A hairline of sky round the mountain parts it from the sun.
            addPath(
                addPathNodes("M11.6,108L51,40Q54,35 57,40L96.4,108"),
                stroke = sky,
                strokeLineWidth = 4f,
                strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                addPathNodes("M11.6,108L51,40Q54,35 57,40L58,41.7L55,52L60,64L56,78L60,90L60,108Z"),
                fill = Brush.linearGradient(listOf(Color(0xFF5A90FF), Color(0xFF1F5CF2)), start = Offset(30f, 36f), end = Offset(54f, 90f)),
            )
            addPath(
                addPathNodes("M58,41.7L96.4,108L60,108L60,90L56,78L60,64L55,52Z"),
                fill = Brush.linearGradient(listOf(Color(0xFF1A4FD6), Color(0xFF0A35A6)), start = Offset(60f, 36f), end = Offset(80f, 90f)),
            )
            addPath(addPathNodes("M45.2,50L51,40Q54,35 57,40L58,41.7L55,52L52,49L48.5,53Z"), fill = SolidColor(Color.White))
            addPath(addPathNodes("M58,41.7L62.8,50L60,48.6L57.5,52.5L55,52Z"), fill = SolidColor(Color(0xFFCBD7F7)))
        }
        .clearGroup()
        .build()
}
