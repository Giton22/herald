package dev.hermeskotlin.core.voice

import dev.hermeskotlin.core.chat.ApprovalChoice
import dev.hermeskotlin.core.chat.ClarifyQuestion
import dev.hermeskotlin.core.chat.InputRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceAnswersTest {

    private val approval = InputRequest.Approval(
        id = "srq-1",
        command = "rm -rf build",
        description = "Delete the build folder",
        toolName = "terminal",
        choices = ApprovalChoice.entries,
    )
    private val onceOnly = approval.copy(choices = listOf(ApprovalChoice.Once, ApprovalChoice.Deny))

    @Test
    fun onlyQuestionsAndApprovalsAreAskedAloud() {
        assertTrue(VoiceAnswers.canAsk(approval))
        assertTrue(VoiceAnswers.canAsk(InputRequest.Clarify("srq-2", listOf(ClarifyQuestion(null, "Which?")))))
        assertFalse(VoiceAnswers.canAsk(InputRequest.Secret("srq-3", InputRequest.Secret.Kind.Sudo, "Password", null, "ls")))
    }

    @Test
    fun theApprovalPromptOffersOnlyWhatTheGatewayAccepts() {
        assertEquals(
            "Hermes needs your approval: Delete the build folder. Say yes, for this chat, always, or no.",
            VoiceAnswers.approvalPrompt(approval),
        )
        assertEquals("Hermes needs your approval: rm -rf build. Say yes, or no.", VoiceAnswers.approvalPrompt(onceOnly.copy(description = "")))
    }

    @Test
    fun approvalsAreHeardInPlainWords() {
        assertEquals(ApprovalChoice.Once, VoiceAnswers.approval(approval, "Yes."))
        assertEquals(ApprovalChoice.Once, VoiceAnswers.approval(approval, "Sure, go ahead"))
        assertEquals(ApprovalChoice.Session, VoiceAnswers.approval(approval, "Yes, for this chat"))
        assertEquals(ApprovalChoice.Always, VoiceAnswers.approval(approval, "allow always"))
        assertEquals(ApprovalChoice.Deny, VoiceAnswers.approval(approval, "No, don't."))
        assertEquals(ApprovalChoice.Deny, VoiceAnswers.approval(approval, "do not run that"))
    }

    @Test
    fun anyNegationDenies() {
        for (said in listOf("I wouldn't do it", "you shouldn't do it", "can't you just do it", "Don’t do it", "won't that break it")) {
            assertEquals(ApprovalChoice.Deny, VoiceAnswers.approval(approval, said), said)
        }
    }

    @Test
    fun aQuestionOrADoubtIsNoYes() {
        for (said in listOf("okay, what does it do?", "what happens if you do it", "Yes, but first tell me what it does", "maybe later")) {
            assertNull(VoiceAnswers.approval(approval, said), said)
        }
    }

    @Test
    fun notKnowingIsAskedAgainNotDenied() {
        for (said in listOf(
            "I don't know what that does", "I don’t know, what does it touch?", "not sure", "no wait, let me think",
            "no idea", "hang on", "dunno", "hold on, no",
        )) {
            assertNull(VoiceAnswers.approval(approval, said), said)
        }
        // A plain no still is one.
        assertEquals(ApprovalChoice.Deny, VoiceAnswers.approval(approval, "No, I don't want that"))
        assertEquals(ApprovalChoice.Deny, VoiceAnswers.approval(approval, "no"))
    }

    @Test
    fun aGrantWordInPassingIsNoYes() {
        for (said in listOf("okay, let me think", "allow me a second", "I'm fine", "do it later", "close the session", "show it to me in the chat", "always for this chat",
            // A question that lost its "?" on the way.
            "is that okay", "can you run it", "is it for this chat")) {
            assertNull(VoiceAnswers.approval(approval, said), said)
        }
        assertEquals(ApprovalChoice.Once, VoiceAnswers.approval(approval, "okay, you can run it now"))
        assertEquals(ApprovalChoice.Session, VoiceAnswers.approval(approval, "for the session"))
    }

    @Test
    fun alwaysIsOnlyGrantedWhenSaidAsAGrant() {
        assertEquals(ApprovalChoice.Always, VoiceAnswers.approval(approval, "Always."))
        assertEquals(ApprovalChoice.Always, VoiceAnswers.approval(approval, "yes, always allow it"))
        assertNull(VoiceAnswers.approval(approval, "I always forget, go ahead"))
        assertNull(VoiceAnswers.approval(approval, "yes, but always ask me first"))
    }

    @Test
    fun aNumberInASentenceIsJustAWord() {
        val question = ClarifyQuestion(null, "Which?", listOf("The database", "Blue icon"))
        assertEquals(listOf("Blue icon"), VoiceAnswers.clarify(question, "the one with the blue icon"))
        assertEquals(listOf("wait 5 minutes"), VoiceAnswers.clarify(question, "wait 5 minutes"))
        assertEquals(listOf("The database"), VoiceAnswers.clarify(question, "first, let me think… the database"))
    }

    @Test
    fun anUnclearOrUnofferedApprovalIsAskedAgain() {
        assertNull(VoiceAnswers.approval(approval, "what does it do?"))
        assertNull(VoiceAnswers.approval(approval, ""))
        // Not narrowed to once without saying so.
        assertNull(VoiceAnswers.approval(onceOnly, "always"))
    }

    @Test
    fun choicesAreReadWithTheirNumbers() {
        val question = ClarifyQuestion(null, "Which color?", listOf("Red", "Blue"))
        assertEquals("Which color? Say one. 1: Red. 2: Blue. Or say something else.", VoiceAnswers.questionPrompt(question))
        assertEquals("Anything else?", VoiceAnswers.questionPrompt(ClarifyQuestion(null, "Anything else?")))
    }

    @Test
    fun aChoiceIsPickedByItsNumberOrItsWords() {
        val question = ClarifyQuestion(null, "Which color?", listOf("Red", "Light blue"))
        assertEquals(listOf("Light blue"), VoiceAnswers.clarify(question, "The second one."))
        assertEquals(listOf("Red"), VoiceAnswers.clarify(question, "number 1"))
        assertEquals(listOf("Light blue"), VoiceAnswers.clarify(question, "light blue please"))
        assertEquals(listOf("Light blue"), VoiceAnswers.clarify(question, "the last one"))
    }

    @Test
    fun anAnswerThatNamesNoChoiceGoesAsSaid() {
        val question = ClarifyQuestion(null, "Which color?", listOf("Red", "Blue"))
        assertEquals(listOf("Green, actually"), VoiceAnswers.clarify(question, " Green, actually "))
        assertEquals(listOf("Tomorrow at noon"), VoiceAnswers.clarify(ClarifyQuestion(null, "When?"), "Tomorrow at noon"))
        assertEquals(emptyList(), VoiceAnswers.clarify(question, "  "))
    }

    @Test
    fun aMultiSelectTakesEveryOneNamed() {
        val question = ClarifyQuestion(null, "Which days?", listOf("Monday", "Tuesday", "Friday"), multiSelect = true)
        assertEquals(listOf("Monday", "Friday"), VoiceAnswers.clarify(question, "one and three"))
        // One to pick and two named: the words go as said, for Hermes to read.
        assertEquals(listOf("one and three"), VoiceAnswers.clarify(question.copy(multiSelect = false), "one and three"))
        val keep = ClarifyQuestion(null, "What now?", listOf("Delete it", "Keep it"))
        assertEquals(listOf("keep it, don't delete it"), VoiceAnswers.clarify(keep, "keep it, don't delete it"))
    }

    @Test
    fun whatsTakenAsTheAnswerWithoutTheVoiceHandingItOver() {
        val color = InputRequest.Clarify("srq-2", listOf(ClarifyQuestion(null, "Which color?", listOf("Red", "Blue"))))
        // Anything that answers, a choice or not.
        listOf(
            "Green", "Blue please", "the second one", "I think red", "um, blue", "Umm… blue", "how about blue", "tell it red",
            "give me red", "do the first one", "sorry, the blue one", "when the tests pass", "use a second account",
            "none of the options", "poll every one minute", "second", "give me one of each", "give me a few examples",
            "give me updates every minute",
        ).forEach { assertTrue(VoiceAnswers.takesAsAnswer(color, it), it) }
        // A question back, a hedge or not knowing is the voice's to talk through.
        listOf(
            "Which one is brighter", "what are the options", "is red darker", "hmm, wait", "sorry?", "", "  ", "um",
            "Umm…", "Hmmm", "Mhm", "Okay", "Yeah", "Alright", "let's see", "one sec", "One second.", "just a minute",
            "give me a second", "say the options again", "do I need to pick", "I'm not sure", "let me think", "I don't know",
            "I do not know", "dunno", "idk", "I didn't catch that", "one more time", "maybe", "hmm, maybe", "hang on",
            "when does it run", "two seconds", "give me two minutes", "sorry, I missed that", "come again", "Got it", "cool",
            "give me five minutes", "give me 5 minutes", "give me a couple of seconds", "give me a moment",
        ).forEach { assertFalse(VoiceAnswers.takesAsAnswer(color, it), it) }
        // A question without choices takes a plain yes.
        val push = InputRequest.Clarify("srq-4", listOf(ClarifyQuestion(null, "Push it?")))
        assertTrue(VoiceAnswers.takesAsAnswer(push, "Yeah"))
        assertFalse(VoiceAnswers.takesAsAnswer(push, "Got it"))
        // Several questions are never asked aloud.
        val two = color.copy(questions = color.questions + ClarifyQuestion(null, "Why?"))
        assertFalse(VoiceAnswers.takesAsAnswer(two, "Green"))
        // An approval only when it's clear.
        assertTrue(VoiceAnswers.takesAsAnswer(onceOnly, "yes"))
        assertTrue(VoiceAnswers.takesAsAnswer(onceOnly, "no, don't"))
        // A lone "okay" may only be "I heard you": the voice hands it over if it's the answer.
        assertFalse(VoiceAnswers.takesAsAnswer(onceOnly, "Okay"))
        assertFalse(VoiceAnswers.takesAsAnswer(onceOnly, "ok."))
        assertTrue(VoiceAnswers.takesAsAnswer(onceOnly, "um, yes"))
        assertTrue(VoiceAnswers.takesAsAnswer(onceOnly, "sorry, yes"))
        // Both steps judge it alike: what's taken is what's granted.
        assertEquals(ApprovalChoice.Once, VoiceAnswers.approval(onceOnly, "Um, yes."))
        assertEquals(ApprovalChoice.Once, VoiceAnswers.approval(onceOnly, "Okay"))
        assertNull(VoiceAnswers.approval(onceOnly, "Okay, can you run it"))
        // Not knowing isn't a no.
        listOf("I don't know what that does", "no wait, let me think", "not sure")
            .forEach { assertFalse(VoiceAnswers.takesAsAnswer(onceOnly, it), it) }
        assertFalse(VoiceAnswers.takesAsAnswer(onceOnly, "okay, let me think"))
        assertFalse(VoiceAnswers.takesAsAnswer(onceOnly, "what does it delete"))
    }
}
