package dev.hermeskotlin.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentUploadsTest {

    @Test
    fun aNameStaysOneSegmentTheGatewayAccepts() {
        assertEquals("report.pdf", AttachmentUploads.safeName("report.pdf"))
        assertEquals("Résumé 2026.pdf", AttachmentUploads.safeName("Résumé 2026.pdf"))
        // No folders: the gateway's path checks turn `..` down, and a name can't move the file.
        assertEquals("passwd", AttachmentUploads.safeName("../../etc/passwd"))
        assertEquals("evil.txt", AttachmentUploads.safeName("C:\\temp\\evil.txt"))
        assertEquals("ab.txt", AttachmentUploads.safeName("a\u0000b.txt"))
        assertEquals("what.txt", AttachmentUploads.safeName("what?.txt"))
        assertEquals("attachment", AttachmentUploads.safeName(".."))
        assertEquals("attachment", AttachmentUploads.safeName(""))
        assertEquals(200, AttachmentUploads.safeName("x".repeat(500)).length)
        // file.attach expands variables and strips spaces off the path, so it would look somewhere else.
        assertEquals("HOME.txt", AttachmentUploads.safeName("\$HOME.txt"))
        assertEquals("TEMP.zip", AttachmentUploads.safeName("%TEMP%.zip"))
        assertEquals("notes", AttachmentUploads.safeName("notes ."))
        assertEquals("x", AttachmentUploads.safeName("x" + " ".repeat(300)))
        // A Windows gateway can't make a file of a device's name.
        assertEquals("_NUL.txt", AttachmentUploads.safeName("NUL.txt"))
        assertEquals("_con", AttachmentUploads.safeName("con"))
        assertEquals("console.log", AttachmentUploads.safeName("console.log"))
    }
}
