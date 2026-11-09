package dev.hermeskotlin.core.voice

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.test.assertNull
import dev.hermeskotlin.core.di.platformModule
import org.koin.dsl.koinApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class LinuxLiveCallTest {
    @Test fun platformFactoryAdvertisesOnlyImplementedHostsWithoutOpeningDevices() {
        val app = koinApplication { modules(platformModule) }
        try {
            val call = app.koin.get<LiveCalls>().create()
            if (System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in setOf("amd64", "x86_64", "aarch64")) {
                assertIs<LinuxLiveCall>(call); call.close()
            } else assertNull(call)
        } finally { app.close() }
    }

    @Test fun preMuteIsAppliedBeforeTheOfferAndCanBeChangedDuringTheCall() = checkCall {
        val peer = MediaPeer(); val call = LinuxLiveCall { peer.apply { events = it } }
        try {
            call.setMuted(true)
            call.connect { "answer" }
            assertEquals(true, peer.mutedAtOffer)
            assertEquals(0f, call.micLevel())
            call.setMuted(false)
            assertEquals(.6f, call.micLevel())
            assertTrue(call.speaking())
        } finally { call.close() }
    }

    @Test fun existingSignalingAndEventsBelongToTheCurrentInstance() = checkCall {
        val peer = MediaPeer(); val call = LinuxLiveCall { peer.apply { events = it } }
        try {
            call.connect { offer -> assertEquals("offer\r\n", offer); "answer\r\n" }
            assertEquals("answer\r\n", peer.answer)
            assertTrue(call.send("event Δ")); assertEquals("event Δ", peer.sent)
            peer.events.message("reply 你好".encodeToByteArray(), false)
            assertEquals("reply 你好", call.events.receive())
        } finally { call.close() }
    }

    @Test fun sharedCallChannelSupportsSelectionAndReleasesConsumedQueueBudget() = checkCall {
        val peer = MediaPeer(); val call: LiveCall = LinuxLiveCall { peer.apply { events = it } }
        try {
            call.connect { "answer" }
            val text = "x".repeat(64 * 1024)
            repeat(300) {
                assertTrue(peer.events.message(text.encodeToByteArray(), false))
                assertEquals(text, kotlinx.coroutines.selects.select { call.events.onReceive { it } })
            }
        } finally { call.close() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun failureWithdrawsAnEventWaitingForTheSharedReader() = checkCall {
        val peer = MediaPeer(); val call: LiveCall = LinuxLiveCall { peer.apply { events = it } }
        try {
            call.connect { "answer" }
            assertTrue(peer.events.message("stale action".encodeToByteArray(), false))
            while (call.events.isEmpty) kotlinx.coroutines.yield()
            peer.events.ended(IllegalStateException("synthetic transport failure"))
            val result = call.events.receiveCatching()
            assertTrue(result.isClosed)
            assertEquals("synthetic transport failure", result.exceptionOrNull()?.message)
        } finally { call.close() }
    }

    @Test fun transportFailureClosesTheSharedCallChannelWithItsCause() = checkCall {
        val peer = MediaPeer(); val call: LiveCall = LinuxLiveCall { peer.apply { events = it } }
        try {
            call.connect { "answer" }
            peer.events.ended(IllegalStateException("synthetic transport failure"))
            val failure = call.events.receiveCatching().exceptionOrNull()
            assertEquals("synthetic transport failure", failure?.message)
        } finally { call.close() }
    }

    @Test fun endSilencesLevelsClosesEventsAndRetiresExactlyOneMediaOwner() = checkCall {
        val peer = MediaPeer(); val call = LinuxLiveCall { peer.apply { events = it } }
        call.connect { "answer" }
        call.close(); call.close(); call.setMuted(false)
        assertEquals(1, peer.closes)
        assertEquals(0f, call.micLevel()); assertFalse(call.speaking())
        assertFalse(call.send("late")); assertTrue(call.events.receiveCatching().isClosed)
        assertEquals(listOf(false), peer.mutes)
    }

    @Test fun closeBeforeConnectDoesNotOpenTheMicrophone() = checkCall {
        var allocations = 0
        val call = LinuxLiveCall { allocations++; MediaPeer() }
        call.close()
        val failure = assertFailsWith<IllegalStateException> { call.connect { "answer" } }
        assertEquals("The call was closed.", failure.message)
        assertEquals(0, allocations)
    }

    @Test fun cancellationDuringAnswerRetiresMediaAndRejectsLateLevels() = checkCall {
        val peer = MediaPeer(); val call = LinuxLiveCall { peer.apply { events = it } }
        val answering = CompletableFuture<Unit>()
        val pending = async { call.connect { answering.complete(Unit); awaitCancellation() } }
        try {
            while (!answering.isDone) delay(5)
            pending.cancelAndJoin()
            assertEquals(1, peer.closes)
            assertEquals(0f, call.micLevel()); assertFalse(call.speaking())
        } finally { pending.cancelAndJoin(); call.close() }
    }

    @Test fun closeWhileMediaIsBeingCreatedReleasesTheLateOwner() = checkCall {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val peer = MediaPeer()
        val call = LinuxLiveCall { events ->
            entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); peer.apply { this.events = events }
        }
        val pending = async { runCatching { call.connect { "answer" } } }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(1, TimeUnit.SECONDS) })
            call.close(); release.countDown(); assertTrue(pending.await().isFailure)
            assertEquals(1, peer.closes); assertTrue(peer.mutes.isEmpty())
            assertEquals(0f, call.micLevel()); assertFalse(call.speaking())
        } finally { release.countDown(); pending.await(); call.close() }
    }

    @Test fun mediaInitializationFailureRetiresTheConstructedPeer() = checkCall {
        val peer = MediaPeer().apply { muteFailure = IllegalStateException("synthetic mute failure") }
        val call = LinuxLiveCall { peer.apply { events = it } }
        try {
            val failure = assertFailsWith<IllegalStateException> { call.connect { "answer" } }
            assertTrue(failure.message.orEmpty().contains("mute failure"))
            assertEquals(1, peer.closes)
            assertEquals(0f, call.micLevel()); assertFalse(call.speaking())
        } finally { call.close() }
    }

    @Test fun endingOneCallCannotMuteOrRetireTheNextCall() = checkCall {
        val first = MediaPeer(); val second = MediaPeer()
        val a = LinuxLiveCall { first.apply { events = it } }; val b = LinuxLiveCall { second.apply { events = it } }
        try {
            a.connect { "a" }; a.setMuted(true); a.close()
            b.connect { "b" }; a.setMuted(false)
            assertEquals(false, second.mutedAtOffer); assertFalse(second.closed)
            assertEquals(.6f, b.micLevel()); assertTrue(b.speaking())
        } finally { a.close(); b.close() }
    }

    private class MediaPeer : LiveMediaPeer {
        lateinit var events: LivePeerEvents
        var closed = false
        var microphoneMuted = false
        var mutedAtOffer: Boolean? = null
        var muteFailure: Throwable? = null
        val mutes = mutableListOf<Boolean>()
        var closes = 0
        var answer: String? = null
        var sent: String? = null
        override suspend fun offer(): String { mutedAtOffer = microphoneMuted; return "offer\r\n" }
        override suspend fun answer(sdp: String) { answer = sdp }
        override fun send(bytes: ByteArray): Boolean { sent = bytes.decodeToString(); return !closed }
        override fun setMuted(muted: Boolean) { muteFailure?.let { throw it }; this.microphoneMuted = muted; mutes += muted }
        override fun micLevel() = if (closed || microphoneMuted) 0f else .6f
        override fun speaking() = !closed
        override fun retire() { if (!closed) { closed = true; closes++ } }
        override fun close() { retire() }
    }

    private fun checkCall(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runBlocking { withTimeout(5_000, block) }
}
