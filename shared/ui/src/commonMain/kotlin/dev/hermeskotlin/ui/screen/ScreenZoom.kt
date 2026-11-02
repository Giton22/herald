package dev.hermeskotlin.ui.screen

/**
 * How much closer the screen can be looked at.
 *
 * The steps are what the pane's buttons offer, from the whole screen to eight times closer. A pinch
 * may leave the view anywhere between two of them, and the buttons go to the step that is next.
 */
internal val SCREEN_ZOOM_STEPS = listOf(1f, 1.5f, 2f, 3f, 4f, 6f, 8f)

/** The zoom the screen may be looked at: from the whole screen to the closest there is. */
internal fun screenZoom(factor: Float): Float = factor.coerceIn(1f, SCREEN_ZOOM_STEPS.last())

/** The zoom one step closer than [current]. */
internal fun screenZoomIn(current: Float): Float =
    SCREEN_ZOOM_STEPS.firstOrNull { it > current + 0.001f } ?: SCREEN_ZOOM_STEPS.last()

/** The zoom one step further out than [current]; the whole screen is the far end. */
internal fun screenZoomOut(current: Float): Float =
    SCREEN_ZOOM_STEPS.lastOrNull { it < current - 0.001f } ?: SCREEN_ZOOM_STEPS.first()
