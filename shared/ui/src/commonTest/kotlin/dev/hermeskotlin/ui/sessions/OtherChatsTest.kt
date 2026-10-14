package dev.hermeskotlin.ui.sessions

import dev.hermeskotlin.core.chat.ApprovalChoice
import dev.hermeskotlin.core.chat.ClarifyQuestion
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.chat.Waiting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtherChatsTest {

    private fun chats(vararg chat: OtherChat) = OtherChats(chat.associateBy { it.id })

    private fun idle(id: String, at: Double = 10.0) = OtherChat(id, id.uppercase(), activityAt = at)

    private fun reply(id: String, at: Double = 20.0) = OtherChat(id, id.uppercase(), unread = true, activityAt = at)

    private fun waiting(id: String, on: Waiting = Waiting.Approval) = OtherChat(id, id.uppercase(), waiting = on, running = true)

    // OtherChat, OtherChats, SessionsBadge, OtherChatEvent

    @Test
    fun aReplyIsUnreadWithNoTurnRunningAndNothingAsked() {
        assertTrue(reply("b").replied)
        assertFalse(reply("b").copy(running = true).replied)
        assertFalse(reply("b").copy(waiting = Waiting.Question).replied)
        assertFalse(idle("b").replied)
        assertTrue(reply("b").copy(waiting = Waiting.Question).needsYou)
        assertFalse(idle("b").copy(running = true).needsYou)
    }

    @Test
    fun theOpenChatIsLeftOutByAnyOfItsIds() {
        val bot = OtherChatRow(OtherChat("bot-new", "Rex", bot = "rex"), ids = setOf("bot-old", "bot-new"))
        val rows = listOf(OtherChatRow(idle("a")), OtherChatRow(idle("b")), bot)
        assertEquals(setOf("a", "b", "bot-new"), OtherChats.of(rows, open = emptySet()).chats.keys)
        assertEquals(setOf("b", "bot-new"), OtherChats.of(rows, open = setOf("a")).chats.keys)
        // A Bot Chat open by its id from before /compress is still the open chat.
        assertEquals(setOf("a", "b"), OtherChats.of(rows, open = setOf("bot-old")).chats.keys)
    }

    @Test
    fun aChatListedTwiceKeepsItsFirstRow() {
        val rows = listOf(OtherChatRow(reply("a")), OtherChatRow(idle("a")))
        assertTrue(OtherChats.of(rows, emptySet()).chats.getValue("a").unread)
    }

    @Test
    fun theBadgeSaysHowManyAndHowUrgentInWords() {
        assertTrue(SessionsBadge().none)
        assertEquals("Sessions", SessionsBadge().description)
        assertEquals("Sessions, 1 needs you", SessionsBadge(replies = 1).description)
        assertEquals("Sessions, 3 need you", SessionsBadge(waiting = 1, replies = 2).description)
        assertFalse(SessionsBadge(replies = 2).urgent)
        assertTrue(SessionsBadge(waiting = 1).urgent)
    }

    @Test
    fun theBannerSaysWhatHappenedAndHowManyMore() {
        assertEquals("Reply in Alpha", OtherChatEvent("a", "Alpha", OtherChatCall.Reply).message)
        assertEquals("Alpha needs your OK, and 2 more", OtherChatEvent("a", "Alpha", OtherChatCall.Approval, more = 2).message)
        assertEquals("Alpha needs an answer", OtherChatEvent("a", "Alpha", OtherChatCall.Question).message)
        assertEquals("Alpha needs your input", OtherChatEvent("a", "Alpha", OtherChatCall.Input).message)
        assertFalse(OtherChatEvent("a", "Alpha", OtherChatCall.Reply).waiting)
        assertTrue(OtherChatEvent("a", "Alpha", OtherChatCall.Input).waiting)
    }

    @Test
    fun aBannerGoesOnceWhatItSaysIsNoLongerTrue() {
        val asked = OtherChatEvent("b", "B", OtherChatCall.Approval)
        val replied = OtherChatEvent("b", "B", OtherChatCall.Reply)
        assertTrue(chats(waiting("b")).stillTrue(asked))
        assertFalse(chats(idle("b")).stillTrue(asked))
        assertTrue(chats(reply("b")).stillTrue(replied))
        // Read, or a new turn started in it.
        assertFalse(chats(idle("b")).stillTrue(replied))
        assertFalse(chats(reply("b").copy(running = true)).stillTrue(replied))
        // Archived, deleted, or opened.
        assertFalse(chats(idle("a")).stillTrue(replied))
    }

    // OtherChatsWatch

    @Test
    fun theFirstLookOnlyTakesStock() {
        val watch = OtherChatsWatch()
        assertNull(watch.update(chats(reply("b"), waiting("c")), 1))
        // A request counts whenever it is there; a reply already unread isn't news.
        assertEquals(SessionsBadge(waiting = 1, replies = 0), watch.badge(chats(reply("b"), waiting("c"))))
        assertEquals(listOf("c"), watch.queue(chats(reply("b"), waiting("c"))).map { it.id })
    }

    @Test
    fun aReplyTurningUnreadWhileWatchedIsNews() {
        val watch = OtherChatsWatch()
        watch.update(chats(idle("a"), idle("b").copy(running = true)), 1)
        // Mid-turn activity isn't a reply yet.
        assertNull(watch.update(chats(idle("a"), reply("b").copy(running = true)), 2))
        val event = assertNotNull(watch.update(chats(idle("a"), reply("b")), 3))
        assertEquals(OtherChatEvent("b", "B", OtherChatCall.Reply, atMillis = 3), event)
        assertEquals(SessionsBadge(replies = 1), watch.badge(chats(idle("a"), reply("b"))))
        assertEquals(listOf("b"), watch.queue(chats(idle("a"), reply("b"))).map { it.id })
        // Nothing new the next time round.
        assertNull(watch.update(chats(idle("a"), reply("b")), 4))
    }

    @Test
    fun eachKindOfNewRequestHasItsCall() {
        for ((kind, call) in listOf(Waiting.Approval to OtherChatCall.Approval, Waiting.Question to OtherChatCall.Question, Waiting.Input to OtherChatCall.Input, Waiting.Unknown to OtherChatCall.Approval)) {
            val watch = OtherChatsWatch()
            watch.update(chats(idle("b")), 1)
            assertEquals(call, watch.update(chats(waiting("b", kind)), 2)?.call, "$kind")
        }
    }

    @Test
    fun learningWhatARequestWasIsntNewButAnotherKindIs() {
        val watch = OtherChatsWatch()
        watch.update(chats(waiting("b", Waiting.Unknown)), 1)
        assertNull(watch.update(chats(waiting("b", Waiting.Approval)), 2))
        assertNull(watch.update(chats(waiting("b", Waiting.Unknown)), 3))
        assertNull(watch.update(chats(waiting("b", Waiting.Approval)), 4))
        // Approved, then a question straight after.
        assertEquals(OtherChatCall.Question, watch.update(chats(waiting("b", Waiting.Question)), 5)?.call)
    }

    @Test
    fun anotherRequestOfTheSameKindIsNewWhenItsIdIsKnown() {
        val watch = OtherChatsWatch()
        watch.update(chats(waiting("b").copy(requestId = "r1"), waiting("c")), 1)
        // The same request heard again isn't new.
        assertNull(watch.update(chats(waiting("b").copy(requestId = "r1"), waiting("c")), 2))
        // Approved on Desktop, and the next approval asked before the next look.
        val next = assertNotNull(watch.update(chats(waiting("b").copy(requestId = "r2"), waiting("c")), 3))
        assertEquals("b", next.chatId)
        assertEquals(OtherChatCall.Approval, next.call)
        // It goes to the back of Next's queue, as a new one.
        assertEquals(listOf("c", "b"), watch.queue(chats(waiting("b"), waiting("c"))).map { it.id })
    }

    @Test
    fun anUnknownIdIsNoNewRequest() {
        val watch = OtherChatsWatch()
        watch.update(chats(waiting("b")), 1)
        assertNull(watch.update(chats(waiting("b").copy(requestId = "r1")), 2))
        assertNull(watch.update(chats(waiting("b")), 3))
    }

    @Test
    fun severalAtOnceLeadWithARequestAndCountTheRest() {
        val watch = OtherChatsWatch()
        watch.update(chats(idle("a"), idle("b"), idle("c")), 1)
        val event = assertNotNull(watch.update(chats(reply("a"), waiting("b"), reply("c")), 2))
        assertEquals("b", event.chatId)
        assertEquals(OtherChatCall.Approval, event.call)
        assertEquals(2, event.more)
        assertEquals("B needs your OK, and 2 more", event.message)
    }

    @Test
    fun severalRepliesAtOnceLeadWithTheNewest() {
        val watch = OtherChatsWatch()
        watch.update(chats(idle("a"), idle("b")), 1)
        val event = assertNotNull(watch.update(chats(reply("a", at = 30.0), reply("b", at = 20.0)), 2))
        assertEquals("a", event.chatId)
        assertEquals(1, event.more)
        assertEquals(SessionsBadge(replies = 2), watch.badge(chats(reply("a", at = 30.0), reply("b", at = 20.0))))
    }

    @Test
    fun aChatJustListedIsntNews() {
        val watch = OtherChatsWatch()
        watch.update(chats(idle("a")), 1)
        // "b" was the open chat, or further down the list: it comes in already unread or waiting.
        assertNull(watch.update(chats(idle("a"), reply("b"), waiting("c")), 2))
        assertEquals(SessionsBadge(waiting = 1), watch.badge(chats(idle("a"), reply("b"), waiting("c"))))
    }

    @Test
    fun repliesSinceLeavingAreNewsWithoutABanner() {
        val watch = OtherChatsWatch()
        // Time away counts from the newest activity the last look saw (the gateway's clock), here c's.
        watch.update(chats(idle("b", at = 50.0), reply("c", at = 90.0), idle("d", at = 50.0)), 1)
        watch.left(100.0)
        // "b" replied after the chat screen went; "c" was already unread from before, not news.
        val back = chats(reply("b", at = 150.0), reply("c", at = 90.0), idle("d"))
        assertNull(watch.update(back, 2))
        assertEquals(SessionsBadge(replies = 1), watch.badge(back))
        assertEquals(listOf("b"), watch.queue(back).map { it.id })
        // The time away counts once: a second return doesn't make "c" news.
        watch.reset()
        assertNull(watch.update(back, 3))
        assertEquals(SessionsBadge(replies = 1), watch.badge(back))
    }

    @Test
    fun timeAwayCountsOnTheGatewaysClock() {
        // The phone's clock runs far ahead of the gateway's: a reply on the gateway's clock still counts.
        val ahead = OtherChatsWatch()
        ahead.update(chats(idle("b", at = 50.0), idle("c", at = 60.0)), 1)
        ahead.left(10_000.0)
        val back = chats(reply("b", at = 70.0), idle("c", at = 60.0))
        assertNull(ahead.update(back, 2))
        assertEquals(SessionsBadge(replies = 1), ahead.badge(back))
        // Behind it: what was already unread before leaving doesn't turn into news.
        val behind = OtherChatsWatch()
        behind.update(chats(reply("b", at = 500.0), idle("c", at = 400.0)), 1)
        behind.left(10.0)
        val later = chats(reply("b", at = 500.0), idle("c", at = 400.0))
        assertNull(behind.update(later, 2))
        assertEquals(SessionsBadge(), behind.badge(later))
    }

    @Test
    fun leavingTwiceCountsFromTheFirstTime() {
        val watch = OtherChatsWatch()
        watch.update(chats(idle("b", at = 50.0)), 1)
        watch.left(100.0)
        watch.left(200.0)
        val back = chats(reply("b", at = 150.0))
        assertNull(watch.update(back, 2))
        assertEquals(SessionsBadge(replies = 1), watch.badge(back))
    }

    @Test
    fun readOrOpenedIsNoLongerNews() {
        val watch = OtherChatsWatch()
        watch.update(chats(idle("b")), 1)
        watch.update(chats(reply("b")), 2)
        // Read elsewhere.
        watch.update(chats(idle("b")), 3)
        assertEquals(SessionsBadge(), watch.badge(chats(reply("b"))))
        // Or opened here: gone from the other chats.
        watch.update(chats(idle("c")), 4)
        watch.update(chats(idle("c"), reply("b")), 5)
        assertEquals(SessionsBadge(), watch.badge(chats(idle("c"), reply("b"))))
    }

    @Test
    fun aResetKeepsTheNewsButTakesStockAgain() {
        val watch = OtherChatsWatch()
        watch.update(chats(idle("b"), idle("c")), 1)
        watch.update(chats(reply("b"), idle("c")), 2)
        watch.reset()
        // "c" replied during the reconnect: taken stock of, not shown.
        assertNull(watch.update(chats(reply("b"), reply("c")), 3))
        assertEquals(SessionsBadge(replies = 1), watch.badge(chats(reply("b"), reply("c"))))
    }

    @Test
    fun clearForgetsEverything() {
        val watch = OtherChatsWatch()
        watch.update(chats(idle("b")), 1)
        watch.update(chats(reply("b")), 2)
        watch.left(0.0)
        watch.clear()
        // Another gateway: nothing is news, not even what came after the old one's leaving.
        assertNull(watch.update(chats(reply("b", at = 99.0)), 3))
        assertEquals(SessionsBadge(), watch.badge(chats(reply("b", at = 99.0))))
    }

    @Test
    fun unsettledLooksOnlyTakeStock() {
        val watch = OtherChatsWatch()
        watch.update(chats(idle("b")), 1)
        assertNull(watch.update(chats(reply("b")), 2, settled = false))
        // The first settled look after it takes stock too, rather than telling what the catch-up brought in.
        assertNull(watch.update(chats(reply("b"), waiting("c"), idle("d")), 3))
        assertEquals(OtherChatCall.Question, watch.update(chats(reply("b"), waiting("c"), waiting("d", Waiting.Question)), 4)?.call)
    }

    @Test
    fun nextGoesToTheOldestRequestThenTheOldestNewsReply() {
        val watch = OtherChatsWatch()
        watch.update(chats(waiting("x"), idle("y"), idle("r1"), idle("r2"), reply("old", at = 1.0)), 1)
        watch.update(chats(waiting("x"), waiting("y"), reply("r1", at = 40.0), idle("r2"), reply("old", at = 1.0)), 5)
        val now = chats(waiting("x"), waiting("y"), reply("r1", at = 40.0), reply("r2", at = 30.0), reply("old", at = 1.0))
        watch.update(now, 9)
        // "old" was unread before the watching began: not on the way.
        assertEquals(listOf("x", "y", "r2", "r1"), watch.queue(now).map { it.id })
    }

    @Test
    fun aNewKindOfRequestGoesToTheBackOfTheQueue() {
        val watch = OtherChatsWatch()
        watch.update(chats(waiting("x"), idle("y")), 1)
        watch.update(chats(waiting("x"), waiting("y")), 2)
        // "x" answered its approval and asks a question now: newer than "y"'s.
        val now = chats(waiting("x", Waiting.Question), waiting("y"))
        watch.update(now, 3)
        assertEquals(listOf("y", "x"), watch.queue(now).map { it.id })
    }

    // previewOf(text)

    @Test
    fun aPreviewTakesTheMarkdownOut() {
        assertEquals(
            "Title Some bold and italic and b and i text.",
            previewOf("# Title\n\nSome **bold** and *italic* and __b__ and _i_ text."),
        )
        assertEquals("See the docs and a cat.", previewOf("See [the docs](https://example.com) and ![a cat](cat.png)."))
        assertEquals("Run make test now", previewOf("Run `make test` now"))
        assertEquals("old new", previewOf("~~old~~ new"))
        assertEquals("one two three four quoted", previewOf("- one\n* two\n1. three\n2) four\n> quoted"))
    }

    @Test
    fun aPreviewLeavesWordsWithMarksInThemAlone() {
        assertEquals("call my_func_name now", previewOf("call my_func_name now"))
        assertEquals("2 * 3 * 4", previewOf("2 * 3 * 4"))
    }

    @Test
    fun aTableReadsAsItsCells() {
        assertEquals("A · B 1 · 2", previewOf("| A | B |\n|---|:-:|\n| 1 | 2 |"))
    }

    @Test
    fun codeBlocksAreLeftOut() {
        assertEquals("Here: Done.", previewOf("Here:\n```kotlin\nval x = 1\n```\nDone."))
        // A fence never closed (the reply was cut short) hides the rest.
        assertEquals("Before", previewOf("Before\n```\ncode\nmore"))
        assertNull(previewOf("```\nonly code\n```"))
    }

    @Test
    fun nothingToSayIsNoPreview() {
        assertNull(previewOf(null as String?))
        assertNull(previewOf("  \n\t"))
        assertNull(previewOf("---\n> "))
    }

    @Test
    fun aLongReplyIsCutWithAnEllipsis() {
        val preview = assertNotNull(previewOf("word ".repeat(100)))
        assertTrue(preview.endsWith("word…"), preview)
        assertEquals(280, preview.length)
    }

    @Test
    fun theCutNeverSplitsACharacter() {
        // An emoji is two UTF-16 units; cut between them, the banner would show a broken glyph.
        assertEquals("a".repeat(279) + "…", previewOf("a".repeat(279) + "😀 tail"))
    }

    // previewOf(InputRequest)

    @Test
    fun anApprovalShowsItsCommandAsItIs() {
        val approval = InputRequest.Approval("r1", "rm -rf build", "Delete files", "terminal", listOf(ApprovalChoice.Once, ApprovalChoice.Deny))
        assertEquals("rm -rf build", previewOf(approval))
        assertEquals("Delete files", previewOf(approval.copy(command = " ")))
        // A command isn't Markdown: what is approved shows as it will run.
        assertEquals("pytest tests/__init__.py *.txt", previewOf(approval.copy(command = "pytest   tests/__init__.py\n*.txt")))
    }

    @Test
    fun aQuestionShowsTheFirstAndHowManyMore() {
        val one = InputRequest.Clarify("r2", listOf(ClarifyQuestion(null, "Which **branch**?")))
        assertEquals("Which branch?", previewOf(one))
        val three = InputRequest.Clarify("r3", listOf(ClarifyQuestion("q1", "Which branch?"), ClarifyQuestion("q2", "Squash?"), ClarifyQuestion("q3", "Push?")))
        assertEquals("Which branch? (+2 more)", previewOf(three))
        assertNull(previewOf(InputRequest.Clarify("r4", emptyList())))
    }

    @Test
    fun aValueAskedForSaysWhatItIsFor() {
        assertEquals("Password for alex", previewOf(InputRequest.Secret("r5", InputRequest.Secret.Kind.Sudo, "Password for alex", null, "apt install jq")))
        assertEquals("apt install jq", previewOf(InputRequest.Secret("r6", InputRequest.Secret.Kind.Sudo, "", null, "apt install jq")))
        assertEquals("OPENAI_API_KEY", previewOf(InputRequest.Secret("r7", InputRequest.Secret.Kind.Secret, "", "OPENAI_API_KEY", null)))
        assertNull(previewOf(InputRequest.Secret("r8", InputRequest.Secret.Kind.VaultUnlock, "", null, null)))
        assertEquals("Save your github.com login?", previewOf(InputRequest.VaultSaveLogin("r9", "github.com", "https://github.com")))
        assertEquals("Save a login?", previewOf(InputRequest.VaultSaveLogin("r10", "", "")))
        assertNull(previewOf(null as InputRequest?))
    }
}
