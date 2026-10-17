package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.chat.ReplyMedia
import dev.hermeskotlin.core.media.FileText
import dev.hermeskotlin.core.media.GatewayFile
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InlineWidgetTest {
    private val theme = WidgetTheme("#111111", "#222222", "#333333", "#444444", "#555555", false, 16f)

    @Test
    fun olderOrUnknownWebViewsCannotRunWidgetsWithoutNetworkIsolation() {
        assertTrue(supportsWidgetIsolation("152.0.7977.0"))
        assertTrue(supportsWidgetIsolation("155.0.8059.43"))
        listOf(null, "", "unknown", "151.0.0.0").forEach { assertFalse(supportsWidgetIsolation(it)) }
    }

    @Test
    fun onlyExplicitWorkspaceHtmlIsEligible() {
        fun item(path: String, preview: Boolean = true) = ReplyMedia(path, path.substringAfterLast('/'), false, preview = preview)
        assertEquals(GatewayFile("/tmp/chart.HTML"), widgetFile(item("/tmp/chart.HTML")))
        assertEquals(GatewayFile("~/chart.htm"), widgetFile(item("~/chart.htm")))
        assertEquals(GatewayFile("/tmp/chart.html"), widgetFile(item("file:///tmp/chart.html")))
        assertNull(widgetFile(item("/tmp/chart.html", preview = false)))
        listOf("https://example.com/a.html", "data:text/html,a", "//example.com/a.html", "chart.html", "/tmp/a.pdf").forEach {
            assertNull(widgetFile(item(it)), it)
        }
    }

    @Test
    fun partialBinaryEmptyAndLargeDocumentsAreNotExecuted() {
        fun text(value: String = "<p>hello</p>", size: Long = 12, truncated: Boolean = false, binary: Boolean = false) =
            FileText(value, size, truncated, binary)
        assertNull(widgetProblem(text()))
        assertNotNull(widgetProblem(text(truncated = true)))
        assertNotNull(widgetProblem(text(binary = true)))
        assertNotNull(widgetProblem(text(value = "  ")))
        assertNotNull(widgetProblem(text(size = 524_289)))
        assertNotNull(widgetProblem(text(value = "é".repeat(262_145))))
    }

    @Test
    fun wrapperKeepsHostileMarkupInsideTheSandboxedDocument() {
        val html = "\" onload=\"alert(1)\"><script>parent.document.body.remove()</script></iframe><p>&quoted</p>"
        val result = widgetDocument(html, theme, "Zm9udA==")
        assertTrue(result.contains("srcdoc=\"&lt;!doctype html&gt;"))
        assertTrue(result.contains("sandbox=\"allow-scripts\""))
        assertFalse(result.contains("allow-same-origin"))
        assertEquals(1, Regex("<iframe ").findAll(result).count())
        assertEquals(1, Regex("</iframe>").findAll(result).count())
        assertFalse(result.contains(html))
        assertTrue(result.contains("&lt;/iframe&gt;"))
        assertTrue(result.contains("&amp;quoted"))
        assertTrue(result.contains("connect-src 'none'"))
        assertTrue(result.contains("form-action 'none'"))
    }

    @Test
    fun preludeUsesResolvedAppearanceAndPrecedesPageStyles() {
        val result = widgetDocument("<style>:root{--accent:red}</style>", theme, "Zm9udA==")
        assertTrue(result.indexOf("--accent:#333333") < result.indexOf("--accent:red"))
        listOf("--foreground:#111111", "--muted-foreground:#222222", "--border:#444444", "--card:#555555",
            "color-scheme:light", "font-size:16.0px", "data:font/ttf;base64,Zm9udA==").forEach {
            assertTrue(result.contains(it), it)
        }
        assertTrue(widgetDocument("<p>dark</p>", theme.copy(dark = true), "").contains("color-scheme:dark"))
    }

    @Test
    fun loaderPreservesTheFileAndGatewayFailure() = runTest {
        val file = GatewayFile("/tmp/chart.html")
        val expected = FileLoad.Ready(FileText("<p>profile B</p>", 16, false, false))
        val files = WidgetFiles("gateway/profile B", { requested ->
            assertEquals(file, requested)
            expected
        }, { FileDownload.Failed("not used") })
        assertEquals(expected, loadWidget(files, file))
        val failed = files.copy(load = { FileLoad.Failed("Profile unavailable") })
        assertEquals(FileLoad.Failed("Profile unavailable"), loadWidget(failed, file))
    }

    @Test
    fun cancelledLoadsAndDownloadsStayCancelled() = runTest {
        val files = WidgetFiles("chat", { throw kotlinx.coroutines.CancellationException("left chat") },
            { throw kotlinx.coroutines.CancellationException("left chat") })
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> { loadWidget(files, GatewayFile("/tmp/a.html")) }
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> { downloadWidget(files, GatewayFile("/tmp/a.html")) }
        assertTrue(loadWidget(files.copy(load = { error("offline") }), GatewayFile("/tmp/a.html")) is FileLoad.Failed)
    }

    @Test
    fun downloadFailuresRemainRecoverable() = runTest {
        val files = WidgetFiles("chat", { FileLoad.Loading }, { throw IllegalStateException("offline") })
        assertTrue(downloadWidget(files, GatewayFile("/tmp/chart.html")) is FileDownload.Failed)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun loaderTimesOutAndRejectsTruncatedText() = runTest {
        val file = GatewayFile("/tmp/chart.html")
        val files = WidgetFiles("chat", { awaitCancellation() }, { FileDownload.Failed("not used") })
        assertTrue(loadWidget(files, file) is FileLoad.Failed)
        assertEquals(20_000, testScheduler.currentTime)
        assertTrue(loadWidget(files.copy(load = { FileLoad.Ready(FileText("partial", 900_000, true, false)) }), file) is FileLoad.Failed)
    }
}
