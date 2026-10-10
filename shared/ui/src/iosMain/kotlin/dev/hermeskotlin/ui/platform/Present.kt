@file:OptIn(ExperimentalForeignApi::class)

package dev.hermeskotlin.ui.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectGetMidX
import platform.CoreGraphics.CGRectGetMidY
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController

/** Shows [controller] over whatever is in front. False when there's no window to show it in. */
internal fun present(controller: UIViewController): Boolean {
    val top = topViewController() ?: return false
    // On iPad a share sheet is a popover and must point at something, or UIKit throws: the screen's middle.
    controller.popoverPresentationController?.let { popover ->
        val view = top.view
        popover.sourceView = view
        val bounds = view.bounds
        popover.sourceRect = CGRectMake(CGRectGetMidX(bounds), CGRectGetMidY(bounds), 0.0, 0.0)
        popover.permittedArrowDirections = 0u
    }
    top.presentViewController(controller, animated = true, completion = null)
    return true
}
