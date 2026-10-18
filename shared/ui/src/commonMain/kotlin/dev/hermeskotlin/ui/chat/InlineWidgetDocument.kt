package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ReplyMedia
import dev.hermeskotlin.core.media.FileText
import dev.hermeskotlin.core.media.GatewayFile
import kotlin.math.abs

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

/** A CSS `#rrggbbaa` color for [argb]: Herald's lines and muted text are translucent, and lose that as `#rrggbb`. */
internal fun cssColor(argb: Int): String {
    val value = argb.toLong() and 0xffffffff
    return "#" + (((value and 0xffffff) shl 8) or (value ushr 24)).toString(16).padStart(8, '0')
}

/**
 * The wrapper's title while a widget is shown: [WIDGET_REPORT], then `t` and/or `b` for the ends its page has
 * scrolled to (both when it doesn't scroll), `|`, and `x,y,w,h` areas (CSS px, `;` between) that keep every drag:
 * inner scrollers, sliders and `touch-action` controls (a drawing canvas needs one; a chart's lets the transcript
 * scroll). Kept current as the page changes, so it's already
 * there when a finger comes down: a report sent from the touch itself arrives after the drag is decided.
 */
internal const val WIDGET_REPORT = "widget:"

/** No native bridge: the wrapper only turns a well-formed report from its own frame into its title. */
private const val REPORT_SCRIPT = "<script>(function(){var last=null,queued=false,dirty=true,scanned=0,timer=0,picks=[]," +
    // Per document: a reloaded widget's first report differs from the last one's, so the title changes.
    "id=Math.random().toString(36).slice(2,8);" +
    "function over(n){return n.scrollHeight-n.clientHeight>1}" +
    "function oy(n){return getComputedStyle(n).overflowY}" +
    // The page scroller: the viewport (unless hidden, also through body's overflow), else a scrolling body.
    "function main(){var d=document.documentElement,s=document.scrollingElement||d,b=document.body;" +
    "if(over(s)&&oy(d)!=='hidden'&&!(b&&oy(d)==='visible'&&oy(b)==='hidden'))return s;" +
    "return b&&over(b)&&/auto|scroll/.test(oy(b))?b:null}" +
    "function keeps(n){if(n.tagName==='INPUT'&&n.type==='range')return true;var s=getComputedStyle(n),t=s.touchAction;" +
    "return t!=='auto'&&t!=='manipulation'&&t.indexOf('pan-y')<0||/auto|scroll/.test(s.overflowY)&&over(n)}" +
    "function scan(){picks=[];var m=main(),all=document.querySelectorAll('body *');" +
    "for(var i=0;i<all.length&&picks.length<20;i++)if(all[i]!==m&&keeps(all[i]))picks.push(all[i])}" +
    "function report(){var m=main(),r=[];for(var i=0;i<picks.length;i++){var b=picks[i].getBoundingClientRect();" +
    "if(b.width>0&&b.height>0&&b.bottom>0&&b.top<innerHeight&&b.right>0&&b.left<innerWidth)" +
    "r.push([b.left,b.top,b.width,b.height].map(Math.round).join(','))}" +
    "while(r.join(';').length>500)r.pop();" +
    "var v=(m?(m.scrollTop<=0?'t':'')+(m.scrollTop+m.clientHeight>=m.scrollHeight-1?'b':''):'tb')+'|'+r.join(';')+'|'+id;" +
    "if(v!==last){last=v;parent.postMessage({heraldReport:v},'*')}}" +
    // Scrolling only moves the areas; a full scan (styles of every element) runs at most every 250 ms.
    "function run(){queued=false;if(dirty){var wait=scanned+250-Date.now();" +
    "if(wait<=0){dirty=false;scanned=Date.now();scan()}else if(!timer)timer=setTimeout(function(){timer=0;soon()},wait)}report()}" +
    "function soon(){if(!queued){queued=true;requestAnimationFrame(run)}}" +
    "function changed(){dirty=true;soon()}" +
    "addEventListener('scroll',soon,{capture:true,passive:true});addEventListener('resize',changed);addEventListener('load',changed);" +
    "new MutationObserver(changed).observe(document.documentElement," +
    "{subtree:true,childList:true,attributes:true,attributeFilter:['style','class','hidden']});" +
    "if(window.ResizeObserver){var ro=new ResizeObserver(changed);ro.observe(document.documentElement);" +
    "addEventListener('DOMContentLoaded',function(){if(document.body)ro.observe(document.body)})}})()</script>"

/** An area of a widget that keeps drags, in CSS px from the widget's top left. */
internal data class WidgetArea(val x: Float, val y: Float, val width: Float, val height: Float) {
    fun contains(px: Float, py: Float): Boolean = px >= x && px < x + width && py >= y && py < y + height
}

/** What a widget last reported; before its first report, the transcript takes vertical drags. */
internal data class WidgetReport(val atTop: Boolean = true, val atBottom: Boolean = true, val keeps: List<WidgetArea> = emptyList())

/** The report in a wrapper [title], or null when it isn't one; malformed areas are left out. */
internal fun widgetReport(title: String?): WidgetReport? {
    val value = title?.takeIf { it.startsWith(WIDGET_REPORT) }?.removePrefix(WIDGET_REPORT) ?: return null
    val ends = value.substringBefore('|')
    // Then `|` and the document's token, only there to change the title.
    val keeps = value.substringAfter('|', "").substringBefore('|').split(';').mapNotNull { area ->
        area.split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 4 }?.let { WidgetArea(it[0], it[1], it[2], it[3]) }
    }
    return WidgetReport('t' in ends, 'b' in ends, keeps)
}

/** A touch at ([x], [y]) on an area that keeps drags, whichever way it then moves: held from the start, so a flick can't escape. */
internal fun widgetKeepsTouch(report: WidgetReport, x: Float, y: Float): Boolean = report.keeps.any { it.contains(x, y) }

/**
 * Whether a drag that came down at ([x], [y]) (CSS px) and has moved by ([dx], [dy]) stays in the widget instead of
 * scrolling the transcript: on an area that keeps drags, sideways (a slider), or with more page to scroll that way.
 * Decided once per drag; the transcript can't take a drag over later.
 */
internal fun widgetKeepsDrag(report: WidgetReport, x: Float, y: Float, dx: Float, dy: Float): Boolean = when {
    widgetKeepsTouch(report, x, y) -> true
    abs(dx) > abs(dy) -> true
    // A finger moving down scrolls the page up, towards its top.
    dy > 0 -> !report.atTop
    else -> !report.atBottom
}

/** A separate, opaque-origin document: HTML cannot escape srcdoc into the app-owned wrapper. */
internal fun widgetDocument(html: String, theme: WidgetTheme, font: String): String {
    val prelude = "<meta http-equiv=\"Content-Security-Policy\" content=\"frame-src 'none'\">" +
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" + REPORT_SCRIPT +
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
        "<script>addEventListener('message',function(e){var f=document.querySelector('iframe'),v=e.data&&e.data.heraldReport;" +
        "if(f&&e.source===f.contentWindow&&typeof v==='string'&&/^t?b?\\|[0-9,;-]{0,500}\\|[0-9a-z]{1,8}$/.test(v))document.title='$WIDGET_REPORT'+v})</script>" +
        "</head><body><iframe title=\"HTML widget\" sandbox=\"allow-scripts\" srcdoc=\"$content\"></iframe></body></html>"
}
