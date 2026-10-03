package dev.hermeskotlin.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app mark, an H whose two halves reach for each other, as an icon (tint it like any other).
 * The launcher icon (ic_launcher_foreground.xml) draws the same shapes in the 108dp adaptive grid.
 */
val HeraldMark: ImageVector by lazy {
    ImageVector.Builder(name = "HeraldMark", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 56f, viewportHeight = 56f)
        .apply {
            listOf(
                "M12.5,4a5.5,5.5 0,0 1,5.5 5.5v37a5.5,5.5 0,0 1,-5.5 5.5a5.5,5.5 0,0 1,-5.5 -5.5v-37a5.5,5.5 0,0 1,5.5 -5.5z",
                "M43.5,4a5.5,5.5 0,0 1,5.5 5.5v37a5.5,5.5 0,0 1,-5.5 5.5a5.5,5.5 0,0 1,-5.5 -5.5v-37a5.5,5.5 0,0 1,5.5 -5.5z",
                "M12,17h18a5,5 0,0 1,5 5a5,5 0,0 1,-5 5h-18a5,5 0,0 1,-5 -5a5,5 0,0 1,5 -5z",
                "M26,29h18a5,5 0,0 1,5 5a5,5 0,0 1,-5 5h-18a5,5 0,0 1,-5 -5a5,5 0,0 1,5 -5z",
            ).forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black)) }
        }
        .build()
}
