package dev.hermeskotlin.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.Braces
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.File
import com.composables.icons.lucide.FileCode
import com.composables.icons.lucide.FileImage
import com.composables.icons.lucide.FileSpreadsheet
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.FileWarning
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.WrapText
import com.composeunstyled.Text
import com.composeunstyled.UnstyledIcon
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.media.DelimitedTable
import dev.hermeskotlin.core.media.FileText
import dev.hermeskotlin.core.media.GatewayFile
import dev.hermeskotlin.core.media.GatewayFileKind
import dev.hermeskotlin.core.media.parseDelimited
import dev.hermeskotlin.core.media.prettyJson
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.accentSoft
import dev.hermeskotlin.designsystem.accentText
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Button
import dev.hermeskotlin.designsystem.components.ButtonSize
import dev.hermeskotlin.designsystem.components.ButtonVariant
import dev.hermeskotlin.designsystem.components.CodeView
import dev.hermeskotlin.designsystem.components.IconButton
import dev.hermeskotlin.designsystem.components.LocalFileLinks
import dev.hermeskotlin.designsystem.components.LocalMarkdownPictures
import dev.hermeskotlin.designsystem.components.MarkdownPicture
import dev.hermeskotlin.designsystem.components.MarkdownText
import dev.hermeskotlin.designsystem.components.SegmentedControl
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.components.plainTextClipEntry
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.dangerSoft
import dev.hermeskotlin.designsystem.label
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.successSoft
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.surface2
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.title
import dev.hermeskotlin.designsystem.typography
import dev.hermeskotlin.designsystem.warning
import dev.hermeskotlin.designsystem.warningSoft
import dev.hermeskotlin.designsystem.well
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a file preview stands: loading, its text, or why it can't be shown. */
sealed interface FileLoad {
    data object Loading : FileLoad

    data class Ready(val text: FileText) : FileLoad

    data class Failed(val message: String) : FileLoad

    /** A PDF fetched whole and opened: its pages, drawn as they scroll near. */
    class Pdf internal constructor(internal val pages: PdfPages) : FileLoad
}

/** A linked file's whole bytes for Save and Share, or why the gateway wouldn't hand them over. */
sealed interface FileDownload {
    class Ready(val bytes: ByteArray) : FileDownload

    data class Failed(val message: String) : FileDownload
}

/**
 * A file on the gateway that a reply linked to, opened full screen over the chat: Markdown rendered, JSON
 * indented, CSV and TSV as a table, code and text with line numbers, scrolled both ways, a PDF's pages.
 * Save and Share fetch the whole file; a text preview only reads the start the gateway hands out, while
 * a PDF is fetched whole once and kept for them. A file with nothing to show (an archive, a file the
 * gateway calls binary) gets Save and Share buttons. A link in a Markdown file to another file opens it
 * here, relative ones against the file's folder; Back goes back to the file before, and closes the viewer
 * from the first one.
 */
@Composable
internal fun FileViewer(
    file: GatewayFile?,
    onDismiss: () -> Unit,
    load: suspend (GatewayFile) -> FileLoad,
    download: suspend (GatewayFile) -> FileDownload,
) {
    if (file == null) return
    // The files opened from links, the one from the reply first.
    var stack by rememberSaveable(file, stateSaver = StackSaver) { mutableStateOf(listOf(file)) }
    var picture by remember(file) { mutableStateOf<ViewerImage?>(null) }
    // Each page's scroll position, kept while a file it linked to is open on top of it.
    val pages = rememberSaveableStateHolder()
    // What the files below the top one showed, so Back finds a page as tall as it was and its scroll fits.
    val loaded = remember(file) { mutableMapOf<GatewayFile, FileLoad>() }
    val scope = rememberCoroutineScope()
    val pictures = remember(file) { LinkedPictures(scope) }
    fun pageKey(index: Int) = "$index|${stack[index].path}"
    fun back() {
        if (stack.size > 1) {
            pages.removeState(pageKey(stack.lastIndex))
            stack = stack.dropLast(1)
        } else {
            onDismiss()
        }
    }
    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val top = stack.last()
        pages.SaveableStateProvider(pageKey(stack.lastIndex)) {
            FileViewerPage(
                top,
                onDismiss = ::back,
                load = { linked -> load(linked).also { if (it is FileLoad.Ready) loaded[linked] = it } },
                download = download,
                preset = loaded[top],
                canGoBack = stack.size > 1,
                onOpenFile = { linked ->
                    when {
                        // A link to a line of this same file: rendered Markdown has no lines to go to.
                        linked.path == top.path -> Unit
                        // A picture opens in the picture viewer, like one linked from a reply.
                        linked.kind == GatewayFileKind.Image -> picture = ViewerImage(linked.name, fetch = { download(linked) })
                        else -> stack = stack + linked
                    }
                },
                onOpenPicture = { picture = it },
                pictures = pictures,
            )
        }
    }
    picture?.let { ImageViewer(it, onDismiss = { picture = null }) }
}

/** A gateway file a link opened: a picture in the picture viewer, anything else in [FileViewer]. */
@Composable
internal fun GatewayFileViewer(
    file: GatewayFile?,
    onDismiss: () -> Unit,
    load: suspend (GatewayFile) -> FileLoad,
    download: suspend (GatewayFile) -> FileDownload,
) {
    if (file?.kind == GatewayFileKind.Image) {
        val image = remember(file) { ViewerImage(file.name, fetch = { download(file) }) }
        ImageViewer(image, onDismiss)
    } else {
        FileViewer(file, onDismiss, load, download)
    }
}

/**
 * The viewer's page, apart from its window, so previews can draw it; [preset] skips loading for screenshots.
 * [onOpenFile] gets a gateway file a link in the text points at; [onOpenPicture] a picture shown in it.
 */
@Composable
internal fun FileViewerPage(
    file: GatewayFile,
    onDismiss: () -> Unit,
    load: suspend (GatewayFile) -> FileLoad,
    download: suspend (GatewayFile) -> FileDownload,
    preset: FileLoad? = null,
    canGoBack: Boolean = false,
    onOpenFile: (GatewayFile) -> Unit = {},
    onOpenPicture: (ViewerImage) -> Unit = {},
    pictures: LinkedPictures? = null,
) {
    val kind = file.kind
    val openPdf = rememberPdfOpener()
    // A PDF's whole bytes once fetched for its pages, so Save and Share don't fetch them again.
    var fetched by remember(file) { mutableStateOf<ByteArray?>(null) }
    val result by produceState(preset ?: if (kind.previewable) FileLoad.Loading else null, file, preset) {
        if (preset != null || !kind.previewable) return@produceState
        if (kind != GatewayFileKind.Pdf) {
            value = load(file)
            return@produceState
        }
        val got = download(file)
        if (got is FileDownload.Failed) {
            value = FileLoad.Failed(got.message)
            return@produceState
        }
        fetched = (got as FileDownload.Ready).bytes
        // Opened past a Back tap, so an open document is never dropped unclosed; it closes with the page.
        when (val opened = withContext(NonCancellable) { openPdf(got.bytes) }) {
            is PdfOpen.Failed -> value = FileLoad.Failed(opened.message)
            is PdfOpen.Ready -> {
                if (!isActive) {
                    opened.pages.close()
                    return@produceState
                }
                value = FileLoad.Pdf(opened.pages)
                awaitDispose { opened.pages.close() }
            }
        }
    }
    val pdf = result as? FileLoad.Pdf
    // Save and Share wait for the PDF the preview is already fetching, rather than fetch it a second time.
    val fetching = kind == GatewayFileKind.Pdf && result == FileLoad.Loading
    val ready = (result as? FileLoad.Ready)?.text?.takeUnless { it.binary }
    var wrap by remember(file) { mutableStateOf(false) }
    var raw by remember(file) { mutableStateOf(false) }
    val actions = rememberMediaActions()
    val scope = rememberCoroutineScope()
    var busy by remember(file) { mutableStateOf(false) }
    var notice by remember(file) { mutableStateOf<Notice?>(null) }
    val clipboard = LocalClipboard.current
    var copied by remember(file) { mutableStateOf(false) }

    fun withBytes(action: suspend (ByteArray) -> Unit) {
        if (busy || fetching) return
        fetched?.let { bytes ->
            scope.launch { action(bytes) }
            return
        }
        busy = true
        scope.launch {
            when (val result = download(file)) {
                is FileDownload.Ready -> action(result.bytes)
                is FileDownload.Failed -> notice = Notice("Couldn't get ${file.name}: ${result.message.replaceFirstChar { it.lowercase() }}", error = true)
            }
            busy = false
        }
    }

    val textual = ready != null && kind != GatewayFileKind.Markdown && kind != GatewayFileKind.Table || ready != null && raw

    Column(Modifier.fillMaxSize().background(Theme[colors][background]).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(Lucide.ArrowLeft, contentDescription = if (canGoBack) "Back" else "Close", onClick = onDismiss, tint = Theme[colors][textColor])
            KindTile(kind)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    file.name,
                    style = Theme[typography][title].copy(fontWeight = FontWeight.SemiBold),
                    color = Theme[colors][textColor],
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    listOfNotNull(
                        file.folder.ifEmpty { "/" },
                        (ready?.byteSize ?: fetched?.size?.toLong())?.let(::formatBytes),
                        pdf?.pages?.aspects?.size?.let { if (it == 1) "1 page" else "$it pages" },
                        file.line?.let { "line $it" },
                    ).joinToString(" · "),
                    style = Theme[typography][caption],
                    color = Theme[colors][textTertiary],
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
            if (busy || fetching) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Spinner(Modifier.size(18.dp)) }
            } else {
                IconButton(Lucide.Download, contentDescription = "Save ${file.name}", onClick = {
                    withBytes { notice = Notice(actions.save(it, file.name)) }
                })
                IconButton(Lucide.Share2, contentDescription = "Share ${file.name}", onClick = { withBytes { actions.share(it, file.name) } })
            }
        }

        // A toolbar for what the text can do: view as rendered or source, wrap, copy.
        if (ready != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // The switch takes the room the buttons leave: it fills its width and would push them off screen.
                if (kind == GatewayFileKind.Markdown || kind == GatewayFileKind.Table || kind == GatewayFileKind.Json) {
                    SegmentedControl(
                        options = listOf(false, true),
                        selected = raw,
                        onSelect = { raw = it },
                        optionLabel = { if (it) "Source" else if (kind == GatewayFileKind.Json) "Formatted" else "Preview" },
                        modifier = Modifier.weight(1f),
                        optionIcon = { if (it) Lucide.FileCode else kindIcon(kind) },
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (textual) {
                    IconButton(
                        Lucide.WrapText,
                        contentDescription = if (wrap) "Don't wrap lines" else "Wrap lines",
                        onClick = { wrap = !wrap },
                        tint = if (wrap) Theme[colors][accentText] else Theme[colors][textSecondary],
                        containerColor = if (wrap) Theme[colors][accentSoft] else Color.Transparent,
                    )
                }
                IconButton(
                    if (copied) Lucide.Check else Lucide.Copy,
                    contentDescription = "Copy the text",
                    onClick = {
                        scope.launch {
                            val clip = clipFor(ready)
                            // A clip is sent whole through the system; a failed one mustn't take the app down.
                            try {
                                clipboard.setClipEntry(plainTextClipEntry(clip.text))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                notice = Notice("Couldn't copy it to the clipboard. Save gets the whole file.", error = true)
                                return@launch
                            }
                            clip.note?.let { notice = Notice(it) }
                            copied = true
                            kotlinx.coroutines.delay(1_500)
                            copied = false
                        }
                    },
                    tint = if (copied) Theme[colors][success] else Theme[colors][textSecondary],
                )
            }
        }

        notice?.let { NoticeLine(it) }
        if (ready?.truncated == true) {
            val shownBytes = remember(ready) { ready.text.encodeToByteArray().size.toLong() }
            Banner(
                "Showing the first ${formatBytes(shownBytes)} of ${formatBytes(ready.byteSize)}. Save gets the whole file.",
                tint = Theme[colors][warning],
                background = Theme[colors][warningSoft],
            )
        }

        Box(Modifier.fillMaxWidth().weight(1f).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            AnimatedContent(result, transitionSpec = { fadeIn() togetherWith fadeOut() }, contentKey = { it?.let { r -> r::class } }) { state ->
                when {
                    state == FileLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
                    state is FileLoad.Pdf -> PdfDocumentView(state.pages, Modifier.fillMaxSize())
                    // A PDF that came but won't open (a password, damage) can still go to an app that reads it.
                    state is FileLoad.Failed -> Unavailable(
                        file,
                        kind,
                        state.message,
                        onSave = { withBytes { notice = Notice(actions.save(it, file.name)) } },
                        onShare = { withBytes { actions.share(it, file.name) } }.takeIf { fetched != null },
                    )
                    ready == null -> Unavailable(file, kind, null, onSave = { withBytes { notice = Notice(actions.save(it, file.name)) } }, onShare = {
                        withBytes { actions.share(it, file.name) }
                    })
                    else -> {
                        val shown = rememberLinkedPictures(file, pictures, download, onOpenPicture)
                        val onLink by rememberUpdatedState(onOpenFile)
                        val links = remember(file) { { link: String -> file.resolve(link)?.let { onLink(it) }; Unit } }
                        CompositionLocalProvider(
                            LocalFileLinks provides links,
                            LocalMarkdownPictures provides shown,
                        ) {
                            FileBody(file, kind, ready, raw = raw, wrap = wrap)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The pictures a viewer's Markdown files show, fetched and decoded once while it's open: the library asks for
 * a picture from more than one place (in the line, then as a block of its own once it's taller than one), and
 * Back to a file shouldn't fetch its pictures again.
 */
internal class LinkedPictures(private val scope: CoroutineScope) {
    sealed interface Entry {
        data object Loading : Entry

        class Ready(val bitmap: ImageBitmap, val bytes: ByteArray) : Entry

        data object Failed : Entry
    }

    private val entries = mutableStateMapOf<GatewayFile, Entry>()

    operator fun get(file: GatewayFile): Entry? = entries[file]

    /** Starts fetching [file] unless it's already fetched or on its way. */
    fun request(file: GatewayFile, download: suspend (GatewayFile) -> FileDownload) {
        if (file in entries) return
        entries[file] = Entry.Loading
        scope.launch {
            entries[file] = when (val result = download(file)) {
                is FileDownload.Ready -> withContext(Dispatchers.Default) { decodeImageBitmap(result.bytes, PICTURE_EDGE) }
                    ?.let { Entry.Ready(it, result.bytes) } ?: Entry.Failed
                is FileDownload.Failed -> Entry.Failed
            }
        }
    }
}

/** Shows the pictures a Markdown file links to (`![chart](chart.png)`, relative to its folder) from [cache]. */
@Composable
private fun rememberLinkedPictures(
    file: GatewayFile,
    cache: LinkedPictures?,
    download: suspend (GatewayFile) -> FileDownload,
    onOpen: (ViewerImage) -> Unit,
): @Composable (String) -> MarkdownPicture? {
    val scope = rememberCoroutineScope()
    val pictures = cache ?: remember { LinkedPictures(scope) }
    val fetch by rememberUpdatedState(download)
    val open by rememberUpdatedState(onOpen)
    return remember(file, pictures) {
        { link ->
            val target = remember(link) { file.resolve(link)?.takeIf { it.kind == GatewayFileKind.Image } }
            if (target == null) {
                null
            } else {
                LaunchedEffect(target) { pictures.request(target) { fetch(it) } }
                when (val entry = pictures[target]) {
                    null, LinkedPictures.Entry.Loading -> MarkdownPicture.Loading
                    LinkedPictures.Entry.Failed -> MarkdownPicture.Failed
                    is LinkedPictures.Entry.Ready -> remember(entry) {
                        MarkdownPicture.Ready(entry.bitmap) { open(ViewerImage(target.name, bytes = entry.bytes)) }
                    }
                }
            }
        }
    }
}

/** The viewer's files as paths and lines, so the stack outlives a recreated activity. */
private val StackSaver = listSaver<List<GatewayFile>, Any>(
    save = { stack -> stack.flatMap { listOf(it.path, it.line ?: 0) } },
    restore = { saved -> saved.chunked(2).map { (path, line) -> GatewayFile(path as String, (line as Int).takeIf { it > 0 }) } },
)

/** A picture in a Markdown file is decoded at most this many pixels on a side: wider than the screen, never a whole photo. */
private const val PICTURE_EDGE = 1600

@Composable
private fun FileBody(file: GatewayFile, kind: GatewayFileKind, text: FileText, raw: Boolean, wrap: Boolean) {
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    val framed = Modifier.fillMaxSize().clip(shape).background(Theme[colors][well]).border(1.dp, Theme[colors][stroke], shape)
    when {
        // Rendered Markdown reads as a page of its own, not a box on one.
        kind == GatewayFileKind.Markdown && !raw ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)) {
                SelectionContainer { MarkdownText(text.text) }
            }
        kind == GatewayFileKind.Table && !raw -> {
            val delimiter = if (file.extension == "tsv") '\t' else ','
            val table = remember(text) { parseDelimited(text.text, delimiter, truncated = text.truncated) }
            DataTable(table, framed)
        }
        else -> {
            // Indented off the main thread: parsing the whole 512 KB preview would stall a frame or more.
            val formatting = kind == GatewayFileKind.Json && !text.truncated
            val pretty by produceState<Pair<FileText, String?>?>(null, text, formatting) {
                value = if (formatting) text to withContext(Dispatchers.Default) { prettyJson(text.text) } else null
            }
            val indented = pretty?.takeIf { it.first == text }?.second
            val shown = if (!raw && indented != null) indented else text.text
            CodeView(
                shown,
                language = text.language ?: languageOf(file),
                modifier = framed.padding(vertical = 10.dp),
                focusLine = file.line.takeIf { shown === text.text },
                wrap = wrap,
            )
        }
    }
}

/** A CSV or TSV file as a grid: the first row as the header, columns as wide as their cells (within reason). */
@Composable
private fun DataTable(table: DelimitedTable, modifier: Modifier) {
    if (table.rows.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("The file is empty.", style = Theme[typography][bodySmall], color = Theme[colors][textTertiary])
        }
        return
    }
    val style = Theme[typography][bodySmall].copy(color = Theme[colors][textColor])
    val headerStyle = style.copy(fontWeight = FontWeight.SemiBold)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val charWidth = remember(style, density) { with(density) { measurer.measure("Ab0-.Mw1 x", style).size.width.toDp() / 10 } }
    val widths: List<Dp> = remember(table, charWidth) {
        (0 until table.columns).map { column ->
            val longest = table.rows.take(200).maxOf { it.getOrNull(column)?.length ?: 0 }
            charWidth * longest.coerceIn(3, 32) * 1.2f + 32.dp
        }
    }
    val numberWidth = charWidth * table.rows.size.toString().length + 20.dp
    val lineColor = Theme[colors][stroke]
    Column(modifier) {
        Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.width(numberWidth + widths.fold(0.dp) { sum, w -> sum + w })) {
                itemsIndexed(table.rows) { index, row ->
                    val header = index == 0
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (header) Theme[colors][surface2] else if (index % 2 == 0) Theme[colors][surface] else Color.Transparent),
                    ) {
                        Text(
                            if (header) "" else index.toString(),
                            style = Theme[typography][caption],
                            color = Theme[colors][textTertiary],
                            modifier = Modifier.width(numberWidth).padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                        widths.forEachIndexed { column, width ->
                            Text(
                                row.getOrNull(column).orEmpty(),
                                style = if (header) headerStyle else style,
                                maxLines = if (header) 2 else 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(width).padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(lineColor))
                }
            }
        }
        Text(
            buildString {
                append("${table.rows.size - 1} rows · ${table.columns} columns")
                if (table.more) append(" · more in the full file")
            },
            style = Theme[typography][caption],
            color = Theme[colors][textTertiary],
            modifier = Modifier.fillMaxWidth().background(Theme[colors][surface2]).padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** No preview: why, and Save / Share so the file can still be had. */
@Composable
private fun Unavailable(file: GatewayFile, kind: GatewayFileKind, error: String?, onSave: () -> Unit, onShare: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val tint = if (error != null) Theme[colors][danger] else Theme[colors][accent]
        Box(
            Modifier.size(64.dp).background(if (error != null) Theme[colors][dangerSoft] else Theme[colors][accentSoft], RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            UnstyledIcon(if (error != null) Lucide.FileWarning else kindIcon(kind), contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            if (error != null) "Couldn't open ${file.name}" else "No preview for ${file.extension.uppercase().ifEmpty { "this" }} files",
            style = Theme[typography][label],
            color = Theme[colors][textColor],
        )
        Spacer(Modifier.height(6.dp))
        Text(
            error ?: "Save it to the phone, or share it to an app that opens it.",
            style = Theme[typography][bodySmall],
            color = Theme[colors][textTertiary],
            textAlign = TextAlign.Center,
        )
        if (onShare != null) {
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button("Save", onClick = onSave, leadingIcon = Lucide.Download, size = ButtonSize.Small)
                Button("Share", onClick = onShare, leadingIcon = Lucide.Share2, variant = ButtonVariant.Secondary, size = ButtonSize.Small)
            }
        }
    }
}

private class Notice(val text: String, val error: Boolean = false)

@Composable
private fun NoticeLine(notice: Notice) = Banner(
    notice.text,
    tint = if (notice.error) Theme[colors][danger] else Theme[colors][success],
    background = if (notice.error) Theme[colors][dangerSoft] else Theme[colors][successSoft],
)

@Composable
private fun Banner(text: String, tint: Color, background: Color) {
    Text(
        text,
        style = Theme[typography][caption],
        color = tint,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
            .background(background, RoundedCornerShape(Theme[radii][radiusMedium]))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** The file's type on a tinted tile, the way the viewer's header names it at a glance. */
@Composable
private fun KindTile(kind: GatewayFileKind) {
    Box(
        Modifier.size(40.dp).background(Theme[colors][accentSoft], RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        UnstyledIcon(kindIcon(kind), contentDescription = kind.label, tint = Theme[colors][accentText], modifier = Modifier.size(22.dp))
    }
}

internal fun kindIcon(kind: GatewayFileKind): ImageVector = when (kind) {
    GatewayFileKind.Markdown, GatewayFileKind.Text -> Lucide.FileText
    GatewayFileKind.Json -> Lucide.Braces
    GatewayFileKind.Table -> Lucide.FileSpreadsheet
    GatewayFileKind.Code -> Lucide.FileCode
    GatewayFileKind.Image -> Lucide.FileImage
    GatewayFileKind.Pdf -> Lucide.BookOpen
    GatewayFileKind.Other -> Lucide.File
}

/** The highlighter's name for a file the gateway gave no language for. */
private fun languageOf(file: GatewayFile): String? = when (file.kind) {
    GatewayFileKind.Json -> "json"
    GatewayFileKind.Code -> file.extension.ifEmpty { file.name.lowercase() }
    else -> null
}

/** What Copy puts on the clipboard, and what to tell when that isn't the whole file. */
internal class Clip(val text: String, val note: String?)

/**
 * The preview's text for the clipboard, cut to [max] characters: Android hands a clip to the system in
 * one Binder call, and the 512 KB preview as UTF-16 is past its 1 MB limit. A cut never splits a
 * surrogate pair.
 */
internal fun clipFor(file: FileText, max: Int = MAX_CLIP_CHARS): Clip {
    if (file.text.length <= max) {
        return Clip(file.text, if (file.truncated) "Copied the part shown. Save gets the whole file." else null)
    }
    val end = if (file.text[max - 1].isHighSurrogate()) max - 1 else max
    return Clip(file.text.substring(0, end), "Copied the first ${groupThousands(end)} characters. Save gets the whole file.")
}

/** 100000 as 100,000. */
private fun groupThousands(n: Int): String = n.toString().reversed().chunked(3).joinToString(",").reversed()

/** About 200 KB as UTF-16 in a Binder parcel: well inside its limit. */
private const val MAX_CLIP_CHARS = 100_000

/** 1.4 KB, 12 MB: one decimal under ten, none above. */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val shown = if (value < 10) ((value * 10).toLong() / 10.0).toString().removeSuffix(".0") else value.toLong().toString()
    return "$shown ${units[unit]}"
}
