package dev.hermeskotlin.wearlink

import dev.hermeskotlin.wearlink.PebbleLink.toWatchAsk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PebbleLinkTest {

    private fun num(value: Int) = PebbleValue.Num(value)
    private fun text(value: String) = PebbleValue.Text(value)

    @Test
    fun anAskFromTheWatchIsNeverReadAloudAndABlankChatIsANewOne() {
        val dict = mapOf(PebbleKeys.TYPE to num(PebbleTypes.ASK), PebbleKeys.ASK_ID to text("p1"), PebbleKeys.TEXT to text("hello"), PebbleKeys.CHAT_ID to text(""))
        val ask = PebbleLink.decode(dict) as PebbleIncoming.Ask
        assertEquals(WatchAsk(id = "p1", text = "hello", chatId = null, speak = false), ask.toWatchAsk())
    }

    @Test
    fun whatTheWatchSendsWithoutItsIdsOrOfAnUnknownTypeIsNothing() {
        assertNull(PebbleLink.decode(mapOf(PebbleKeys.TYPE to num(PebbleTypes.ASK), PebbleKeys.TEXT to text("hello"))))
        assertNull(PebbleLink.decode(mapOf(PebbleKeys.TYPE to num(PebbleTypes.STOP))))
        assertNull(PebbleLink.decode(mapOf(PebbleKeys.TYPE to num(42))))
        assertNull(PebbleLink.decode(mapOf(PebbleKeys.TEXT to text("no type"))))
    }

    @Test
    fun theOtherRequestsReadAsTheyWereSent() {
        assertEquals(PebbleIncoming.ChatsRequest, PebbleLink.decode(mapOf(PebbleKeys.TYPE to num(PebbleTypes.CHATS_REQ))))
        assertEquals(PebbleIncoming.StatusRequest(null), PebbleLink.decode(mapOf(PebbleKeys.TYPE to num(PebbleTypes.STATUS_REQ))))
        assertEquals(
            PebbleIncoming.StatusRequest("rev"),
            PebbleLink.decode(mapOf(PebbleKeys.TYPE to num(PebbleTypes.STATUS_REQ), PebbleKeys.ACCENT_REV to text("rev"))),
        )
        assertEquals(PebbleIncoming.Stop("p1"), PebbleLink.decode(mapOf(PebbleKeys.TYPE to num(PebbleTypes.STOP), PebbleKeys.ASK_ID to text("p1"))))
        assertEquals(
            PebbleIncoming.RingEnd("r1", answered = true),
            PebbleLink.decode(mapOf(PebbleKeys.TYPE to num(PebbleTypes.RING_END_FROM_WATCH), PebbleKeys.REQUEST_ID to text("r1"), PebbleKeys.ANSWERED to num(1))),
        )
        assertEquals(PebbleIncoming.Resync("p1"), PebbleLink.decode(mapOf(PebbleKeys.TYPE to num(PebbleTypes.RESYNC), PebbleKeys.ASK_ID to text("p1"))))
    }

    @Test
    fun theStatusCarriesItsFlagsAndTheAccentAsPlainRgb() {
        val accent = WatchAccent.of(fill = 0xFF8A2BE2.toInt(), text = 0xFFFFFFFF.toInt(), onFill = 0xFF000000.toInt(), dim = 0x80123456.toInt())
        val dict = PebbleLink.status(PhoneStatus(signedIn = true, connected = false, failed = true, accent = accent))
        assertEquals(num(PebbleTypes.STATUS), dict[PebbleKeys.TYPE])
        assertEquals(num(PebbleLink.FLAG_SIGNED_IN or PebbleLink.FLAG_FAILED), dict[PebbleKeys.FLAGS])
        assertEquals(num(0x8A2BE2), dict[PebbleKeys.ACCENT_FILL])
        assertEquals(num(0x123456), dict[PebbleKeys.ACCENT_DIM])
        assertEquals(text(accent.revision), dict[PebbleKeys.ACCENT_REV])
        assertNull(PebbleLink.status(PhoneStatus(signedIn = false, connected = false))[PebbleKeys.ACCENT_FILL])
        assertEquals(num(PebbleLink.FLAG_OFF), PebbleLink.off()[PebbleKeys.FLAGS])
    }

    @Test
    fun eachChatIsItsOwnMessageAndAnEmptyListIsOneWithItsError() {
        val now = 1_000_000.0
        val list = ChatList(listOf(WatchChat("a", "First", now - 30), WatchChat("b", "Second", now - 7_200), WatchChat("c", "Third", null)))
        val items = PebbleLink.chats(list, now)
        assertEquals(3, items.size)
        assertEquals(listOf(0, 1, 2), items.map { (it[PebbleKeys.INDEX] as PebbleValue.Num).value })
        assertTrue(items.all { it[PebbleKeys.COUNT] == num(3) })
        assertEquals(listOf("now", "2h", null), items.map { (it[PebbleKeys.AGO] as? PebbleValue.Text)?.value })

        val failed = PebbleLink.chats(ChatList(error = "Sign in to Kyllene on your phone first."), now).single()
        assertEquals(num(0), failed[PebbleKeys.COUNT])
        assertEquals(text("Sign in to Kyllene on your phone first."), failed[PebbleKeys.ERROR])
    }

    @Test
    fun aReplyAtItsLongestStillFitsTheWatchsInbox() {
        val long = "x".repeat(500)
        val update = ReplyUpdate(
            askId = "p" + "9".repeat(15),
            phase = ReplyPhase.Failed,
            seq = Int.MAX_VALUE,
            chatId = "20261027_044722_e4d150",
            title = long,
            activity = long,
            error = long,
            heard = long,
            // As long a scope as the watch keeps.
            scope = WatchScope("https://" + "g".repeat(168) + ".example", "p".repeat(15)),
        )
        // A chunk of four-byte characters, as many as fit.
        val chunk = takeUtf8("🙂".repeat(1_000), PebbleLink.CHUNK_BYTES)
        val dict = PebbleLink.reply(update, offset = 0, chunk = chunk, total = 5_000)
        assertTrue(dict.containsKey(PebbleKeys.SCOPE))
        assertTrue(PebbleLink.size(dict) <= PebbleLink.WATCH_INBOX_BYTES, "size ${PebbleLink.size(dict)}")
    }

    @Test
    fun aChatsLastExchangeComesAsAFinishedTurnWithWhatWasSaid() {
        assertEquals(PebbleIncoming.HistoryRequest("c1"), PebbleLink.decode(mapOf(PebbleKeys.TYPE to num(PebbleTypes.HISTORY_REQ), PebbleKeys.CHAT_ID to text("c1"))))
        val update = PebbleLink.history("c1", "Plans", said = "What's **on**?", reply = "Nothing.")
        assertEquals(ReplyPhase.Done, update.phase)
        val dict = PebbleLink.reply(update, offset = 0, chunk = "Nothing.", total = 8)
        assertEquals(text("What's on?"), dict[PebbleKeys.SAID])
        assertEquals(text("h:c1"), dict[PebbleKeys.ASK_ID])
    }

    @Test
    fun aRequestAtItsLongestFitsTheWatchsInboxAndItsAnswerReads() {
        val long = "Ü".repeat(2_000)
        val dict = PebbleLink.request(
            requestId = "r".repeat(40),
            chatId = "20261027_044722_e4d150",
            title = long,
            approval = true,
            heading = long,
            detail = long,
            choices = List(10) { long },
            answering = 2,
        )
        assertTrue(PebbleLink.size(dict) <= PebbleLink.WATCH_INBOX_BYTES, "size ${PebbleLink.size(dict)}")
        assertEquals(PebbleLink.MAX_CHOICES, (dict[PebbleKeys.CHOICES] as PebbleValue.Text).value.lines().size)

        assertEquals(
            PebbleIncoming.Answer("r1", 2, null),
            PebbleLink.decode(mapOf(PebbleKeys.TYPE to num(PebbleTypes.ANSWER), PebbleKeys.REQUEST_ID to text("r1"), PebbleKeys.INDEX to num(2))),
        )
        assertEquals(
            PebbleIncoming.Answer("r1", null, "Blue"),
            PebbleLink.decode(
                mapOf(PebbleKeys.TYPE to num(PebbleTypes.ANSWER), PebbleKeys.REQUEST_ID to text("r1"), PebbleKeys.INDEX to num(-1), PebbleKeys.TEXT to text("Blue")),
            ),
        )
    }

    @Test
    fun aRingSaysHowItsAnsweredAndFitsTheWatchsInbox() {
        val ring = WatchRing("r".repeat(40), "20261027_044722_e4d150", "Ü".repeat(200), approval = false, agent = true)
        val dict = PebbleLink.ring(ring, answering = 2)
        assertEquals(num(PebbleTypes.RING), dict[PebbleKeys.TYPE])
        assertEquals(num(2), dict[PebbleKeys.ANSWERING])
        assertEquals(num(1), dict[PebbleKeys.AGENT])
        assertTrue(PebbleLink.size(dict) <= PebbleLink.WATCH_INBOX_BYTES)
        assertEquals(PebbleIncoming.RingOn("r1"), PebbleLink.decode(mapOf(PebbleKeys.TYPE to num(PebbleTypes.RING_ON), PebbleKeys.REQUEST_ID to text("r1"))))
    }

    @Test
    fun promptsGoAsLinesWithARevisionThatChangesWithThem() {
        val prompts = listOf(" Summarize my day ", "", "What's\nrunning?", "x".repeat(200)) + List(10) { "More $it" }
        val dict = PebbleLink.prompts(prompts)
        val lines = (dict[PebbleKeys.PROMPTS] as PebbleValue.Text).value.lines()
        assertEquals(PebbleLink.MAX_PROMPTS, lines.size)
        assertEquals("Summarize my day", lines[0])
        assertEquals("What's running?", lines[1])
        assertTrue(utf8Size(lines[2]) <= 60)
        assertTrue(PebbleLink.size(dict) <= PebbleLink.WATCH_INBOX_BYTES)
        assertEquals(text(PebbleLink.promptsRevision(prompts)), dict[PebbleKeys.PROMPTS_REV])
        // Only what the watch would show counts: blanks and spacing don't change the revision, an edit does.
        assertEquals(PebbleLink.promptsRevision(listOf("A", "B")), PebbleLink.promptsRevision(listOf(" A", "", "B ")))
        assertTrue(PebbleLink.promptsRevision(listOf("A", "B")) != PebbleLink.promptsRevision(listOf("B", "A")))
        assertEquals(
            PebbleIncoming.StatusRequest("rev", "p1"),
            PebbleLink.decode(mapOf(PebbleKeys.TYPE to num(PebbleTypes.STATUS_REQ), PebbleKeys.ACCENT_REV to text("rev"), PebbleKeys.PROMPTS_REV to text("p1"))),
        )
    }

    @Test
    fun aChatsScopeGoesWithItAndComesBackWithEachAsk() {
        val scope = WatchScope("https://gateway.example", "work")
        val launch = WatchScope("https://gateway.example")
        assertEquals(scope, PebbleLink.scopeOf(PebbleLink.scopeText(scope)!!))
        // The launch profile is no profile, not an empty one.
        assertEquals(launch, PebbleLink.scopeOf(PebbleLink.scopeText(launch)!!))
        assertNull(PebbleLink.scopeText(WatchScope("https://" + "g".repeat(300))))

        val item = PebbleLink.chats(ChatList(listOf(WatchChat("a", "First")), scope = scope), 0.0).single()
        assertEquals(text("https://gateway.example\nwork"), item[PebbleKeys.SCOPE])
        val ask = PebbleLink.decode(
            mapOf(PebbleKeys.TYPE to num(PebbleTypes.ASK), PebbleKeys.ASK_ID to text("p1"), PebbleKeys.CHAT_ID to text("a"), PebbleKeys.SCOPE to text("https://gateway.example\nwork")),
        ) as PebbleIncoming.Ask
        assertEquals(scope, ask.toWatchAsk().scope)
        assertEquals(
            PebbleIncoming.HistoryRequest("a", launch),
            PebbleLink.decode(mapOf(PebbleKeys.TYPE to num(PebbleTypes.HISTORY_REQ), PebbleKeys.CHAT_ID to text("a"), PebbleKeys.SCOPE to text("https://gateway.example"))),
        )
        // A watchapp from before scopes asks in whatever the phone is on.
        assertNull((PebbleLink.decode(mapOf(PebbleKeys.TYPE to num(PebbleTypes.ASK), PebbleKeys.ASK_ID to text("p2"))) as PebbleIncoming.Ask).scope)
    }

    @Test
    fun everyChatItemFitsTheWatchsInbox() {
        val chat = WatchChat("2".repeat(40), "Ü".repeat(80), 0.0)
        val item = PebbleLink.chats(ChatList(listOf(chat)), 1.0).single()
        assertTrue(PebbleLink.size(item) <= PebbleLink.WATCH_INBOX_BYTES)
    }
}
