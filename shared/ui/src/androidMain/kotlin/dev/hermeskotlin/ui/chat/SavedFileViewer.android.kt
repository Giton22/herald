package dev.hermeskotlin.ui.chat

import android.app.DownloadManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.WrapText
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.media.FileText
import dev.hermeskotlin.core.media.GatewayFile
import dev.hermeskotlin.core.media.GatewayFileKind
import dev.hermeskotlin.designsystem.*
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.SegmentedControl
import dev.hermeskotlin.designsystem.components.Spinner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A real viewer window, not a transparent activity forwarding the notification to another app. */
class OpenSavedFileActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null) { finish(); return }
        enableEdgeToEdge()
        getSystemService(NotificationManager::class.java)?.cancel(uri.toString(), 0)
        setContent {
            HermesTheme {
                SavedFileScreen(uri, intent.getStringExtra(Intent.EXTRA_TITLE) ?: "Saved file", intent.type.orEmpty(), ::finish, ::openInAnotherApp, ::shareFile)
            }
        }
    }

    /** APK installation is handed to Android only after the user presses Install APK. */
    internal fun openInAnotherApp() {
        val uri = intent.data ?: return
        val name = intent.getStringExtra(Intent.EXTRA_TITLE).orEmpty()
        val mime = if (name.endsWith(".apk", ignoreCase = true)) APK_MIME else intent.type ?: "application/octet-stream"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .apply { clipData = ClipData.newRawUri("Saved file", uri) }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this file. It's saved in Herald's folder.", Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
        } catch (_: SecurityException) {
            Toast.makeText(this, "Couldn't open the file. It may have been moved or deleted.", Toast.LENGTH_LONG).show()
        }
    }

    internal fun shareFile() {
        val uri = intent.data ?: return
        val share = Intent(Intent.ACTION_SEND).setType(intent.type ?: "application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .apply { clipData = ClipData.newRawUri("Saved file", uri) }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(share, "Share saved file"))
        } catch (_: Exception) {
            Toast.makeText(this, "Couldn't share this file.", Toast.LENGTH_LONG).show()
        }
    }
}

private const val APK_MIME = "application/vnd.android.package-archive"
private sealed interface SavedPreview {
    data object Loading : SavedPreview
    class Pdf(val pages: PdfPages) : SavedPreview
    class Picture(val bitmap: ImageBitmap) : SavedPreview
    class TextFile(val value: FileText) : SavedPreview
    class Unavailable(val message: String) : SavedPreview
}

@Composable
private fun SavedFileScreen(uri: Uri, name: String, mime: String, onClose: () -> Unit, onOpenExternal: () -> Unit, onShare: () -> Unit) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val file = remember(name) { GatewayFile("/Saved files/$name") }
    val kind = when {
        mime == "application/pdf" -> GatewayFileKind.Pdf
        mime.startsWith("image/") && mime != "image/svg+xml" -> GatewayFileKind.Image
        else -> file.kind
    }
    val apk = mime == APK_MIME || name.endsWith(".apk", ignoreCase = true)
    val preview by produceState<SavedPreview>(SavedPreview.Loading, uri, kind) {
        val loaded: SavedPreview = when (kind) {
            GatewayFileKind.Pdf -> {
                val opened = withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        resolver.openFileDescriptor(uri, "r")?.let(::openPdfDescriptor)
                            ?: PdfOpen.Failed("The saved file is no longer available.")
                    } catch (_: Exception) { PdfOpen.Failed("Couldn't read the saved file. It may have been moved or deleted.") }
                }
                when (opened) {
                    is PdfOpen.Ready -> SavedPreview.Pdf(opened.pages)
                    is PdfOpen.Failed -> SavedPreview.Unavailable("This PDF couldn't be previewed. Try opening it in another app.")
                }
            }
            GatewayFileKind.Image -> when (val read = readSavedBytes(resolver, uri)) {
                is FileDownload.Ready -> withContext(Dispatchers.Default) { decodeImageBitmap(read.bytes, 3072) }
                    ?.let { SavedPreview.Picture(it) } ?: SavedPreview.Unavailable("This image couldn't be previewed. Try another app.")
                is FileDownload.Failed -> SavedPreview.Unavailable(read.message)
            }
            GatewayFileKind.Other -> SavedPreview.Unavailable(if (apk) "Android app package\nAndroid will ask you to approve installation." else "This format opens in another app. Your original file is saved on this phone.")
            else -> when (val read = readSavedText(resolver, uri)) {
                is FileLoad.Ready -> if (read.text.binary) SavedPreview.Unavailable("This is a binary file. Open it in a compatible app.") else SavedPreview.TextFile(read.text)
                is FileLoad.Failed -> SavedPreview.Unavailable(read.message)
                else -> SavedPreview.Unavailable("Couldn't preview this file.")
            }
        }
        if (!isActive) { (loaded as? SavedPreview.Pdf)?.pages?.close(); return@produceState }
        value = loaded
        awaitDispose { (loaded as? SavedPreview.Pdf)?.pages?.close() }
    }
    val scope = rememberCoroutineScope()
    val pdfList = rememberLazyListState()
    var raw by remember(uri) { mutableStateOf(false) }
    var wrap by remember(uri) { mutableStateOf(false) }
    val textFile = (preview as? SavedPreview.TextFile)?.value
    val pdf = preview as? SavedPreview.Pdf
    Column(Modifier.fillMaxSize().background(Theme[colors][background]).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Lucide.ArrowLeft, contentDescription = "Back", onClick = onClose)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(name, style = Theme[typography][heading], color = Theme[colors][text], maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(if (apk) "Android app" else kind.label, textFile?.let { formatBytes(it.byteSize) }, "On this phone").joinToString(" · "), style = Theme[typography][caption], color = Theme[colors][textSecondary])
            }
            IconButton(Lucide.Share2, contentDescription = "Share saved file", onClick = onShare)
        }
        if (pdf != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                val page = pdfList.firstVisibleItemIndex
                IconButton(Lucide.ChevronLeft, contentDescription = "Previous page", enabled = page > 0, onClick = { scope.launch { pdfList.animateScrollToItem(page - 1) } })
                Text("Page ${page + 1} of ${pdf.pages.aspects.size} · Pinch to zoom", style = Theme[typography][caption], color = Theme[colors][textSecondary])
                IconButton(Lucide.ChevronRight, contentDescription = "Next page", enabled = page < pdf.pages.aspects.lastIndex, onClick = { scope.launch { pdfList.animateScrollToItem(page + 1) } })
            }
        }
        if (textFile != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (kind in setOf(GatewayFileKind.Markdown, GatewayFileKind.Json, GatewayFileKind.Table)) {
                    SegmentedControl(options = listOf(false, true), selected = raw, onSelect = { raw = it }, optionLabel = { if (it) "Source" else "Preview" }, modifier = Modifier.weight(1f))
                } else Spacer(Modifier.weight(1f))
                IconButton(Lucide.WrapText, contentDescription = "Toggle line wrapping", onClick = { wrap = !wrap })
                IconButton(Lucide.Copy, contentDescription = "Copy text", onClick = {
                    val clip = clipFor(textFile)
                    runCatching { context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(name, clip.text)) }
                        .onSuccess { Toast.makeText(context, clip.note ?: "Copied", Toast.LENGTH_SHORT).show() }
                })
            }
            if (textFile.truncated) Text("Preview shortened. Open in another app for the full file.", style = Theme[typography][caption], color = Theme[colors][textSecondary], modifier = Modifier.padding(12.dp))
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            when (val result = preview) {
                SavedPreview.Loading -> Spinner()
                is SavedPreview.Pdf -> PdfDocumentView(result.pages, Modifier.fillMaxSize(), pdfList)
                is SavedPreview.TextFile -> FileBody(file, kind, result.value, raw, wrap)
                is SavedPreview.Picture -> SavedPicture(result.bitmap, name)
                is SavedPreview.Unavailable -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    UnstyledIcon(kindIcon(kind), contentDescription = null, tint = Theme[colors][accent], modifier = Modifier.size(48.dp))
                    Text(result.message, style = Theme[typography][bodySmall], color = Theme[colors][textSecondary], textAlign = TextAlign.Center)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) {
            Button(if (apk) "Install APK" else "Open in another app", onClick = onOpenExternal, leadingIcon = Lucide.ExternalLink, variant = ButtonVariant.Secondary, size = ButtonSize.Small)
        }
    }
}

@Composable
private fun SavedPicture(bitmap: ImageBitmap, name: String) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Image(bitmap, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
        .pointerInput(bitmap) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero }) }
        .transformable(transform).graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))
}
