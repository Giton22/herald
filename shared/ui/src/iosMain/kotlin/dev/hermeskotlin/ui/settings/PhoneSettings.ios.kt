package dev.hermeskotlin.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import dev.hermeskotlin.core.phone.PhoneTool
import dev.hermeskotlin.ui.resources.Res
import dev.hermeskotlin.ui.resources.phone_need_android

/** The phone tools are Android's (SMS, calls, contacts and the like): none of them works on iOS. */
@Composable
actual fun rememberPhoneToolAccess(): PhoneToolAccess = remember {
    object : PhoneToolAccess {
        override fun missing(tool: PhoneTool) = Res.string.phone_need_android

        override fun request(tool: PhoneTool) {}
    }
}

/** Herald on iOS has its one icon for now: the alternate icons need their own pictures in the app. */
@Composable
actual fun rememberAppIcons(): AppIcons = remember {
    object : AppIcons {
        override val current = AppIcon.Blue

        override suspend fun picture(icon: AppIcon, sizePx: Int): ImageBitmap? = null

        override fun apply(icon: AppIcon) {}
    }
}
