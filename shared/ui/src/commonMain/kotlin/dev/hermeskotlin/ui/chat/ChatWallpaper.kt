package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.settings.WallpaperStore
import dev.hermeskotlin.core.settings.WallpaperStrength
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.ui.settings.rememberWallpaperBitmap
import org.koin.compose.koinInject

/** The chat background chosen in Settings, decoded; null when there's none. */
@Composable
internal fun rememberChatWallpaper(): ImageBitmap? {
    val wallpaper by koinInject<WallpaperStore>().wallpaper.collectAsStateWithLifecycle()
    return rememberWallpaperBitmap(wallpaper?.image)
}

/** [image] cropped to fill, under a veil of the theme's background so the conversation stays readable on any photo. */
@Composable
internal fun ChatWallpaper(image: ImageBitmap, strength: WallpaperStrength, modifier: Modifier = Modifier) {
    Box(modifier) {
        Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Theme[colors][background].copy(alpha = strength.veil)))
    }
}
