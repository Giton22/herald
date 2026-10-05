package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.network.HermesJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test
    fun vaultUnlockNamesThePasswordManager() {
        val named = assertIs<InputRequest.Secret>(
            InputRequest.parse("srq-1", "vault.unlock_prompt", params("""{"session_id":"s","backend":"bitwarden","display_name":"Bitwarden"}""")),
        )
        assertEquals(InputRequest.Secret.Kind.VaultUnlock, named.kind)
        assertEquals("Hermes needs Bitwarden unlocked to sign in for you.", named.prompt)

        val backendOnly = assertIs<InputRequest.Secret>(InputRequest.parse("srq-2", "vault.unlock_prompt", params("""{"backend":"onepassword","display_name":""}""")))
        assertEquals("Hermes needs onepassword unlocked to sign in for you.", backendOnly.prompt)

        val bare = assertIs<InputRequest.Secret>(InputRequest.parse("srq-3", "vault.unlock_prompt", params("{}")))
        assertEquals("Hermes needs your password manager unlocked to sign in for you.", bare.prompt)
    }

    @Test
    fun vaultCodeNamesTheSiteAndHint() {
        val full = assertIs<InputRequest.Secret>(
            InputRequest.parse("srq-1", "vault.code", params("""{"session_id":"s","site":"github.com","hint":"Sent to your phone."}""")),
        )
        assertEquals(InputRequest.Secret.Kind.VaultCode, full.kind)
        assertEquals("Enter the sign-in code for github.com. Sent to your phone.", full.prompt)

        // The gateway sends an empty hint today.
        val noHint = assertIs<InputRequest.Secret>(InputRequest.parse("srq-2", "vault.code", params("""{"site":"github.com","hint":""}""")))
        assertEquals("Enter the sign-in code for github.com.", noHint.prompt)

        val bare = assertIs<InputRequest.Secret>(InputRequest.parse("srq-3", "vault.code", params("{}")))
        assertEquals("Enter the sign-in code.", bare.prompt)
    }

    @Test
    fun vaultSaveLoginFallsBackToTheOrigin() {
        val full = assertIs<InputRequest.VaultSaveLogin>(
            InputRequest.parse("srq-1", "vault.save_login", params("""{"session_id":"s","origin":"https://github.com","site":"github.com"}""")),
        )
        assertEquals("github.com", full.site)
        assertEquals("https://github.com", full.origin)

        val noSite = assertIs<InputRequest.VaultSaveLogin>(InputRequest.parse("srq-2", "vault.save_login", params("""{"origin":"https://github.com"}""")))
        assertEquals("https://github.com", noSite.site)
        assertEquals(Waiting.Input, Waiting.of(noSite))
    }

    @Test
    fun theVaultMethodsAreAnswered() {
        assertTrue(listOf("vault.unlock_prompt", "vault.code", "vault.save_login").all { it in InputRequest.METHODS })
    }

    @Test
    fun saveLoginEncodesTheLoginAsAJsonString() {
        val answer = InputAnswers.saveLogin("ada@example.com", """pa"ss\word""")
        val login = HermesJson.parseToJsonElement(answer["value"]!!.jsonPrimitive.content).jsonObject
        assertEquals("ada@example.com", login["identifier"]!!.jsonPrimitive.content)
        assertEquals("""pa"ss\word""", login["password"]!!.jsonPrimitive.content)
    }
}
