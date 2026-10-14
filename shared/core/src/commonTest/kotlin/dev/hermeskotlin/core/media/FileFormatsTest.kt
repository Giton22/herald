package dev.hermeskotlin.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileFormatsTest {

    @Test
    fun oneLineJsonIsIndented() {
        assertEquals("{\n  \"a\": 1,\n  \"b\": [\n    true,\n    null\n  ]\n}", prettyJson("""{"a":1,"b":[true,null]}"""))
    }

    @Test
    fun whatIsntOneJsonDocumentIsLeftAlone() {
        assertNull(prettyJson("{\"a\":1}\n{\"a\":2}"))
        assertNull(prettyJson("{\"a\": 1, \"b\""))
        assertNull(prettyJson("hello"))
        assertNull(prettyJson(""))
    }

    @Test
    fun csvCellsMayBeQuoted() {
        val table = parseDelimited("name,notes\r\nAda,\"likes, commas\"\nBob,\"said \"\"hi\"\"\nthen left\"\n", ',')
        assertEquals(
            listOf(listOf("name", "notes"), listOf("Ada", "likes, commas"), listOf("Bob", "said \"hi\"\nthen left")),
            table.rows,
        )
        assertEquals(2, table.columns)
        assertFalse(table.more)
    }

    @Test
    fun tsvAndALastLineWithoutABreak() {
        val table = parseDelimited("a\tb\n1\t2\n\n3\t4", '\t')
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2"), listOf("3", "4")), table.rows)
    }

    @Test
    fun rowsStopAtTheCapAndACutOffLineIsDropped() {
        val capped = parseDelimited("h\n1\n2\n3\n4\n", ',', maxRows = 3)
        assertEquals(listOf(listOf("h"), listOf("1"), listOf("2")), capped.rows)
        assertTrue(capped.more)

        val cut = parseDelimited("h,i\n1,2\n3,", ',', truncated = true)
        assertEquals(listOf(listOf("h", "i"), listOf("1", "2")), cut.rows)
        assertTrue(cut.more)
    }

    @Test
    fun bareFileUrisBecomeAutolinksOutsideCode() {
        assertEquals(
            "Saved to <file:///opt/data/report.md>. Also [x](file:///a.md) and `file:///code.md`",
            dev.hermeskotlin.core.chat.linkFileUris("Saved to file:///opt/data/report.md. Also [x](file:///a.md) and `file:///code.md`"),
        )
        assertEquals("Already <file:///a.md>", dev.hermeskotlin.core.chat.linkFileUris("Already <file:///a.md>"))
        assertEquals("no links", dev.hermeskotlin.core.chat.linkFileUris("no links"))
    }

    @Test
    fun aFileUriAlreadyInALinkIsntLinkedAgain() {
        listOf(
            "[see file:///a.md](https://x.dev) then",
            "Open <https://h.dev/x?u=file:///y> now",
            "Open https://h.dev/x?u=file:///y now",
            "Open https://h.dev/file:///y now",
            "Open https://h.dev/#file:///y or https://h.dev/?file:///y now",
        ).forEach { assertEquals(it, dev.hermeskotlin.core.chat.linkFileUris(it)) }
        assertEquals(
            "[a](https://x.dev) and <file:///b.md>",
            dev.hermeskotlin.core.chat.linkFileUris("[a](https://x.dev) and file:///b.md"),
        )
        assertEquals(
            "![chart](https://x.dev/c.png)<br>see <file:///b.md>",
            dev.hermeskotlin.core.chat.linkFileUris("![chart](https://x.dev/c.png)<br>see file:///b.md"),
        )
    }
}
