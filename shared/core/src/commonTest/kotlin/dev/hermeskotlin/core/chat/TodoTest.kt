package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.rpc.GatewayEvent
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodoTest {

    private fun todos(revision: Int, vararg items: String) =
        """{"revision":$revision,"todos":[${items.joinToString(",")}]}"""

    private fun item(id: Any, status: String, content: String = "Step $id", parent: String? = null) =
        """{"id":${if (id is Int) id else "\"$id\""},"content":"$content","status":"$status"${parent?.let { ""","parent":"$it"""" } ?: ""}}"""

    private fun event(type: String, payload: String = "{}") =
        GatewayEvent(type, HermesJson.parseToJsonElement(payload), sessionId = "rt", seq = null)

    @Test
    fun parsesTheSnapshotAndCountsWithoutCancelledSteps() {
        val list = TodoList.parse(
            HermesJson.parseToJsonElement(
                todos(3, item(1, "completed"), item("b", "in_progress"), item("c", "cancelled"), item("d", "bogus")),
            ) as JsonObject,
        )!!
        assertEquals(listOf("1", "b", "c"), list.items.map { it.id })
        assertEquals(TodoStatus.InProgress, list.items[1].status)
        assertEquals(1, list.done)
        assertEquals(2, list.total)
        assertTrue(list.active)
    }

    @Test
    fun anUnusedStoreIsNoPlan() {
        assertNull(TodoList.parse(HermesJson.parseToJsonElement(todos(0)) as JsonObject))
    }

    @Test
    fun subtasksSitUnderTheirParent() {
        val list = TodoList.parse(
            HermesJson.parseToJsonElement(
                todos(1, item("a", "pending"), item("b", "pending"), item("a1", "pending", parent = "a")),
            ) as JsonObject,
        )!!
        assertEquals(listOf("a" to 0, "a1" to 1, "b" to 0), list.tree().map { (it, depth) -> it.id to depth })
    }

    @Test
    fun aLateOlderSnapshotIsIgnored() {
        val state = ChatState().reduce(event("todo.updated", todos(2, item("a", "completed"))))
            .reduce(event("todo.updated", todos(1, item("a", "pending"))))
        assertEquals(TodoStatus.Completed, state.todos!!.items.single().status)
    }

    @Test
    fun aNewTurnShowsTheLastPlanAsPastUntilItPlansAgain() {
        val planned = ChatState(running = true).reduce(event("todo.updated", todos(1, item("a", "pending"))))
        assertTrue(planned.todosLive)
        val next = planned.reduce(event("message.complete", """{"text":"ok","status":"complete"}""")).reduce(event("message.start"))
        assertFalse(next.todosLive)
        assertTrue(next.reduce(event("todo.updated", todos(2, item("b", "pending")))).todosLive)
    }

    @Test
    fun thePlanOutlivesItsTurnDoneOrNot() {
        val running = ChatState(running = true).reduce(event("todo.updated", todos(1, item("a", "in_progress"))))
        // The agent stopped without ticking it off: still worth looking back at.
        assertTrue(running.reduce(event("message.complete", """{"status":"interrupted"}""")).todos!!.active)

        val finished = running.reduce(event("todo.updated", todos(2, item("a", "completed"))))
            .reduce(event("message.complete", """{"text":"Done","status":"complete"}"""))
        assertFalse(finished.todos!!.active)
    }
}
