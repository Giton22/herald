package dev.hermeskotlin.wearlink

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WearLinkTest {

    @Test
    fun explicitLaunchScopeSurvivesRecordedMessagesAndLegacyCallsKeepNoScope() {
        val scope = WatchScope("https://gateway.example", null)
        val ask = WatchAsk("ask", chatId = "saved", scope = scope)
        assertEquals(ask, RecordedAsk.read(RecordedAsk.write(ask, byteArrayOf(1)))!!.first)
        assertEquals(scope, WearJson.decode<CallStart>(WearJson.encode(CallStart("call", "saved", "offer", scope = scope)))!!.scope)
        assertNull(WearJson.decode<CallStart>("""{"id":"call","chatId":"saved","offerSdp":"offer"}""".encodeToByteArray())!!.scope)
    }

    @Test
    fun contextChecksAndListsRetainTheirRequestAndScopeWithoutChangingOldPeers() {
        val owner = WatchScope("https://gateway.example", "work")
        val request = PhoneStatusRequest(requestId = "latest")
        assertEquals(request, WearJson.decode<PhoneStatusRequest>(WearJson.encode(request)))
        val status = PhoneStatus(true, true, requestId = "latest", activeScope = owner, profileSync = true)
        assertEquals(status, WearJson.decode<PhoneStatus>(WearJson.encode(status)))
        val list = ChatList(requestId = "list", scope = owner)
        assertEquals(list, WearJson.decode<ChatList>(WearJson.encode(list)))
        assertEquals(ChatListRequest("list"), WearJson.decode<ChatListRequest>(WearJson.encode(ChatListRequest("list"))))
        assertFalse(WearJson.decode<PhoneStatus>("""{"signedIn":true,"connected":true}""".encodeToByteArray())!!.profileSync)
        assertNull(WearJson.decode<ChatList>("""{"chats":[]}""".encodeToByteArray())!!.scope)
    }

    @Test
    fun defaultCallsRemainDistinctAndPreserveBotContracts() {
        val request = PhoneStatusRequest(botProfile = "assistant")
        assertEquals(request, WearJson.decode<PhoneStatusRequest>(WearJson.encode(request)))
        val call = CallStart("call", "tip", "offer", "assistant", "resolution")
        assertEquals(call, WearJson.decode<CallStart>(WearJson.encode(call)))
        assertFalse(WearPaths.DEFAULT_CALL_START == WearPaths.CALL_START)
        assertFalse(WearPaths.DEFAULT_CALL_START == WearPaths.BOT_CALL_START)
        assertFalse(WearJson.decode<PhoneStatus>("{\"signedIn\":true,\"connected\":true}".encodeToByteArray())!!.defaultCalls)
    }

    @Test
    fun malformedDisplaySnapshotsAreRejectedAndValidOnesRoundTrip() {
        val display = CallDisplay("a".repeat(64), "Assistant", hasPicture = true)
        assertTrue(display.valid)
        assertEquals(display, WearJson.decode<CallDisplay>(WearJson.encode(display)))
        assertFalse(display.copy(revision = "wrong").valid)
        assertFalse(display.copy(version = 2).valid)
        assertFalse(display.copy(targetKey = "wrong").valid)
        assertFalse(display.copy(title = "x".repeat(257)).valid)
        assertEquals(DefaultCallTarget("request", error = "Not available"),
            WearJson.decode<DefaultCallTarget>(WearJson.encode(DefaultCallTarget("request", error = "Not available"))))
    }

    @Test
    fun botIdentitySurvivesBothAskAndCallMessagesWithoutChangingLegacyRequests() {
        val ask = WearJson.decode<WatchAsk>("""{"id":"a","text":"hi","botProfile":"assistant"}""".encodeToByteArray())!!
        val call = WearJson.decode<CallStart>("""{"id":"c","chatId":null,"offerSdp":"offer","botProfile":"assistant"}""".encodeToByteArray())!!
        val askJson = WearJson.decode<kotlinx.serialization.json.JsonObject>(WearJson.encode(ask))!!
        val callJson = WearJson.decode<kotlinx.serialization.json.JsonObject>(WearJson.encode(call))!!
        assertEquals("\"assistant\"", askJson["botProfile"].toString())
        assertEquals("\"assistant\"", callJson["botProfile"].toString())
        assertEquals(WatchAsk("old", "hi"), WearJson.decode<WatchAsk>("""{"id":"old","text":"hi"}""".encodeToByteArray()))
    }

    @Test
    fun botRecordingsUseADistinctPathThatLegacyPhonesCannotMistakeForRegularSpeech() {
        val bot = WearPaths.recordingPath("ask/1", bot = true)
        assertTrue(WearPaths.isRecording(bot))
        assertTrue(WearPaths.isBotRecording(bot))
        assertFalse(bot.startsWith("/herald/recording/"))
        assertFalse(WearPaths.isBotRecording(WearPaths.recordingPath("ask/1")))
        val ask = WatchAsk("a", botProfile = "assistant")
        assertEquals(ask, RecordedAsk.read(RecordedAsk.write(ask, byteArrayOf(1, 2, 3)))?.first)
    }

    @Test
    fun phoneAccentSurvivesStatusSerialization() {
        val wire = """{"signedIn":true,"connected":true,"accent":{"revision":"dark:Violet:1","fill":-5796870,"text":-5796870,"onFill":-16119284,"dim":-5796870}}"""
        val status = WearJson.decode<PhoneStatus>(wire.encodeToByteArray())!!
        val resent = WearJson.decode<JsonObject>(WearJson.encode(status))!!
        assertEquals(Json.parseToJsonElement(wire).jsonObject["accent"], resent["accent"])
    }

    @Test
    fun legacyPhoneStatusHasNoAccent() {
        val old = WearJson.decode<PhoneStatus>("""{"signedIn":true,"connected":true}""".encodeToByteArray())!!
        assertFalse("accent" in WearJson.decode<JsonObject>(WearJson.encode(old))!!)
        assertTrue(old.connected)
    }

    @Test
    fun accentRevisionSurvivesARequestAndLegacyRequestsHaveNone() {
        val request = PhoneStatusRequest(accentRevision = "dark:Violet:1")
        assertEquals(request, WearJson.decode<PhoneStatusRequest>(WearJson.encode(request)))
        assertEquals(PhoneStatusRequest(), WearJson.decode<PhoneStatusRequest>("{}".encodeToByteArray()))
        assertNull(WearJson.decode<PhoneStatusRequest>(ByteArray(0)))
    }

    @Test
    fun matchingRevisionOmitsColorsFromWire() {
        val purple = WatchAccent("dark:Violet:1", -5796870, -5796870, -16119284, -5796870)
        val response = PhoneStatus(signedIn = true, connected = true, accent = purple.updateFor("dark:Violet:1"))
        assertFalse("accent" in WearJson.decode<JsonObject>(WearJson.encode(response))!!)
    }

    @Test
    fun missingOldOrNewPaletteVersionSendsColors() {
        val purple = WatchAccent("dark:Violet:2", -5796870, -5796870, -16119284, -5796870)
        for (cached in listOf(null, "dark:Teal:2", "dark:Violet:1")) {
            assertEquals(purple, purple.updateFor(cached))
        }
    }

    @Test
    fun anAccentsRevisionChangesWithAnyOfItsColors() {
        val purple = WatchAccent.of(-5796870, -5796870, -16119284, -5796870)
        assertEquals(purple.revision, WatchAccent.of(-5796870, -5796870, -16119284, -5796870).revision)
        val changed = listOf(
            WatchAccent.of(-5796871, -5796870, -16119284, -5796870),
            WatchAccent.of(-5796870, -5796871, -16119284, -5796870),
            WatchAccent.of(-5796870, -5796870, -16119285, -5796870),
            WatchAccent.of(-5796870, -5796870, -16119284, -5796871),
        )
        for (accent in changed) assertEquals(accent, accent.updateFor(purple.revision))
        assertNull(purple.updateFor(purple.revision))
    }

    @Test
    fun aRecordingCarriesItsAskAndItsAudioInOnePiece() {
        val path = WearPaths.recordingPath("ask/1")
        assertTrue(path.startsWith(WearPaths.PREFIX))
        assertTrue(WearPaths.isRecording(path))
        val ask = WatchAsk("ask/1", chatId = "s1", audioType = "audio/wav")
        val audio = ByteArray(300) { it.toByte() }
        val (read, readAudio) = RecordedAsk.read(RecordedAsk.write(ask, audio))!!
        assertEquals(ask, read)
        assertContentEquals(audio, readAudio)
    }

    @Test
    fun aTruncatedOrForeignRecordingReadsAsNull() {
        val whole = RecordedAsk.write(WatchAsk("a", audioType = "audio/wav"), ByteArray(10))
        assertNull(RecordedAsk.read(whole.copyOfRange(0, 8)))
        assertNull(RecordedAsk.read(byteArrayOf(0, 0)))
        assertNull(RecordedAsk.read(byteArrayOf(0x7F, 0, 0, 0, 1, 2, 3)))
    }

    @Test
    fun aRingAndItsEndRoundTripWithoutTheQuestion() {
        val ring = WatchRing("srq-1", "s1", "Choose a colour", approval = true)
        assertEquals(ring, WearJson.decode<WatchRing>(WearJson.encode(ring)))
        assertFalse(WearJson.encode(ring).decodeToString().contains("question"))
        assertEquals(RingEnd("srq-1"), WearJson.decode<RingEnd>("""{"requestId":"srq-1"}""".encodeToByteArray()))
        assertEquals(RingOn("srq-1"), WearJson.decode<RingOn>(WearJson.encode(RingOn("srq-1"))))
        assertTrue(listOf(WearPaths.RING, WearPaths.RING_END, WearPaths.RING_ON).all { it.startsWith(WearPaths.PREFIX) })
    }

    @Test
    fun speechPathRoundTrips() {
        val path = WearPaths.speechPath("a/b c", 1, 3, "audio/ogg; codecs=opus")
        assertEquals(WearPaths.SpeechClip("a/b c", 1, 3, "audio/ogg; codecs=opus"), WearPaths.parseSpeech(path))
    }

    @Test
    fun otherPathsAndBrokenOnesParseToNull() {
        assertFalse(WearPaths.isRecording(WearPaths.speechPath("a", 0, 1, "audio/mpeg")))
        assertFalse(WearPaths.isRecording("/herald/recording/"))
        assertNull(WearPaths.parseSpeech(WearPaths.recordingPath("a")))
        assertNull(WearPaths.parseSpeech("/herald/speech/a/3/3/audio"))
        assertNull(WearPaths.parseSpeech("/herald/speech/a/x/3/audio"))
        assertNull(WearPaths.parseSpeech(WearPaths.ASK))
    }

    @Test
    fun messagesRoundTripAndSkipFieldsTheyDontKnow() {
        val update = ReplyUpdate("a", ReplyPhase.Working, chatId = "s1", text = "Hi", activity = "Running terminal", speech = ReplySpeech.Coming)
        assertEquals(update, WearJson.decode<ReplyUpdate>(WearJson.encode(update)))
        val newer = """{"id":"x","text":"hello","speak":false,"mood":"happy"}""".encodeToByteArray()
        assertEquals(WatchAsk("x", "hello", speak = false), WearJson.decode<WatchAsk>(newer))
    }

    @Test
    fun aMessageThatIsntOneDecodesToNull() {
        assertNull(WearJson.decode<WatchAsk>("not json".encodeToByteArray()))
        assertNull(WearJson.decode<ReplyUpdate>("""{"askId":"a","phase":"Dancing"}""".encodeToByteArray()))
    }

    @Test
    fun aCallsMessagesGoBothWaysIntact() {
        val start = CallStart("c1", chatId = null, offerSdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\n")
        assertEquals(start, WearJson.decode<CallStart>(WearJson.encode(start)))
        val events = CallEvents("c1", seq = 3, events = listOf("""{"type":"session.started"}""", """{"type":"error"}"""))
        assertEquals(events, WearJson.decode<CallEvents>(WearJson.encode(events)))
        val update = CallUpdate("c1", CallPhase.Live, seq = 2, answerSdp = "v=0", caption = "Hello", captionIsVoice = true, thinking = true, working = "terminal")
        assertEquals(update, WearJson.decode<CallUpdate>(WearJson.encode(update)))
    }

    @Test
    fun anOlderPhoneSaysNothingOfLiveVoice() {
        val status = WearJson.decode<PhoneStatus>("""{"signedIn":true,"connected":true}""".encodeToByteArray())
        assertEquals(PhoneStatus(signedIn = true, connected = true, failed = false, liveVoice = null), status)
    }

    @Test
    fun aPhoneThatKnowsItsLiveVoiceSaysSoEitherWay() {
        for (live in listOf(true, false)) {
            val status = PhoneStatus(signedIn = true, connected = true, liveVoice = live)
            assertEquals(live, WearJson.decode<PhoneStatus>(WearJson.encode(status))?.liveVoice)
        }
    }

    @Test
    fun aCallsBatchesComeOutInOrderAndALostOneHoldsThemOnlyUntilSkipped() {
        val out = mutableListOf<String>()
        val order = InOrder<String> { out += it }
        order.add(2, "b")
        assertTrue(order.waiting)
        assertEquals(emptyList<String>(), out)
        order.add(1, "a")
        order.add(1, "a again")
        assertEquals(listOf("a", "b"), out)
        assertFalse(order.waiting)
        order.add(5, "e")
        order.add(4, "d")
        order.skipGap()
        order.add(3, "too late")
        order.add(6, "f")
        assertEquals(listOf("a", "b", "d", "e", "f"), out)
    }

    @Test
    fun pastOneSkippedGapAnotherStillWaitsToBeSkipped() {
        val out = mutableListOf<String>()
        val order = InOrder<String> { out += it }
        order.add(2, "b")
        order.add(4, "d")
        order.skipGap()
        assertEquals(listOf("b"), out)
        // The callers skip again while this holds, so "d" doesn't wait on a batch that may never come.
        assertTrue(order.waiting)
        order.skipGap()
        assertEquals(listOf("b", "d"), out)
        assertFalse(order.waiting)
    }

    @Test
    fun theMissingBatchMovesOnWhenItsGapFillsSoANewGapIsntSkippedEarly() {
        val order = InOrder<String> {}
        order.add(1, "a")
        assertNull(order.missing)
        order.add(3, "c")
        assertEquals(2, order.missing)
        order.add(2, "b")
        order.add(5, "e")
        // A new gap: the callers wait it out in full, rather than skip it on the old one's time.
        assertEquals(4, order.missing)
    }

    @Test
    fun aSecondGapIsSkippedWithoutAnotherBatchComing() = runTest {
        val out = mutableListOf<String>()
        val order = InOrder<String> { out += it }
        order.add(3, "c")
        order.add(5, "e")
        val skipping = launch { order.skipGaps(gapMs = 100) }
        advanceTimeBy(101)
        assertEquals(listOf("c"), out)
        // Nothing more comes, and 4 is skipped all the same.
        advanceTimeBy(100)
        assertEquals(listOf("c", "e"), out)
        assertFalse(order.waiting)
        skipping.join()
    }

    @Test
    fun aGapThatOpensDuringAWaitGetsItsFullWait() = runTest {
        val out = mutableListOf<String>()
        val order = InOrder<String> { out += it }
        order.add(1, "a")
        order.add(3, "c")
        val skipping = launch { order.skipGaps(gapMs = 100) }
        advanceTimeBy(90)
        // 2 fills its gap late, opening one at 4.
        order.add(2, "b")
        order.add(5, "e")
        advanceTimeBy(20)
        assertEquals(listOf("a", "b", "c"), out)
        assertTrue(order.waiting)
        // 4 would have been skipped here, 100 after the wait started, had its own wait not begun only now.
        advanceTimeBy(80)
        assertEquals(listOf("a", "b", "c"), out)
        order.add(4, "d")
        assertEquals(listOf("a", "b", "c", "d", "e"), out)
        skipping.join()
    }

    @Test
    fun longRepliesAreCutAtAWordToFitAMessage() {
        assertEquals("short", clipForWatch("short"))
        val clipped = clipForWatch("word ".repeat(100), max = 40)
        assertTrue(clipped.length <= 40)
        assertTrue(clipped.endsWith("word…"))
        val oneWord = clipForWatch("x".repeat(100), max = 40)
        assertEquals(40, oneWord.length)
    }
}
