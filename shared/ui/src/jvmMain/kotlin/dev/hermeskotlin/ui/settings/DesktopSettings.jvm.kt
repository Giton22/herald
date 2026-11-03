package dev.hermeskotlin.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import dev.hermeskotlin.core.phone.PhoneTool
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** The desktop keeps the one icon its installer gives it. */
@Composable
actual fun rememberAppIcons(): AppIcons = remember {
    object : AppIcons {
        override val current = AppIcon.Blue

        override suspend fun picture(icon: AppIcon, sizePx: Int): ImageBitmap? = null

        override fun apply(icon: AppIcon) = Unit
    }
}

// App lock has no system lock to fall back to on the desktop.
@Composable
actual fun deviceHasScreenLock(): Boolean = false

/** The phone tools are the phone's: on the desktop each one says so. */
@Composable
actual fun rememberPhoneToolAccess(): PhoneToolAccess {
    val needsPhone = stringResource(Res.string.desktop_phone_tools_need_phone)
    return remember(needsPhone) { DesktopPhoneToolAccess(needsPhone) }
}

private class DesktopPhoneToolAccess(private val needsPhone: String) : PhoneToolAccess {
    override fun missing(tool: PhoneTool) = needsPhone

    override fun request(tool: PhoneTool) = Unit
}
