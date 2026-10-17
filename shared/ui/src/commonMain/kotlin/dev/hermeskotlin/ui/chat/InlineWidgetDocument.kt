package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ReplyMedia
import dev.hermeskotlin.core.media.FileText
import dev.hermeskotlin.core.media.GatewayFile

/** Connection Allowlists, including WebRTC blocking, shipped in Chromium WebView 152. */
internal fun supportsWidgetIsolation(version: String?): Boolean =
    version?.substringBefore('.')?.toIntOrNull()?.let { it >= 152 } == true

/** Only an explicit workspace HTML preview may execute; ordinary file links remain files. */
internal fun widgetFile(item: ReplyMedia): GatewayFile? =
    if (item.preview) GatewayFile.fromLink(item.source)?.takeIf { it.extension in setOf("html", "htm") } else null

internal fun widgetProblem(file: FileText): String? = when {
    file.binary -> "This file isn't HTML text."
    file.truncated || file.byteSize > 512 * 1024 || file.text.encodeToByteArray().size > 512 * 1024 -> "This widget is over the 512 KB preview limit."
    file.text.isBlank() -> "This widget is empty."
    else -> null
}

/** Resolved Herald appearance, using Desktop's public widget token names. */
internal data class WidgetTheme(
    val foreground: String,
    val muted: String,
    val accent: String,
    val border: String,
    val card: String,
    val dark: Boolean,
    val fontSize: Float,
)

/** A separate, opaque-origin document: HTML cannot escape srcdoc into the app-owned wrapper. */
internal fun widgetDocument(html: String, theme: WidgetTheme, font: String): String {
    val prelude = "<meta http-equiv=\"Content-Security-Policy\" content=\"frame-src 'none'\">" +
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<style>@font-face{font-family:Herald;src:url(data:font/ttf;base64,$font)}" +
        ":root{color-scheme:${if (theme.dark) "dark" else "light"};--foreground:${theme.foreground};" +
        "--muted-foreground:${theme.muted};--accent:${theme.accent};--border:${theme.border};--card:${theme.card}}" +
        "html,body{margin:0;padding:0;background:transparent;color:var(--foreground);" +
        "font-family:Herald,sans-serif;font-size:${theme.fontSize}px}</style>"
    val content = ("<!doctype html>" + prelude + html).replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; " +
        "script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; " +
        "frame-src 'self'; connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'\">" +
        "<style>html,body{margin:0;height:100%;background:transparent}iframe{width:100%;height:100%;border:0}</style>" +
        "</head><body><iframe title=\"HTML widget\" sandbox=\"allow-scripts\" srcdoc=\"$content\"></iframe></body></html>"
}
