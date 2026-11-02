package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import dev.hermeskotlin.ui.rememberDesktopWeb
import kotlin.io.encoding.Base64

/** No PDF pages drawn in the desktop app yet: the viewer says so, and Save opens it elsewhere. */
@Composable
internal actual fun rememberPdfOpener(): suspend (ByteArray) -> PdfOpen =
    { PdfOpen.Failed("PDFs don't open in the desktop app yet. Save gets the file.") }

/** A widget in the app's own browser, where the runtime has one; elsewhere it says it can't show. */
@Composable
internal actual fun WidgetWebView(html: String, theme: WidgetTheme, name: String, modifier: Modifier, onFailure: (String) -> Unit) {
    val web = rememberDesktopWeb()
    if (web == null) {
        val failure = rememberUpdatedState(onFailure)
        LaunchedEffect(Unit) { failure.value("Widgets don't show in this build of the desktop app.") }
        return
    }
    val document = remember(html, theme) { widgetDocument(html, theme, widgetFont) }
    web.Widget(document, name, modifier, keepsWheel = ::widgetKeepsWheel, onFailure = onFailure)
}

/** A wheel turned over a widget scrolls it while it has page left that way; at its end, the chat scrolls on. */
private fun widgetKeepsWheel(title: String?, down: Boolean): Boolean {
    val report = widgetReport(title) ?: WidgetReport()
    return if (down) !report.atBottom else !report.atTop
}

/** Geist as base64, which every widget's document embeds; read once. */
private val widgetFont: String by lazy {
    val bytes = WidgetTheme::class.java.classLoader?.getResourceAsStream("geist_regular.ttf")?.use { it.readBytes() }
    bytes?.let { Base64.encode(it) }.orEmpty()
}
