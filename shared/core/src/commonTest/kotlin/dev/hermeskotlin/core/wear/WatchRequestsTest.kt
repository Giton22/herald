package dev.hermeskotlin.core.wear

import dev.hermeskotlin.core.chat.ApprovalChoice
import dev.hermeskotlin.core.chat.ClarifyQuestion
import dev.hermeskotlin.core.chat.InputRequest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchRequestsTest {

    private val approval = InputRequest.Approval(
        "a1",
        command = "rm -rf build",
        description = "Delete the build folder",
        toolName = "terminal",
        choices = listOf(ApprovalChoice.Once, ApprovalChoice.Session, ApprovalChoice.Always, ApprovalChoice.Deny),
    )

    @Test
    fun anApprovalOffersItsChoicesButAlways() {
        val view = watchView(approval)
        assertEquals("rm -rf build", view.detail)
        assertEquals(listOf("Allow once", "Allow for chat", "Deny"), view.choices)
        assertEquals(WatchAnswering.Choices, view.answering)
    }

    @Test
    fun anApprovalIsAnsweredByTheChoiceShownNotItsPlaceInTheGatewaysList() {
        // Deny is third on the watch, fourth in the request.
        assertEquals(JsonPrimitive("deny"), watchAnswer(approval, 2, null)!!["choice"])
        assertEquals(JsonPrimitive("once"), watchAnswer(approval, 0, null)!!["choice"])
        assertNull(watchAnswer(approval, 3, null))
        assertNull(watchAnswer(approval, null, "yes please"))
    }

    @Test
    fun aSingleQuestionInBatchFormIsAnsweredOnTheWatch() {
        // Current Hermes sends even one question as a batch, its qid set.
        val question = InputRequest.Clarify("c1", listOf(ClarifyQuestion("q0", "Which colour?", listOf("Red (Recommended)", "Green"))))
        val view = watchView(question)
        assertEquals(WatchAnswering.ChoicesOrText, view.answering)
        assertEquals(listOf("Red (Recommended)", "Green"), view.choices)
        assertEquals(JsonPrimitive("Green"), watchAnswer(question, 1, null)!!["answers"]!!.jsonObject["q0"])
        assertEquals(JsonPrimitive("Blue"), watchAnswer(question, null, " Blue ")!!["answers"]!!.jsonObject["q0"])
        assertNull(watchAnswer(question, null, "  "))
    }

    @Test
    fun aBlankChoiceIsLeftOutSoTheWatchsPlacesMatch() {
        // The watch shows one choice per line and skips empty lines: a blank one sent would shift every place after it.
        val question = InputRequest.Clarify("c6", listOf(ClarifyQuestion("q0", "Go on?", listOf("", "Yes", "  ", "No"))))
        assertEquals(listOf("Yes", "No"), watchView(question).choices)
        assertEquals(JsonPrimitive("Yes"), watchAnswer(question, 0, null)!!["answers"]!!.jsonObject["q0"])
        assertEquals(JsonPrimitive("No"), watchAnswer(question, 1, null)!!["answers"]!!.jsonObject["q0"])
        assertNull(watchAnswer(question, 2, null))
    }

    @Test
    fun aQuestionWithoutABatchIdIsAnsweredPlainly() {
        val question = InputRequest.Clarify("c2", listOf(ClarifyQuestion(null, "Name?")))
        assertEquals(JsonPrimitive("Ada"), watchAnswer(question, null, "Ada")!!["answer"])
    }

    @Test
    fun aRingIsAnsweredOnTheWatchOnlyWhenAllowedAndNeverForTheAgentsCall() {
        val question = InputRequest.Clarify("c5", listOf(ClarifyQuestion("q0", "Which?", listOf("a", "b"))))
        assertEquals(WatchAnswering.ChoicesOrText, ringAnswering(question, answersOnWatch = true))
        assertEquals(WatchAnswering.Choices, ringAnswering(approval, answersOnWatch = true))
        assertEquals(WatchAnswering.OnPhone, ringAnswering(question, answersOnWatch = false))
        assertEquals(WatchAnswering.OnPhone, ringAnswering(null, answersOnWatch = true))
    }

    @Test
    fun multiSelectBatchesAndPasswordsStayOnThePhone() {
        val multi = InputRequest.Clarify("c3", listOf(ClarifyQuestion("q0", "Which?", listOf("a", "b"), multiSelect = true)))
        val batch = InputRequest.Clarify("c4", listOf(ClarifyQuestion("q0", "One?"), ClarifyQuestion("q1", "Two?")))
        val secret = InputRequest.Secret("s1", InputRequest.Secret.Kind.Sudo, "Password for sudo", null, "sudo apt upgrade")
        for (request in listOf(multi, batch, secret)) {
            assertEquals(WatchAnswering.OnPhone, watchView(request).answering)
            assertNull(watchAnswer(request, 0, "anything"))
        }
        // Not even what it's for leaves the phone.
        assertEquals("", watchView(secret).detail)
    }
}
