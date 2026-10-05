package dev.hermeskotlin.core.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** How far an approval reaches: this command once, the pattern for the session, or permanently. */
enum class ApprovalChoice(val wire: String) {
    Once("once"),
    Session("session"),
    Always("always"),
    Deny("deny"),
    ;

    companion object {
        fun fromWire(value: String): ApprovalChoice? = entries.firstOrNull { it.wire == value }
    }
}

data class ClarifyQuestion(
    /** Batch question id; null for a single-question request. */
    val qid: String?,
    val question: String,
    val choices: List<String> = emptyList(),
    val multiSelect: Boolean = false,
)

/**
 * Something the agent is blocked on until a person answers: a server→client request
 * (tui_gateway/server_requests.py) that this client knows how to show.
 */
sealed interface InputRequest {
    /** The `srq-…` request id the answer is sent back under. */
    val id: String

    /** A dangerous command waits for a decision. The gateway already redacted [command]. */
    data class Approval(
        override val id: String,
        val command: String,
        val description: String,
        val toolName: String?,
        /** What the gateway will accept, in its order; always holds [ApprovalChoice.Once] and [ApprovalChoice.Deny]. */
        val choices: List<ApprovalChoice>,
    ) : InputRequest

    /** The clarify tool: one question, or a batch answered together. */
    data class Clarify(
        override val id: String,
        val questions: List<ClarifyQuestion>,
    ) : InputRequest {
        val batch: Boolean get() = questions.any { it.qid != null }
    }

    /**
     * A one-line value: the sudo password for a terminal command, a named secret, the master password
     * of a password manager ([Kind.VaultUnlock]) or a sign-in code for the page the agent is on
     * ([Kind.VaultCode], the only one not masked).
     */
    data class Secret(
        override val id: String,
        val kind: Kind,
        val prompt: String,
        /** The env var a [Kind.Secret] fills, e.g. `OPENAI_API_KEY`. */
        val envVar: String?,
        /** The command a [Kind.Sudo] password is for. */
        val command: String?,
    ) : InputRequest {
        enum class Kind { Sudo, Secret, VaultUnlock, VaultCode }
    }

    /**
     * The agent is on a sign-in page with nothing in the vault: save a login for it. Answered with
     * [InputAnswers.saveLogin], or an empty value to not save.
     */
    data class VaultSaveLogin(
        override val id: String,
        /** The site's name as the agent shows it, e.g. `github.com`. */
        val site: String,
        /** The page's origin, e.g. `https://github.com`. */
        val origin: String,
    ) : InputRequest {
        /** The question, without a blank where the site goes when the gateway named none. */
        val title: String get() = if (site.isBlank()) "Save a login?" else "Save your $site login?"
    }

    companion object {
        /** The methods this client answers; anything else is left for another client. */
        val METHODS = setOf("approval", "clarify", "sudo", "secret", "vault.unlock_prompt", "vault.code", "vault.save_login")

        fun parse(id: String, method: String, params: JsonObject): InputRequest? = when (method) {
            "approval" -> Approval(
                id = id,
                command = params.string("command").orEmpty(),
                description = params.string("description").orEmpty(),
                toolName = params.string("tool_name"),
                choices = approvalChoices(params),
            )
            "clarify" -> clarifyQuestions(params).takeIf { it.isNotEmpty() }?.let { Clarify(id, it) }
            "sudo" -> Secret(id, Secret.Kind.Sudo, "Password for sudo", envVar = null, command = params.string("command"))
            "secret" -> Secret(
                id = id,
                kind = Secret.Kind.Secret,
                prompt = params.string("prompt")?.takeIf { it.isNotBlank() } ?: "Value for ${params.string("env_var").orEmpty()}",
                envVar = params.string("env_var"),
                command = null,
            )
            "vault.unlock_prompt" -> {
                val name = params.string("display_name")?.takeIf { it.isNotBlank() }
                    ?: params.string("backend")?.takeIf { it.isNotBlank() }
                    ?: "your password manager"
                Secret(id, Secret.Kind.VaultUnlock, "Hermes needs $name unlocked to sign in for you.", envVar = null, command = null)
            }
            "vault.code" -> {
                val site = params.string("site")?.takeIf { it.isNotBlank() }
                val hint = params.string("hint")?.takeIf { it.isNotBlank() }
                val ask = if (site != null) "Enter the sign-in code for $site." else "Enter the sign-in code."
                Secret(id, Secret.Kind.VaultCode, listOfNotNull(ask, hint).joinToString(" "), envVar = null, command = null)
            }
            "vault.save_login" -> {
                val origin = params.string("origin").orEmpty()
                VaultSaveLogin(id, site = params.string("site")?.takeIf { it.isNotBlank() } ?: origin, origin = origin)
            }
            else -> null
        }

        /** The payload's own `choices` when present, else the gateway's rule (`_approval_request_payload`). */
        private fun approvalChoices(params: JsonObject): List<ApprovalChoice> {
            val offered = (params["choices"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(ApprovalChoice::fromWire) }
                ?.takeIf { it.isNotEmpty() }
            val choices = offered ?: buildList {
                add(ApprovalChoice.Once)
                if (params.boolean("smart_denied") != true && params.boolean("allow_session") != false) {
                    add(ApprovalChoice.Session)
                    if (params.boolean("allow_permanent") != false) add(ApprovalChoice.Always)
                }
            }
            return (listOf(ApprovalChoice.Once) + choices + ApprovalChoice.Deny).distinct().sortedBy { it.ordinal }
        }

        private fun clarifyQuestions(params: JsonObject): List<ClarifyQuestion> {
            val batch = (params["questions"] as? JsonArray)?.mapNotNull { item ->
                val question = item as? JsonObject ?: return@mapNotNull null
                ClarifyQuestion(
                    qid = question.string("qid") ?: return@mapNotNull null,
                    question = question.string("question").orEmpty(),
                    choices = question.strings("choices"),
                    multiSelect = question.boolean("multi_select") == true,
                )
            }
            if (!batch.isNullOrEmpty()) return batch
            val question = params.string("question") ?: return emptyList()
            return listOf(ClarifyQuestion(null, question, params.strings("choices"), params.boolean("multi_select") == true))
        }
    }
}

/** The response `result` for each kind of answer, shaped as the gateway contract wants it. */
object InputAnswers {
    fun approval(choice: ApprovalChoice): JsonObject = buildJsonObject { put("choice", choice.wire) }

    /**
     * One entry per question. A multi-select answer is the picked options as a JSON array string,
     * like Desktop sends it; an empty answer means skipped.
     */
    fun clarify(request: InputRequest.Clarify, answers: List<List<String>>): JsonObject {
        val texts = answers.map { picked ->
            when (picked.size) {
                0 -> ""
                1 -> picked.single()
                else -> buildJsonArray { picked.forEach { add(JsonPrimitive(it)) } }.toString()
            }
        }
        return buildJsonObject {
            if (request.batch) {
                put("answers", buildJsonObject { request.questions.zip(texts).forEach { (q, text) -> put(q.qid.orEmpty(), text) } })
            } else {
                put("answer", texts.firstOrNull().orEmpty())
            }
        }
    }

    /** `{}` (neither `answer` nor `answers`) cancels every question at once. */
    val clarifyCancel: JsonObject = JsonObject(emptyMap())

    /** An empty [value] declines the prompt. */
    fun value(value: String): JsonObject = buildJsonObject { put("value", value) }

    /**
     * A [InputRequest.VaultSaveLogin] answer: the login as a JSON string in `value`. The gateway drops
     * one without a password, so send [value] `""` to not save.
     */
    fun saveLogin(identifier: String, password: String): JsonObject = value(
        buildJsonObject {
            put("identifier", identifier)
            put("password", password)
        }.toString(),
    )
}

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

internal fun JsonElement?.asObjectList(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
