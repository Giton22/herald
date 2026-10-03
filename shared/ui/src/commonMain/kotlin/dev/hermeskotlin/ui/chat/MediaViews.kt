package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.File
import com.composables.icons.lucide.ImageOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.X
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ReplyMedia
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Loads a picture or file the chat shows by source (see `ChatViewModel.loadMedia`); null when unavailable. */
internal val LocalMediaLoader = staticCompositionLocalOf<suspend (String) -> ByteArray?> { { null } }

/** Opens the full-screen viewer for a picture. */
internal val LocalOpenImage = staticCompositionLocalOf<(ViewerImage) -> Unit> { {} }

/** What the viewer shows: bytes already at hand, or a source to load them from. */
internal class ViewerImage(val name: String, val source: String? = null, val bytes: ByteArray? = null)

/** The pictures and files a reply delivered, under its text. */
@Composable
internal fun ReplyMediaList(media: List<ReplyMedia>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        media.forEach { item -> if (item.isImage) ReplyImage(item) else ReplyFile(item) }
    }
}

@Composable
private fun ReplyImage(item: ReplyMedia) {
    val load = LocalMediaLoader.current
    val open = LocalOpenImage.current
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    val bytes by produceState<ByteArray?>(null, item.source) { value = load(item.source) }
    var failed by remember(item.source) { mutableStateOf(false) }
    LaunchedEffect(item.source) {
        // A gateway that won't hand the file over shouldn't leave a spinner forever.
        delay(20_000)
        if (bytes == null) failed = true
    }
    val bitmap = bytes?.let { rememberImageBitmap(it, maxEdge = INLINE_EDGE) }
    Box(
        Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .background(Theme[colors][surface])
            .clickable(enabled = bitmap != null) { open(ViewerImage(item.name, source = item.source)) },
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap,
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)).heightIn(max = 480.dp),
            )
            failed || (bytes != null) -> Row(
                Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UnstyledIcon(Lucide.ImageOff, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(18.dp))
                Text("Couldn't load ${item.name}", style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
            }
            else -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Spinner() }
        }
    }
}

/** A delivered file: its name, with Save and Share. */
@Composable
private fun ReplyFile(item: ReplyMedia) {
    val load = LocalMediaLoader.current
    val actions = rememberMediaActions()
    val notify = LocalNotice.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])

    fun withBytes(action: suspend (ByteArray) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            val bytes = load(item.source)
            if (bytes == null) notify("Couldn't get ${item.name} from the gateway.") else action(bytes)
            busy = false
        }
    }

    Row(
        Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, Theme[colors][stroke], shape)
            .background(Theme[colors][surface])
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        UnstyledIcon(Lucide.File, contentDescription = null, tint = Theme[colors][textTertiary], modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = Theme[typography][label], color = Theme[colors][textColor], maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.name.substringAfterLast('.', "file").uppercase(), style = Theme[typography][caption], color = Theme[colors][textTertiary])
        }
        if (busy) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Spinner(Modifier.size(18.dp)) }
        } else {
            IconButton(Lucide.Download, contentDescription = "Save ${item.name}", onClick = { withBytes { notify(actions.save(it, item.name)) } })
            IconButton(Lucide.Share2, contentDescription = "Share ${item.name}", onClick = { withBytes { actions.share(it, item.name) } })
        }
    }
}

/** Shows a short message over the chat (a save result, a failed download). */
internal val LocalNotice = staticCompositionLocalOf<(String) -> Unit> { {} }

/** Full-screen picture with pinch-zoom (double-tap resets), Save and Share. */
@Composable
internal fun ImageViewer(image: ViewerImage, onDismiss: () -> Unit) {
    val load = LocalMediaLoader.current
    val actions = rememberMediaActions()
    val scope = rememberCoroutineScope()
    val bytes by produceState(image.bytes, image) { if (value == null) value = image.source?.let { load(it) } }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2_500)
            notice = null
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val bitmap = bytes?.let { rememberImageBitmap(it, maxEdge = VIEWER_EDGE) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = image.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = {
                                scale = if (scale > 1f) 1f else 2.5f
                                offset = Offset.Zero
                            })
                        }
                        .transformable(transform)
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(Lucide.X, contentDescription = "Close", onClick = onDismiss, tint = Color.White)
                Text(
                    image.name,
                    style = Theme[typography][label],
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                val ready = bytes
                IconButton(
                    Lucide.Download,
                    contentDescription = "Save",
                    onClick = { ready?.let { scope.launch { notice = actions.save(it, image.name.withImageExtension(it)) } } },
                    enabled = ready != null,
                    tint = Color.White,
                )
                IconButton(
                    Lucide.Share2,
                    contentDescription = "Share",
                    onClick = { ready?.let { actions.share(it, image.name.withImageExtension(it)) } },
                    enabled = ready != null,
                    tint = Color.White,
                )
            }
            notice?.let {
                Text(
                    it,
                    style = Theme[typography][bodySmall],
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(24.dp)
                        .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(Theme[radii][radiusMedium]))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** History and replies don't always carry an extension ("Image"); sniff one so other apps open the file. */
private fun String.withImageExtension(bytes: ByteArray): String {
    if ('.' in this) return this
    val ext = when {
        bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
        bytes.size > 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "gif"
        bytes.size > 11 && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() -> "webp"
        else -> "jpg"
    }
    return "$this.$ext"
}

/** Long edge of a reply's inline picture: sharp on a phone, light on memory. */
private const val INLINE_EDGE = 1280

/** Long edge in the viewer, enough to zoom into text. */
private const val VIEWER_EDGE = 3072
