package dev.hermeskotlin.core.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A screen as the chat draws it: the call that made it ([owner], whose card it is), every message sent to its
 * surfaces since, by it and by later calls that only update it ([screen]), and the newest of those calls
 * ([latest]), whose running and answer the card goes by.
 */
data class ScreenView(val owner: ToolActivity, val latest: ToolActivity, val screen: AgentScreen)

/**
 * The chat's screens, by the id of the call whose card draws each. A call's messages for a surface it makes
 * (`createSurface`) are its own screen; its messages for a surface an earlier call made (`updateComponents`,
 * `updateDataModel`, `deleteSurface`) go to that call's screen, as A2UI updates a surface already shown. A call
 * with nothing of its own has no card. A running call's updates wait until the phone [took] them: one it turns
 * down mustn't flash on screen and then take the card's typing with it.
 */
fun screenViews(calls: List<ToolActivity>, took: (AgentScreen) -> Boolean = { true }): Map<String, ScreenView> {
    val views = LinkedHashMap<String, ScreenView>()
    val ownerOf = HashMap<String, String>()
    for (call in calls) {
        val screen = call.agentScreen ?: continue
        val parsed = screen.messages.zip(screen.envelopes)
        val made = parsed.mapNotNullTo(HashSet()) { (_, e) -> e?.surfaceId?.takeIf { e.creates } }
        val own = parsed.filter { (_, e) -> e == null || e.surfaceId in made || e.surfaceId !in ownerOf }
        // What the phone turned down was never drawn there, and isn't here either. An update the phone hasn't
        // taken yet waits; one that ended later (stopped, not seen) did change the screen.
        val notDrawn = !call.running && call.failed && NOT_DRAWN in call.output.orEmpty()
        val refused = notDrawn || call.running && !took(screen)
        if (!refused) parsed.filterNot { it in own }.groupBy { (_, e) -> ownerOf.getValue(e!!.surfaceId) }.forEach { (owner, sent) ->
            val view = views.getValue(owner)
            val updated = view.screen.copy(messages = view.screen.messages + sent.map { it.first }, wait = screen.wait)
            // One that would leave a layout Kyllene won't draw is left out: the screen stays as it was.
            if (updated.layoutProblem == null) views[owner] = view.copy(latest = call, screen = updated)
        }
        // Only a call that makes a surface gets a card. One that updates a surface the chat hasn't loaded (an
        // older page) has none to draw on; one that updates a surface never made was turned down by the phone.
        if (!notDrawn && screen.layoutProblem == null && own.any { (_, e) -> e?.creates == true }) {
            val ids = own.mapNotNullTo(LinkedHashSet()) { (_, e) -> e?.surfaceId }
            ids.forEach { ownerOf[it] = call.id }
            views[call.id] = ScreenView(call, call, AgentScreen(own.map { it.first }, ids, screen.wait))
        }
    }
    return views
}

/**
 * How every error the phone gives for messages it wouldn't draw starts: the call changed nothing on screen, unlike
 * one that ended unanswered after it was drawn.
 */
const val NOT_DRAWN = "The screen wasn't drawn: "

/** What the chat needs of one A2UI message: the surface it's for, and whether it makes that surface. */
internal class A2uiEnvelope(val surfaceId: String, val creates: Boolean) {
    companion object {
        fun of(message: String): A2uiEnvelope? {
            val json = runCatching { Json.parseToJsonElement(message) }.getOrNull() as? JsonObject ?: return null
            val (kind, body) = json.entries.firstOrNull { (k, v) -> k != "version" && v is JsonObject } ?: return null
            val id = ((body as JsonObject)["surfaceId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
            return A2uiEnvelope(id, kind == "createSurface")
        }
    }
}
