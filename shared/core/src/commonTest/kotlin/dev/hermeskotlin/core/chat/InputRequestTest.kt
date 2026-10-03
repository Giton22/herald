package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InputRequestTest {

    private fun params(json: String): JsonObject = HermesJson.parseToJsonElement(json).jsonObject

    @Test
    fun approvalUsesTheOfferedChoicesAndFallsBackToTheGatewayRule() {
        val offered = assertIs<InputRequest.Approval>(
            InputRequest.parse("srq-1", "approval", params("""{"session_id":"s","command":"rm -rf build","description":"recursive delete","choices":["once","deny"]}""")),
        )
        assertEquals("rm -rf build", offered.command)
        assertEquals(listOf(ApprovalChoice.Once, ApprovalChoice.Deny), offered.choices)

        val derived = assertIs<InputRequest.Approval>(InputRequest.parse("srq-2", "approval", params("""{"allow_permanent":false}""")))
        assertEquals(listOf(ApprovalChoice.Once, ApprovalChoice.Session, ApprovalChoice.Deny), derived.choices)

        val smartDenied = assertIs<InputRequest.Approval>(InputRequest.parse("srq-3", "approval", params("""{"smart_denied":true}""")))
        assertEquals(listOf(ApprovalChoice.Once, ApprovalChoice.Deny), smartDenied.choices)
    }

    @Test
    fun clarifyParsesSingleAndBatchQuestions() {
        val single = assertIs<InputRequest.Clarify>(
            InputRequest.parse("srq-1", "clarify", params("""{"question":"Which branch?","choices":["main","dev"]}""")),
        )
        assertEquals(listOf("main", "dev"), single.questions.single().choices)
        assertEquals(false, single.batch)

        val batch = assertIs<InputRequest.Clarify>(
            InputRequest.parse(
                "srq-2",
                "clarify",
                params("""{"questions":[{"qid":"a","question":"Name?"},{"qid":"b","question":"Tags?","choices":["x","y"],"multi_select":true}]}"""),
            ),
        )
        assertTrue(batch.batch)
        assertTrue(batch.questions[1].multiSelect)

        assertNull(InputRequest.parse("srq-3", "clarify", params("{}")))
        assertNull(InputRequest.parse("srq-4", "preview.read", params("{}")))
    }

    @Test
    fun answersMatchTheContract() {
        assertEquals("""{"choice":"session"}""", InputAnswers.approval(ApprovalChoice.Session).toString())
        assertEquals("""{"value":""}""", InputAnswers.value("").toString())

        val single = InputRequest.Clarify("srq-1", listOf(ClarifyQuestion(null, "Which?", listOf("a", "b"), multiSelect = true)))
        assertEquals("""{"answer":"[\"a\",\"b\"]"}""", InputAnswers.clarify(single, listOf(listOf("a", "b"))).toString())

        val batch = InputRequest.Clarify("srq-2", listOf(ClarifyQuestion("q1", "Name?"), ClarifyQuestion("q2", "Skip me?")))
        assertEquals("""{"answers":{"q1":"Ada","q2":""}}""", InputAnswers.clarify(batch, listOf(listOf("Ada"), emptyList())).toString())
    }

    @Test
    fun secretsCarryTheirPrompt() {
        val sudo = assertIs<InputRequest.Secret>(InputRequest.parse("srq-1", "sudo", params("""{"command":"apt install jq"}""")))
        assertEquals(InputRequest.Secret.Kind.Sudo, sudo.kind)
        assertEquals("apt install jq", sudo.command)

        val secret = assertIs<InputRequest.Secret>(InputRequest.parse("srq-2", "secret", params("""{"env_var":"API_KEY","prompt":""}""")))
        assertEquals("Value for API_KEY", secret.prompt)
    }
}
