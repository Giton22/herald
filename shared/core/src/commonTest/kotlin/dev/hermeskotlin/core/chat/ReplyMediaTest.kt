package dev.hermeskotlin.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplyMediaTest {

    @Test
    fun markdownImagesAndMediaTokensBecomeMedia() {
        val (text, media) = extractReplyMedia(
            """
            Yes, I rendered the full CV as a PNG:

            ![CV page 1](/opt/data/attachments/CV_page_1.png)

            The report is at MEDIA:/opt/data/out/report.xlsx. And again MEDIA:/opt/data/attachments/CV_page_1.png
            """.trimIndent(),
        )
        assertEquals(
            "Yes, I rendered the full CV as a PNG:\n\nThe report is at. And again",
            text,
        )
        assertEquals(listOf("CV_page_1.png", "report.xlsx"), media.map { it.name })
        assertTrue(media[0].isImage && media[0].onGateway)
        assertFalse(media[1].isImage)
        assertEquals("/opt/data/out/report.xlsx", media[1].gatewayPath)
    }

    @Test
    fun remoteAndInlineImagesAreKeptAsSources() {
        val (_, media) = extractReplyMedia("""![chart](https://example.com/c.png "Chart") ![](data:image/png;base64,AAAA)""")
        assertEquals(listOf("https://example.com/c.png", "data:image/png;base64,AAAA"), media.map { it.source })
        assertFalse(media[0].onGateway)
        assertEquals("image", media[1].name)
    }

    @Test
    fun previewWidgetsBecomeTheirFile() {
        val (text, media) = extractReplyMedia("Here is the chart:\n::preview{file=\"/opt/data/out/chart.html\"}\nEnjoy.")
        assertEquals("Here is the chart:\n\nEnjoy.", text)
        assertEquals(ReplyMedia("/opt/data/out/chart.html", "chart.html", isImage = false), media.single())
    }

    @Test
    fun mentionsOfAGeneratedPictureGo() {
        val host = "/root/.hermes/cache/generated/images/cat.png"
        val sandbox = "/workspace/.hermes/cache/generated/images/cat.png"
        val generated = listOf(GeneratedImage(host, listOf(host, sandbox)))
        val (text, media) = extractReplyMedia(
            "Here is your cat:\n\n![cat]($sandbox)\n\nAlso at MEDIA:$host and saved to $sandbox.\n\nMEDIA:/opt/data/out/notes.txt",
            generated,
        )
        assertEquals("Here is your cat:\n\nAlso at and saved to.", text)
        // Other files the reply delivers stay.
        assertEquals(listOf("/opt/data/out/notes.txt"), media.map { it.source })
    }

    @Test
    fun aBarePathGoesWithoutAnyOtherMarkup() {
        val sandbox = "/workspace/.hermes/cache/generated/images/cat.png"
        val (text, media) = extractReplyMedia("Done! It's at $sandbox", listOf(GeneratedImage("/root/cat.png", listOf(sandbox))))
        assertEquals("Done! It's at", text)
        assertTrue(media.isEmpty())
    }

    @Test
    fun aGeneratedPathInCodeOrALongerPathStays() {
        val host = "/tmp/a.png"
        val (text, _) = extractReplyMedia("See `/tmp/a.png` and /tmp/a.png.bak", listOf(GeneratedImage(host, listOf(host))))
        assertEquals("See `/tmp/a.png` and /tmp/a.png.bak", text)
    }

    @Test
    fun plainTextIsUntouched() {
        val text = "No pictures here, just [a link](https://example.com)."
        assertEquals(text to emptyList(), extractReplyMedia(text))
    }
}
