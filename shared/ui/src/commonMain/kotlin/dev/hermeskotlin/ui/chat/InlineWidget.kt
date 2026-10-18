package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.ReplyMedia
import dev.hermeskotlin.core.media.GatewayFile
import dev.hermeskotlin.designsystem.accent
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.bodySmall
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.Spinner
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusMedium
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * File callbacks and whose files they read (gateway and profile, all the callbacks use); never share documents across
 * owners. A loaded widget keeps the callbacks it started with, so the identity must grow if they ever use more.
 */
internal data class WidgetFiles(
    val identity: Any,
    val load: suspend (GatewayFile) -> FileLoad,
    val download: suspend (GatewayFile) -> FileDownload,
)

internal val LocalWidgetFiles = staticCompositionLocalOf<WidgetFiles?> { null }

internal suspend fun downloadWidget(files: WidgetFiles, file: GatewayFile): FileDownload = try {
    files.download(file)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    FileDownload.Failed("Couldn't get this widget from the gateway.")
}

internal suspend fun loadWidget(files: WidgetFiles, file: GatewayFile): FileLoad = try {
    val loaded = withTimeoutOrNull(20_000) { files.load(file) } ?: FileLoad.Failed("The widget took too long to load.")
    if (loaded is FileLoad.Ready) widgetProblem(loaded.text)?.let { FileLoad.Failed(it) } ?: loaded
    else if (loaded is FileLoad.Failed) loaded else FileLoad.Failed("Couldn't read this widget.")
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    FileLoad.Failed("Couldn't load this widget from the gateway.")
}

@Composable
internal fun InlineWidget(item: ReplyMedia, file: GatewayFile, files: WidgetFiles) = key(files.identity, item.source) {
    var attempt by remember { mutableIntStateOf(0) }
    var failed by remember(attempt) { mutableStateOf<String?>(null) }
    val loaded by key(attempt) { produceState<FileLoad>(FileLoad.Loading) { value = loadWidget(files, file) } }
    fun css(color: Color) = cssColor(color.toArgb())
    val theme = WidgetTheme(
        css(Theme[colors][text]), css(Theme[colors][textTertiary]), css(Theme[colors][accent]),
        css(Theme[colors][stroke]), css(Theme[colors][surface]), Theme[colors][background].luminance() < 0.5f,
        Theme[typography][bodySmall].fontSize.value * LocalDensity.current.fontScale,
    )
    val shape = RoundedCornerShape(Theme[radii][radiusMedium])
    Column(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
        ReplyFile(item, download = { downloadWidget(files, file) })
        when (val current = loaded) {
            is FileLoad.Ready -> if (failed == null) {
                WidgetWebView(current.text.text, theme, item.name, Modifier.fillMaxWidth().height(280.dp).clip(shape).border(1.dp, Theme[colors][stroke], shape), onFailure = { failed = it })
            }
            FileLoad.Loading -> Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { Spinner() }
            else -> Unit
        }
        val problem = (loaded as? FileLoad.Failed)?.message ?: failed
        if (problem != null) {
            Text("$problem Tap to retry.", style = Theme[typography][bodySmall], color = Theme[colors][textTertiary],
                modifier = Modifier.fillMaxWidth().clickable { attempt++ }.padding(12.dp))
        }
    }
}

@Composable
internal expect fun WidgetWebView(html: String, theme: WidgetTheme, name: String, modifier: Modifier, onFailure: (String) -> Unit)
