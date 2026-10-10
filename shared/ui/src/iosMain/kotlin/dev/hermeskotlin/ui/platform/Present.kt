package dev.hermeskotlin.ui.platform

import platform.UIKit.UIViewController

/** Shows [controller] over whatever is in front. False when there's no window to show it in. */
internal fun present(controller: UIViewController): Boolean {
    val top = topViewController() ?: return false
    top.presentViewController(controller, animated = true, completion = null)
    return true
}
