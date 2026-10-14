package dev.hermeskotlin.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatewayFileTest {

    @Test
    fun absolutePathsAndFileUrisAreGatewayFiles() {
        assertEquals(GatewayFile("/opt/data/report.md"), GatewayFile.fromLink("/opt/data/report.md"))
        assertEquals(GatewayFile("/opt/data/report.md"), GatewayFile.fromLink("file:///opt/data/report.md"))
        assertEquals(GatewayFile("/opt/data/report.md"), GatewayFile.fromLink("file://localhost/opt/data/report.md"))
        assertEquals(GatewayFile("/opt/data/report.md"), GatewayFile.fromLink("FILE:/opt/data/report.md"))
        assertEquals(GatewayFile("~/notes/today.txt"), GatewayFile.fromLink("~/notes/today.txt"))
    }

    @Test
    fun webLinksOtherSchemesAndRelativePathsAreNot() {
        listOf(
            "https://example.com/a.md", "mailto:me@example.com", "intent://scan#Intent;end", "//cdn.example.com/x.js",
            "report.md", "./out/report.md", "file://otherhost/share/x.txt", "", "/", "file:///", "javascript:alert(1)",
        ).forEach { assertNull(GatewayFile.fromLink(it), it) }
    }

    @Test
    fun escapesAreDecodedAndTheLineIsKept() {
        assertEquals(GatewayFile("/home/me/My Notes/plan ü.md"), GatewayFile.fromLink("file:///home/me/My%20Notes/plan%20%C3%BC.md"))
        assertEquals(GatewayFile("/tmp/100%real.txt"), GatewayFile.fromLink("/tmp/100%real.txt"))
        assertEquals(GatewayFile("/tmp/%-1 50%.txt"), GatewayFile.fromLink("/tmp/%-1%2050%.txt"))
        // A character outside the BMP keeps both halves of its surrogate pair beside an escape.
        assertEquals(GatewayFile("/tmp/My 😀.txt"), GatewayFile.fromLink("file:///tmp/My%20😀.txt"))
        assertEquals(GatewayFile("/src/App.kt", line = 42), GatewayFile.fromLink("/src/App.kt:42"))
        assertEquals(GatewayFile("/src/App.kt", line = 42), GatewayFile.fromLink("/src/App.kt:42:7"))
        assertEquals(GatewayFile("/src/App.kt", line = 12), GatewayFile.fromLink("file:///src/App.kt#L12"))
        assertEquals(GatewayFile("/src/App.kt", line = 12), GatewayFile.fromLink("/src/App.kt#L12-L20"))
        assertEquals(GatewayFile("/src/App.kt"), GatewayFile.fromLink("/src/App.kt#readme"))
        // A `#` in a plainly written name is part of it; in a URI it would be escaped.
        assertEquals(GatewayFile("/home/me/issue#12.md"), GatewayFile.fromLink("/home/me/issue#12.md"))
        assertEquals(GatewayFile("/tmp/C#/Program.cs", line = 3), GatewayFile.fromLink("/tmp/C#/Program.cs#L3"))
        assertEquals(GatewayFile("/tmp/issue#12.md"), GatewayFile.fromLink("file:///tmp/issue%2312.md"))
        assertEquals(GatewayFile("/tmp/notes"), GatewayFile.fromLink("file:///tmp/notes#12.md"))
        // An anchor after a name with its extension is cut, dots and all.
        assertEquals(GatewayFile("/docs/guide.md"), GatewayFile.fromLink("/docs/guide.md#v1.2-notes"))
        assertEquals(GatewayFile("C:/Users/me/a.txt"), GatewayFile.fromLink("file:///C:/Users/me/a.txt"))
    }

    @Test
    fun relativeLinksJoinTheFilesFolder() {
        val file = GatewayFile("/opt/data/docs/index.md")
        assertEquals(GatewayFile("/opt/data/docs/part2.md"), file.resolve("part2.md"))
        assertEquals(GatewayFile("/opt/data/docs/part2.md"), file.resolve("./part2.md"))
        assertEquals(GatewayFile("/opt/data/docs/img/chart.png"), file.resolve("img/./chart.png"))
        assertEquals(GatewayFile("/opt/data/out.csv"), file.resolve("../out.csv"))
        assertEquals(GatewayFile("/opt/data/data/out.csv"), file.resolve("../data//out.csv"))
        assertEquals(GatewayFile("/opt/data/docs/My Notes.md"), file.resolve("My%20Notes.md"))
        assertEquals(GatewayFile("/x.md"), GatewayFile("/index.md").resolve("x.md"))
        assertEquals(GatewayFile("C:/work/notes/b.md"), GatewayFile("C:/work/docs/a.md").resolve("../notes/b.md"))
        assertEquals(GatewayFile("C:/work/notes/b.md"), GatewayFile("C:/work/docs/a.md").resolve("..\\notes\\b.md"))
        assertEquals(GatewayFile("C:/b.md"), GatewayFile("C:/work/docs/a.md").resolve("/b.md"))
        assertEquals(GatewayFile("/opt/data/docs/my chart.png"), file.resolve("<my chart.png>"))
    }

    @Test
    fun dotDotPastTheRootStaysAtTheRootButNotPastHome() {
        assertEquals(GatewayFile("/b.md"), GatewayFile("/a/index.md").resolve("../../../b.md"))
        assertEquals(GatewayFile("C:/b.md"), GatewayFile("C:/a.md").resolve("../b.md"))
        assertEquals(GatewayFile("~/notes/b.md"), GatewayFile("~/notes/a.md").resolve("b.md"))
        assertEquals(GatewayFile("~/b.md"), GatewayFile("~/notes/a.md").resolve("../b.md"))
        assertEquals(GatewayFile("~/../shared/b.md"), GatewayFile("~/a.md").resolve("../shared/b.md"))
        assertEquals(GatewayFile("~/../c.md"), GatewayFile("~/../shared/b.md").resolve("../c.md"))
    }

    @Test
    fun anchorsAndLinesOnRelativeLinks() {
        val file = GatewayFile("/repo/docs/guide.md", line = 3)
        assertEquals(GatewayFile("/repo/src/App.kt", line = 12), file.resolve("../src/App.kt#L12"))
        assertEquals(GatewayFile("/repo/src/App.kt", line = 12), file.resolve("../src/App.kt#L12-L20"))
        assertEquals(GatewayFile("/repo/src/App.kt", line = 42), file.resolve("../src/App.kt:42"))
        assertEquals(GatewayFile("/repo/docs/Makefile", line = 12), file.resolve("Makefile:12"))
        assertEquals(GatewayFile("/repo/docs/setup.md"), file.resolve("setup.md#install"))
        assertEquals(GatewayFile("/repo/docs/guide.md", line = 12), file.resolve("#L12"))
        assertNull(file.resolve("#install"))
    }

    @Test
    fun absoluteLinksReadAsInAReplyAndTheRestAreNotFiles() {
        val file = GatewayFile("/repo/docs/guide.md")
        assertEquals(GatewayFile("/etc/hosts"), file.resolve("/etc/hosts"))
        assertEquals(GatewayFile("/tmp/a.md", line = 2), file.resolve("file:///tmp/a.md#L2"))
        assertEquals(GatewayFile("~/x.txt"), file.resolve("~/x.txt"))
        listOf(
            "https://example.com/a.md", "mailto:me@example.com", "tel:12345", "//cdn.example.com/x.js", "javascript:alert(1)",
            "", " ", ".", "..", "./", "../", "sub/", "../../..",
        ).forEach { assertNull(file.resolve(it), it) }
    }

    @Test
    fun nameFolderAndKind() {
        val file = GatewayFile("/opt/data/out/report.MD")
        assertEquals("report.MD", file.name)
        assertEquals("/opt/data/out", file.folder)
        assertEquals(GatewayFileKind.Markdown, file.kind)
        assertEquals("", GatewayFile("/x.txt").folder)
    }

    @Test
    fun kindsByExtension() {
        assertEquals(GatewayFileKind.Json, GatewayFileKind.of("data.json"))
        assertEquals(GatewayFileKind.Table, GatewayFileKind.of("sales.csv"))
        assertEquals(GatewayFileKind.Table, GatewayFileKind.of("sales.tsv"))
        assertEquals(GatewayFileKind.Code, GatewayFileKind.of("config.yaml"))
        assertEquals(GatewayFileKind.Code, GatewayFileKind.of("Dockerfile"))
        assertEquals(GatewayFileKind.Text, GatewayFileKind.of("agent.log"))
        assertEquals(GatewayFileKind.Text, GatewayFileKind.of("events.jsonl"))
        assertEquals(GatewayFileKind.Text, GatewayFileKind.of(".bashrc"))
        assertEquals(GatewayFileKind.Text, GatewayFileKind.of("README"))
        assertEquals(GatewayFileKind.Text, GatewayFileKind.of("notes.weird"))
        assertEquals(GatewayFileKind.Image, GatewayFileKind.of("cat.PNG"))
        assertEquals(GatewayFileKind.Other, GatewayFileKind.of("backup.zip"))
        listOf("photo.HEIC", "photo.heif", "photo.avif", "favicon.ico", "old.bmp", "scan.tiff", "scan.TIF").forEach {
            assertEquals(GatewayFileKind.Image, GatewayFileKind.of(it), it)
        }
        // Android has no SVG decoder that Compose can use: it's shown as code.
        assertEquals(GatewayFileKind.Code, GatewayFileKind.of("logo.svg"))
        assertTrue(GatewayFileKind.Code.previewable)
        assertFalse(GatewayFileKind.Other.previewable)
        assertFalse(GatewayFileKind.Image.previewable)
    }

    @Test
    fun pdfsArePreviewedAsPages() {
        assertEquals(GatewayFileKind.Pdf, GatewayFileKind.of("report.pdf"))
        assertEquals(GatewayFileKind.Pdf, GatewayFileKind.of("Q3 Summary.PDF"))
        assertEquals(GatewayFileKind.Pdf, GatewayFile.fromLink("/opt/data/out/report.pdf")?.kind)
        assertEquals(GatewayFileKind.Pdf, GatewayFile.fromLink("file:///opt/data/out/My%20Report.pdf#page=2")?.kind)
        // Only the extension counts: a name that merely mentions pdf is not one.
        assertEquals(GatewayFileKind.Text, GatewayFileKind.of("pdf-notes.txt"))
        assertEquals(GatewayFileKind.Other, GatewayFileKind.of("report.pdf.zip"))
        assertTrue(GatewayFileKind.Pdf.previewable)
    }
}
