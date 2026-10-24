package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier

/** No PDF pages drawn in Herald on iOS yet: the viewer says so. */
@Composable
internal actual fun rememberPdfOpener(): suspend (ByteArray) -> PdfOpen =
    { PdfOpen.Failed("PDFs don't open in Herald on iOS yet.") }

/** Never shown: [dev.hermeskotlin.ui.inlineWidgetsShow] is off on iOS, so a widget is a file row there. */
@Composable
internal actual fun WidgetWebView(html: String, theme: WidgetTheme, name: String, modifier: Modifier, onFailure: (String) -> Unit) {
    val failure = rememberUpdatedState(onFailure)
    LaunchedEffect(Unit) { failure.value("Widgets don't show on iOS yet.") }
}
