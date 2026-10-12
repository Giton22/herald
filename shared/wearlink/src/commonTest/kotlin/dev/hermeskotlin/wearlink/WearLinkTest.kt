package dev.hermeskotlin.wearlink

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WearLinkTest {

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
        assertEquals(PhoneStatus(signedIn = true, connected = true, failed = false, liveVoice = false), status)
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
    fun longRepliesAreCutAtAWordToFitAMessage() {
        assertEquals("short", clipForWatch("short"))
        val clipped = clipForWatch("word ".repeat(100), max = 40)
        assertTrue(clipped.length <= 40)
        assertTrue(clipped.endsWith("word…"))
        val oneWord = clipForWatch("x".repeat(100), max = 40)
        assertEquals(40, oneWord.length)
    }
}
