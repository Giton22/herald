package dev.hermeskotlin.desktop.web

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefRendering
import org.cef.callback.CefDragData
import org.cef.handler.CefRenderHandler
import org.cef.handler.CefScreenInfo
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.awt.Cursor
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A Chromium page drawn off screen: Chromium paints into memory and hands each frame over, and Compose draws it like
 * any picture, so the app's menus, tooltips and scrolling stay over and around it as over anything else. Sizes
 * Chromium sees are in the screen's scaled units (dp here); its frames are in pixels.
 */
internal class OffscreenBrowser(
    client: CefClient,
    url: String,
    transparent: Boolean,
) : CefRenderHandler {
    /** The page's latest frame, and the open `<select>` list's, which Chromium paints apart, at [popupAt]. */
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set
    var popup by mutableStateOf<ImageBitmap?>(null)
        private set
    var popupAt by mutableStateOf(Rectangle())
        private set

    /** The pointer's shape over the page: an AWT cursor type, as Chromium names them here. */
    var cursor by mutableIntStateOf(Cursor.DEFAULT_CURSOR)
        private set

    /** The page's title; a widget reports how far it's scrolled in it. */
    var title by mutableStateOf<String?>(null)
        internal set

    @Volatile private var view = Rectangle(0, 0, 1, 1)
    @Volatile private var scale = 1.0
    @Volatile private var origin = Point()

    // Chromium paints on one thread of its own.
    private var frameBytes = ByteArray(0)
    private var popupBytes = ByteArray(0)

    val browser: CefBrowser = client.createBrowser(url, CefRendering.CefRenderingWithHandler(this, null), transparent, null).also {
        it.createImmediately()
    }

    /** The view's size and place, in dp, and the pixels in a dp; Chromium redraws at the new size. */
    fun place(size: IntSize, at: IntOffset, density: Float) {
        val resized = size.width != view.width || size.height != view.height || density.toDouble() != scale
        view = Rectangle(0, 0, size.width.coerceAtLeast(1), size.height.coerceAtLeast(1))
        origin = Point(at.x, at.y)
        if (density.toDouble() != scale) {
            scale = density.toDouble()
            browser.notifyScreenInfoChanged()
        }
        if (resized) browser.wasResized(view.width, view.height)
    }

    override fun getViewRect(browser: CefBrowser): Rectangle = view

    override fun getScreenInfo(browser: CefBrowser, info: CefScreenInfo): Boolean {
        val screen = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds }
            .getOrDefault(view)
        // The room for a <select> list is the view's own, so one near its bottom opens upward instead of past the
        // edge, where nothing would show it or take its clicks.
        info.Set(scale, 32, 8, false, screen, Rectangle(origin.x, origin.y, view.width, view.height))
        return true
    }

    override fun getScreenPoint(browser: CefBrowser, viewPoint: Point): Point = Point(origin.x + viewPoint.x, origin.y + viewPoint.y)

    override fun getDeviceScaleFactor(browser: CefBrowser): Double = scale

    override fun onPopupShow(browser: CefBrowser, show: Boolean) {
        if (!show) popup = null
    }

    override fun onPopupSize(browser: CefBrowser, size: Rectangle) {
        // Kept inside the view all the same, as Chromium's own client does.
        popupAt = Rectangle(
            size.x.coerceAtMost(view.width - size.width).coerceAtLeast(0),
            size.y.coerceAtMost(view.height - size.height).coerceAtLeast(0),
            size.width,
            size.height,
        )
    }

    override fun onPaint(browser: CefBrowser, popup: Boolean, dirtyRects: Array<out Rectangle>, buffer: ByteBuffer, width: Int, height: Int) {
        // The buffer is Chromium's and only good during this call: each frame is copied into a picture of its own,
        // which Compose may still be drawing while the next one comes. The copy out of Chromium's buffer reuses one
        // array (the picture takes its own copy of it).
        val size = width * height * 4
        val bytes = (if (popup) popupBytes else frameBytes).takeIf { it.size == size } ?: ByteArray(size)
        if (popup) popupBytes = bytes else frameBytes = bytes
        buffer.duplicate().rewind().get(bytes)
        val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
        val bitmap = Bitmap().apply {
            installPixels(info, bytes, width * 4)
            setImmutable()
        }
        if (popup) this.popup = bitmap.asComposeImageBitmap() else frame = bitmap.asComposeImageBitmap()
    }

    override fun onCursorChange(browser: CefBrowser, cursorType: Int): Boolean {
        cursor = cursorType
        return true
    }

    override fun startDragging(browser: CefBrowser, dragData: CefDragData, mask: Int, x: Int, y: Int): Boolean = false

    override fun updateDragCursor(browser: CefBrowser, operation: Int) = Unit

    fun close() {
        browser.close(true)
    }
}

/**
 * Draws [page] and hands it the pointer and, once clicked, the keyboard. [keepsWheel] says whether a turn of the
 * wheel (down or up) is the page's; one it isn't goes on to what's around it, a chat that scrolls on past a widget.
 */
private const val WHEEL_NOTCH = 120

/**
 * Whether a key the page was given goes on to the app too: Escape (closing what's open) and the app's own shortcuts,
 * Ctrl or Cmd with anything but the keys text editing in the page needs. Without this, a clicked page would keep the
 * keyboard until something else was clicked.
 */
internal fun reachesApp(event: KeyEvent): Boolean {
    if (event.keyCode == KeyEvent.VK_ESCAPE) return true
    if (!event.isControlDown && !event.isMetaDown) return false
    return event.keyCode !in PAGE_EDITING_KEYS
}

private val PAGE_EDITING_KEYS = setOf(
    KeyEvent.VK_A, KeyEvent.VK_C, KeyEvent.VK_V, KeyEvent.VK_X, KeyEvent.VK_Z, KeyEvent.VK_Y,
    KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_HOME, KeyEvent.VK_END,
    KeyEvent.VK_BACK_SPACE, KeyEvent.VK_DELETE, KeyEvent.VK_CONTROL, KeyEvent.VK_META, KeyEvent.VK_SHIFT, KeyEvent.VK_ALT,
)

@Composable
internal fun BrowserSurface(page: OffscreenBrowser, modifier: Modifier = Modifier, keepsWheel: (down: Boolean) -> Boolean = { true }) {
    val density = LocalDensity.current.density
    val focus = remember { FocusRequester() }
    val wheel by rememberUpdatedState(keepsWheel)
    // Chromium wants an AWT component as each event's source; it reads only the event's own fields.
    val source = remember { java.awt.Canvas() }
    DisposableEffect(page) { onDispose { page.close() } }
    Box(
        modifier
            .onGloballyPositioned { coordinates ->
                val size = coordinates.size
                val screen = coordinates.positionOnScreen()
                // A window on its way out has no place on the screen any more.
                if (screen.x.isNaN() || screen.y.isNaN()) return@onGloballyPositioned
                page.place(
                    IntSize((size.width / density).roundToInt(), (size.height / density).roundToInt()),
                    IntOffset((screen.x / density).roundToInt(), (screen.y / density).roundToInt()),
                    density,
                )
            }
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(page.cursor.coerceIn(0, Cursor.MOVE_CURSOR))))
            // Keyed on the density too: the window moved to a screen with another scale reads positions anew.
            .pointerInput(page, density) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val x = (change.position.x / density).roundToInt()
                        val y = (change.position.y / density).roundToInt()
                        when (val awt = event.awtEventOrNull) {
                            is MouseWheelEvent -> {
                                val sideways = awt.isShiftDown || abs(change.scrollDelta.x) > abs(change.scrollDelta.y)
                                if (sideways || wheel(awt.preciseWheelRotation > 0)) {
                                    // JCEF hands Chromium the event's units to scroll as its delta, which Chromium
                                    // reads as Windows' wheel units: 120 a notch, not AWT's 3 lines. A touchpad's
                                    // part notches keep their size.
                                    val delta = (awt.preciseWheelRotation * WHEEL_NOTCH).roundToInt()
                                    if (delta != 0) page.browser.sendMouseWheelEvent(
                                        MouseWheelEvent(source, awt.id, awt.`when`, awt.modifiersEx, x, y, awt.xOnScreen, awt.yOnScreen,
                                            0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, delta, delta.toDouble()),
                                    )
                                    change.consume()
                                }
                            }
                            is MouseEvent -> {
                                if (event.type == PointerEventType.Press) runCatching { focus.requestFocus() }
                                page.browser.sendMouseEvent(
                                    MouseEvent(source, awt.id, awt.`when`, awt.modifiersEx, x, y, awt.xOnScreen, awt.yOnScreen,
                                        awt.clickCount, awt.isPopupTrigger, awt.button),
                                )
                                if (event.type == PointerEventType.Press || event.type == PointerEventType.Release) change.consume()
                            }
                            else -> Unit
                        }
                    }
                }
            }
            .onFocusChanged { page.browser.setFocus(it.isFocused) }
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                val awt = event.awtEventOrNull ?: return@onKeyEvent false
                page.browser.sendKeyEvent(awt)
                !reachesApp(awt)
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            page.frame?.let { drawImage(it, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())) }
            page.popup?.let { popup ->
                val at = page.popupAt
                drawImage(
                    popup,
                    dstOffset = IntOffset((at.x * density).roundToInt(), (at.y * density).roundToInt()),
                    dstSize = IntSize((at.width * density).roundToInt(), (at.height * density).roundToInt()),
                )
            }
        }
    }
}
