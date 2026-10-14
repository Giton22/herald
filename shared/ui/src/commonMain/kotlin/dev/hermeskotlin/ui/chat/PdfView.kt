package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.stroke
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** An open PDF: the shape of each page, and a way to draw one. Closed when the viewer leaves it. */
internal interface PdfPages : AutoCloseable {
    /** Each page's width over its height, in page order; never empty. */
    val aspects: List<Float>

    /** Page [index] drawn on white to fill [width] by [height] pixels, or null when it can't be (closed, out of memory). */
    suspend fun render(index: Int, width: Int, height: Int): ImageBitmap?
}

/** A PDF's bytes opened for drawing, or why they can't be. */
internal sealed interface PdfOpen {
    class Ready(val pages: PdfPages) : PdfOpen

    data class Failed(val message: String) : PdfOpen
}

/** Opens a PDF's bytes off the main thread. */
@Composable
internal expect fun rememberPdfOpener(): suspend (ByteArray) -> PdfOpen

internal const val PDF_LOCKED = "It's protected with a password. Save it, or share it to an app that asks for one."
internal const val PDF_BROKEN = "It doesn't read as a PDF: it may be damaged or cut short. Save gets the file as it is."
internal const val PDF_NO_ROOM = "The phone has no room to open it right now. Save or share it instead."

/**
 * A PDF's pages one under another, each as wide as the screen and drawn only once it scrolls near.
 * Two fingers zoom (up to [MAX_ZOOM]) around where they are; zoomed in, one finger also moves sideways.
 * Double tap zooms in, or back out. A zoomed page is drawn again, sharper, once the pinch settles.
 */
@Composable
internal fun PdfDocumentView(pages: PdfPages, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    var viewport by remember(pages) { mutableIntStateOf(0) }
    var scale by remember(pages) { mutableFloatStateOf(1f) }
    var offsetX by remember(pages) { mutableFloatStateOf(0f) }

    fun clampX(x: Float, zoom: Float) = x.coerceIn(viewport - viewport * zoom, 0f)

    // Keeps the point under [centroid] where it is while the pages grow or shrink, then moves by [pan].
    fun zoomBy(factor: Float, centroid: Offset, pan: Offset) {
        val next = (scale * factor).coerceIn(1f, MAX_ZOOM)
        val f = next / scale
        offsetX = clampX(centroid.x + pan.x - (centroid.x - offsetX) * f, next)
        // Set as a position for the next layout, at the new size: a scroll now would be clamped to the old
        // content's end, and zooming near the last page would drift. The gaps between pages don't grow.
        val into = (list.firstVisibleItemScrollOffset + centroid.y) * f - centroid.y - pan.y
        list.requestScrollToItem(list.firstVisibleItemIndex, into.roundToInt())
        scale = next
    }

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { viewport = it.width }
            // Two fingers belong to the zoom: taken before the list sees them, so it doesn't scroll as well.
            .pointerInput(pages) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            zoomBy(event.calculateZoom(), event.calculateCentroid(useCurrent = true), event.calculatePan())
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(pages) {
                detectTapGestures(onDoubleTap = { at -> zoomBy((if (scale > 1f) 1f else DOUBLE_TAP_ZOOM) / scale, at, Offset.Zero) })
            }
            .draggable(
                rememberDraggableState { dx -> offsetX = clampX(offsetX + dx, scale) },
                Orientation.Horizontal,
                enabled = scale > 1f,
            ),
    ) {
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                // As wide as the zoom makes it, shifted sideways; the box clips what falls outside.
                .layout { measurable, constraints ->
                    val width = (constraints.maxWidth * scale).roundToInt()
                    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                    layout(constraints.maxWidth, placeable.height) { placeable.place(offsetX.roundToInt(), 0) }
                },
        ) {
            items(pages.aspects.size) { index -> PdfPage(pages, index, count = pages.aspects.size) }
        }
    }
}

@Composable
private fun PdfPage(pages: PdfPages, index: Int, count: Int) {
    val aspect = pages.aspects[index].takeIf { it.isFinite() && it > 0f }?.coerceIn(0.1f, 10f) ?: (1f / 1.414f)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .background(Color.White)
            .border(1.dp, Theme[colors][stroke])
            .semantics { contentDescription = "Page ${index + 1} of $count" },
    ) {
        val target = renderWidth(constraints.maxWidth, aspect)
        var bitmap by remember(pages, index) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(pages, index, target) {
            // A page already on screen waits for the pinch to settle before it's drawn again.
            if (bitmap != null) delay(RERENDER_DELAY_MS)
            // Drawn in the proportions it's laid out in, so an extreme page is squeezed, never oversized.
            pages.render(index, target, (target / aspect).roundToInt().coerceAtLeast(1))?.let { bitmap = it }
        }
        bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
    }
}

/**
 * How many pixels wide to draw a page shown [shown] pixels wide: in steps, so a pinch doesn't redraw it
 * every frame, and within [MAX_PAGE_PIXELS] so a tall page or a deep zoom can't run the phone out of memory.
 */
internal fun renderWidth(shown: Int, aspect: Float): Int {
    val stepped = ((shown + RENDER_STEP - 1) / RENDER_STEP) * RENDER_STEP
    val fit = sqrt(MAX_PAGE_PIXELS * aspect.toDouble()).toInt()
    return stepped.coerceAtMost(fit).coerceAtLeast(1)
}

private const val MAX_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val RENDER_STEP = 256
private const val RERENDER_DELAY_MS = 200L

/** About 32 MB as ARGB: a page's sharpness, a few times over, without risking the heap. */
private const val MAX_PAGE_PIXELS = 8_000_000.0
