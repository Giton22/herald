package dev.hermeskotlin.core.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class TodoStatus { Pending, InProgress, Completed, Cancelled }

/** One step of the agent's plan (tools/todo_tool.py); [parent] nests it under another step. */
data class TodoItem(
    val id: String,
    val content: String,
    val status: TodoStatus,
    val parent: String? = null,
)

/** The agent's plan for the running turn, as the `todo_list` tool last wrote it. */
data class TodoList(val items: List<TodoItem>, val revision: Int) {
    /** Something is still to do; a list that's all done or cancelled is finished. */
    val active: Boolean get() = items.any { it.status == TodoStatus.Pending || it.status == TodoStatus.InProgress }

    /** Cancelled steps count on neither side, as on Desktop. */
    val done: Int get() = items.count { it.status == TodoStatus.Completed }
    val total: Int get() = items.count { it.status != TodoStatus.Cancelled }

    /** Items in reading order with their nesting depth: each one under its parent, in list order. */
    fun tree(): List<Pair<TodoItem, Int>> {
        val ids = items.mapTo(HashSet()) { it.id }
        val children = items.filter { it.parent in ids }.groupBy { it.parent }
        val out = mutableListOf<Pair<TodoItem, Int>>()
        val seen = HashSet<String>()
        fun visit(item: TodoItem, depth: Int) {
            if (!seen.add(item.id)) return
            out += item to depth
            children[item.id].orEmpty().forEach { visit(it, depth + 1) }
        }
        items.filter { it.parent !in ids }.forEach { visit(it, 0) }
        // A parent cycle leaves items unvisited; show them flat rather than lose them.
        items.filter { it.id !in seen }.forEach { visit(it, 0) }
        return out
    }

    companion object {
        /**
         * A `todo.updated` payload or `todo_state` snapshot (`{todos, revision}`); null when malformed or
         * when it's the empty list of an agent that never planned.
         */
        fun parse(snapshot: JsonObject?): TodoList? {
            val todos = snapshot?.get("todos") as? JsonArray ?: return null
            val revision = snapshot.int("revision") ?: 0
            val items = todos.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val status = when (item.string("status")) {
                    "pending" -> TodoStatus.Pending
                    "in_progress" -> TodoStatus.InProgress
                    "completed" -> TodoStatus.Completed
                    "cancelled" -> TodoStatus.Cancelled
                    else -> return@mapNotNull null
                }
                val id = item.text("id") ?: return@mapNotNull null
                val content = item.text("content") ?: return@mapNotNull null
                TodoItem(id, content, status, item.text("parent")?.takeIf { it != id })
            }
            if (items.isEmpty() && revision == 0) return null
            return TodoList(items, revision)
        }

        /** Ids may come as numbers. */
        private fun JsonObject.text(key: String): String? =
            (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
}
