package dev.hermeskotlin.core.wear

import dev.hermeskotlin.core.chat.InputAnswers
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.chat.choicesAway
import dev.hermeskotlin.core.chat.label
import kotlinx.serialization.json.JsonObject

/** How a watch answers a request: on the phone only, by picking one of its choices, or by those or dictating. */
enum class WatchAnswering { OnPhone, Choices, ChoicesOrText }

/**
 * An approval or a question as a watch shows it: [detail] is the command or the question (blank for a password or a
 * login, which never leave the phone), [choices] what can be picked.
 */
data class WatchRequestView(val approval: Boolean, val heading: String, val detail: String, val choices: List<String>, val answering: WatchAnswering)

/** [request] for a watch. One question, picking one answer, can be answered there; batches and multi-select can't. */
fun watchView(request: InputRequest): WatchRequestView = when (request) {
    is InputRequest.Approval -> WatchRequestView(
        approval = true,
        heading = "Approve this command?",
        detail = request.command.ifBlank { request.description },
        choices = request.choicesAway.map { it.label },
        answering = WatchAnswering.Choices,
    )
    is InputRequest.Clarify -> {
        val question = request.questions.singleOrNull()?.takeIf { !it.multiSelect }
        if (question != null) {
            // Blank ones are left out here, not on the watch: it answers by a choice's place among those shown.
            val choices = question.choices.filter { it.isNotBlank() }
            WatchRequestView(false, "Hermes has a question", question.question, choices, WatchAnswering.ChoicesOrText)
        } else {
            val detail = request.questions.joinToString("\n") { it.question }
            WatchRequestView(false, "Hermes has questions", detail, emptyList(), WatchAnswering.OnPhone)
        }
    }
    // Passwords, codes and logins are typed in the app only, and what they're for stays there too.
    is InputRequest.Secret, is InputRequest.VaultSaveLogin -> WatchRequestView(false, "Hermes needs a password", "", emptyList(), WatchAnswering.OnPhone)
}

/**
 * How a watch answers a ring for [request] once it's picked up there: as [watchView] says while [answersOnWatch],
 * else on the phone only, and so for a call from the agent (no [request]), which only the phone can take.
 */
fun ringAnswering(request: InputRequest?, answersOnWatch: Boolean): WatchAnswering =
    if (request != null && answersOnWatch) watchView(request).answering else WatchAnswering.OnPhone

/**
 * The gateway's answer to [request] from a watch: choice [index] of [watchView]'s choices, or [text] dictated. Null
 * when it doesn't fit the request (it changed, or can't be answered there).
 */
fun watchAnswer(request: InputRequest, index: Int?, text: String?): JsonObject? {
    val view = watchView(request)
    if (view.answering == WatchAnswering.OnPhone) return null
    return when (request) {
        is InputRequest.Approval -> index?.let(request.choicesAway::getOrNull)?.let(InputAnswers::approval)
        is InputRequest.Clarify -> {
            val answer = index?.let(view.choices::getOrNull) ?: text?.trim()?.takeIf { it.isNotEmpty() && view.answering == WatchAnswering.ChoicesOrText }
            answer?.let { InputAnswers.clarify(request, listOf(listOf(it))) }
        }
        else -> null
    }
}
