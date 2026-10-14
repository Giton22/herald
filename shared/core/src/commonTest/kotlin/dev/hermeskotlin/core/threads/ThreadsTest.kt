package dev.hermeskotlin.core.threads

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.sessions.SessionSummary
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ThreadsTest {

    private val a = ChatThread("a", "Misc", "s-a")
    private val b = ChatThread("b", "Work", "s-b", cwd = "/srv/work")
    private val c = ChatThread("c", "Home", "s-c")
    private val list = listOf(a, b, c)

    @Test
    fun aThreadIsAddedAtTheEndAndOnlyOncePerChat() {
        val d = ChatThread("d", "New", "s-d")
        assertEquals(listOf(a, b, c, d), list.withThread(d))
        assertSame(list, list.withThread(ChatThread("e", "Again", "s-a")))
    }

    @Test
    fun removingAThreadKeepsTheOthersInOrder() {
        assertEquals(listOf(a, c), list.withoutThread("b"))
        assertEquals(list, list.withoutThread("nope"))
    }

    @Test
    fun renamingChangesOnlyTheNameAndABlankNameNothing() {
        assertEquals("Errands", list.renamed("a", "  Errands ").first().name)
        assertEquals("s-a", list.renamed("a", "Errands").first().sessionId)
        assertSame(list, list.renamed("a", "   "))
    }

    @Test
    fun aThreadIsPointedAtItsNewChat() {
        assertEquals("s-new", list.pointedAt("b", "s-new")[1].sessionId)
        assertEquals("s-a", list.pointedAt("b", "s-new")[0].sessionId)
    }

    @Test
    fun threadsMoveUpDownAndToAPlace() {
        assertEquals(listOf(b, a, c), list.movedPast("b", "a"))
        assertEquals(listOf(a, c, b), list.movedPast("b", "c"))
        // Past the next shown thread when a hidden one sits between them.
        assertEquals(listOf(c, a, b), list.movedPast("c", "a"))
        assertEquals(listOf(b, c, a), list.movedPast("a", "c"))
        assertEquals(list, list.movedPast("a", "gone"))
        assertEquals(list, list.movedPast("gone", "a"))
        assertEquals(listOf(b, c, a), list.moved("a", 99))
        assertEquals(listOf(c, a, b), list.moved("c", -3))
        assertEquals(list, list.moved("nope", 0))
    }

    @Test
    fun anOrderKeepsThreadsItDoesntName() {
        val d = ChatThread("d", "Added elsewhere", "s-d")
        assertEquals(listOf(c, a, b, d), (list + d).ordered(listOf("c", "a", "gone", "b")))
    }

    @Test
    fun anOrderDraggedInANarrowedListLeavesTheHiddenThreadsInPlace() {
        // b is hidden: a and c swap the places they had, b stays second.
        assertEquals(listOf(c, b, a), list.ordered(listOf("c", "a")))
    }

    @Test
    fun theStoredListReadsBackAsWritten() {
        val block = ThreadMeta.write(list, JsonObject(mapOf("other" to JsonPrimitive(1))))
        assertEquals(list, ThreadMeta.parse(block))
        // Keys this app doesn't know are kept.
        assertEquals(JsonPrimitive(1), block["other"])
        assertNull(block["threads"]!!.jsonArray[0].let { (it as JsonObject)["cwd"] })
    }

    @Test
    fun missingOrBrokenStorageReadsAsNoThreads() {
        assertEquals(emptyList(), ThreadMeta.parse(null))
        assertEquals(emptyList(), ThreadMeta.parse(JsonPrimitive("junk")))
        assertEquals(emptyList(), ThreadMeta.parse(HermesJson.parseToJsonElement("""{"threads":"nope"}""")))
        val mixed = HermesJson.parseToJsonElement(
            """{"threads":[
                {"id":"a","name":"Misc","sessionId":"s-a"},
                {"id":"a","name":"Twice","sessionId":"s-x"},
                {"id":"b","name":"","sessionId":"s-b"},
                {"id":"c","name":"No chat"},
                {"id":3,"name":"Numeric id","sessionId":"s-3"},
                "junk",
                {"id":"d","name":"Work","sessionId":"s-d","cwd":"/srv/work","extra":true}
            ]}""",
        )
        assertEquals(listOf(ChatThread("a", "Misc", "s-a"), ChatThread("d", "Work", "s-d", "/srv/work")), ThreadMeta.parse(mixed))
    }

    @Test
    fun aThreadHoldsItsChatAfterCompressionMovedIt() {
        assertTrue(a.holds(SessionSummary("s-a")))
        assertTrue(a.holds(SessionSummary("tip", lineageIds = listOf("s-a", "tip"))))
        assertEquals(false, a.holds(SessionSummary("other")))
    }

    @Test
    fun threadIdsAreShortAndPlain() {
        val id = newThreadId(Random(1))
        assertEquals(12, id.length)
        assertTrue(id.all { it.isLetterOrDigit() && !it.isUpperCase() })
    }
}
