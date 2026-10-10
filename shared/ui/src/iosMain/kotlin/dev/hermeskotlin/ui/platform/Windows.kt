package dev.hermeskotlin.ui.platform

import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

/** The window in front, to present system sheets from. */
internal fun keyWindow(): UIWindow? =
    UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
        .let { windows -> windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull() }

/** The view controller on top of the key window, which a new sheet has to be presented from. */
internal fun topViewController(): UIViewController? {
    var top = keyWindow()?.rootViewController ?: return null
    while (true) top = top.presentedViewController ?: return top
}
