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
    fun markupInCodeIsASampleNotMedia() {
        val reply = "Use this syntax:\n```markdown\n![cat](/opt/data/cat.png)\nMEDIA:/opt/data/report.pdf\n```\n" +
            "Inline, type `MEDIA:/tmp/x.png` or `![a](b.png)`. Here it is: MEDIA:/opt/data/real.pdf"
        val (text, media) = extractReplyMedia(reply)
        assertEquals(
            "Use this syntax:\n```markdown\n![cat](/opt/data/cat.png)\nMEDIA:/opt/data/report.pdf\n```\n" +
                "Inline, type `MEDIA:/tmp/x.png` or `![a](b.png)`. Here it is:",
            text,
        )
        assertEquals(listOf("/opt/data/real.pdf"), media.map { it.source })
    }

    @Test
    fun aFenceStillStreamingIsCodeToTheEnd() {
        val (text, media) = extractReplyMedia("```bash\necho MEDIA:/tmp/a.png")
        assertEquals("```bash\necho MEDIA:/tmp/a.png", text)
        assertTrue(media.isEmpty())
    }

    @Test
    fun backticksMidSentenceOpenNoFence() {
        val (text, media) = extractReplyMedia("Wrap it in ``` fences.\nHere is the chart: MEDIA:/opt/data/chart.png")
        assertEquals("Wrap it in ``` fences.\nHere is the chart:", text)
        assertEquals(listOf("/opt/data/chart.png"), media.map { it.source })
    }

    @Test
    fun aLongerFenceHoldsAShorterOne() {
        val reply = "Example:\n````markdown\n```markdown\n![cat](/opt/data/cat.png)\n```\n````\nDone."
        assertEquals(reply to emptyList(), extractReplyMedia(reply))
    }

    @Test
    fun aCodeSpanClosesOnTheSameNumberOfBackticks() {
        val reply = "Type ``MEDIA:`x` `` or ``` `![a](b.png)` ```."
        assertEquals(reply to emptyList(), extractReplyMedia(reply))
    }

    @Test
    fun aCodeSpanInAnImagesAltKeepsItAnImage() {
        val (text, media) = extractReplyMedia("The run: ![`plot.py` output](/opt/data/plot.png)")
        assertEquals("The run:", text)
        assertEquals(listOf("/opt/data/plot.png"), media.map { it.source })
    }

    @Test
    fun aDirectiveAfterACodeSpanIsNotAloneOnItsLine() {
        val text = "Run `x` ::preview{file=\"a.html\"}"
        assertEquals(text to emptyList(), extractReplyMedia(text))
    }

    @Test
    fun anEchoInCodeStays() {
        val generated = GeneratedImage("/opt/data/img.png", echoes = listOf("/tmp/sandbox/img.png"))
        val reply = "Done.\n```bash\nls /tmp/sandbox/img.png\n```"
        assertEquals(reply, extractReplyMedia(reply, listOf(generated)).first)
    }

    @Test
    fun theTidyingUpLeavesCodeAlone() {
        val code = "```python\ndef a():\n    pass\n\n\ndef b():  \n    pass\n```"
        val (text, media) = extractReplyMedia("Here: MEDIA:/opt/data/a.py\n\n\n\n$code")
        assertEquals("Here:\n\n$code", text)
        assertEquals(listOf("/opt/data/a.py"), media.map { it.source })
    }

    @Test
    fun anImageAddressKeepsItsBrackets() {
        val (text, media) = extractReplyMedia("See ![Foo](https://en.wikipedia.org/wiki/Foo_(bar).png) for details.")
        assertEquals("See  for details.", text)
        assertEquals(listOf("https://en.wikipedia.org/wiki/Foo_(bar).png"), media.map { it.source })
    }

    @Test
    fun aLoneBracketInAnImageAddressRunsToTheFirstClose() {
        val (text, media) = extractReplyMedia("Saved ![x](/opt/data/a(1.png)")
        assertEquals("Saved", text)
        assertEquals(listOf("/opt/data/a(1.png"), media.map { it.source })
    }

    @Test
    fun mediaInsideAWordIsProse() {
        val text = "MULTIMEDIA:notes.txt is a heading"
        assertEquals(text to emptyList(), extractReplyMedia(text))
    }

    @Test
    fun plainTextIsUntouched() {
        val text = "No pictures here, just [a link](https://example.com)."
        assertEquals(text to emptyList(), extractReplyMedia(text))
    }
}
