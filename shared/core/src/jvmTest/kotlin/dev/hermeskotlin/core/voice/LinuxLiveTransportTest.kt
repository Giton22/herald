package dev.hermeskotlin.core.voice

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxLiveTransportTest {
    @Test fun exchangePreservesSdpCrLfAndTextEvents() = checkTransport {
        val peer = FakePeer()
        val transport = LinuxLiveTransport { listener -> peer.apply { events = listener } }
        try {
            transport.connect { offer ->
                assertEquals("v=0\r\nm=audio synthetic\r\n", offer)
                "v=0\r\na=synthetic-answer\r\n"
            }
            assertEquals("v=0\r\na=synthetic-answer\r\n", peer.applied)
            assertTrue(peer.events.message("你好 Δ\r\n".encodeToByteArray(), false))
            assertEquals("你好 Δ\r\n", transport.events.receive())
            assertTrue(transport.send("synthetic event"))
            assertEquals("synthetic event", peer.sent.single())
        } finally { transport.close() }
        assertTrue(peer.closed)
    }

    @Test fun cancellationOfPendingAnswerClosesPeerAndRejectsLateEvents() = checkTransport {
        val peer = FakePeer()
        val transport = LinuxLiveTransport { listener -> peer.apply { events = listener } }
        val answering = CompletableDeferred<Unit>()
        val operation = async { transport.connect { answering.complete(Unit); awaitCancellation() } }
        answering.await()
        operation.cancelAndJoin()
        assertTrue(peer.closed)
        assertFalse(transport.send("retired"))
        assertFalse(peer.events.message("late".encodeToByteArray(), false))
        assertTrue(transport.events.receiveCatching().isClosed)
    }

    @Test fun aLateAnswerFromANonCancellableCallbackCannotReachTheRetiredPeer() = checkTransport {
        val peer = FakePeer()
        val transport = LinuxLiveTransport { listener -> peer.apply { events = listener } }
        val answering = CompletableDeferred<Unit>()
        val release = CompletableDeferred<String>()
        val operation = async { transport.connect {
            answering.complete(Unit)
            withContext(NonCancellable) { release.await() }
        } }
        answering.await(); operation.cancel()
        try {
            repeat(25) { if (!peer.closed) kotlinx.coroutines.delay(10) }
            assertTrue(peer.closed, "Native resources must retire while a late answer is still pending")
        } finally { release.complete("late answer"); operation.join(); transport.close() }
        assertEquals(null, peer.applied)
        assertFalse(peer.events.message("retired".encodeToByteArray(), false))
    }

    @Test fun explicitCloseDropsQueuedEventsAndReleasesNativeOwnershipOnce() = checkTransport {
        val peer = FakePeer()
        val transport = LinuxLiveTransport { listener -> peer.apply { events = listener } }
        transport.connect { "answer\r\n" }
        peer.events.message("queued".encodeToByteArray(), false)
        transport.close(); transport.close()
        assertEquals(1, peer.closes)
        assertTrue(transport.events.receiveCatching().isClosed)
        assertFalse(transport.send("retired"))
    }

    @Test fun failedNativeConnectionCancelsThePendingAnswerAndClosesPeer() = checkTransport {
        val peer = FakePeer()
        val transport = LinuxLiveTransport { listener -> peer.apply { events = listener } }
        val answering = CompletableDeferred<Unit>()
        var cancelled = false
        val original = IllegalStateException("Synthetic disconnected")
        val operation = async { runCatching { transport.connect {
            answering.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        } } }
        answering.await(); peer.events.ended(original)
        val failure = operation.await().exceptionOrNull()
        assertTrue(generateSequence(failure) { it.cause }.any { it === original })
        assertTrue(cancelled && peer.closed)
    }

    @Test fun overflowingEventsEndsWithoutDeliveringAPartialQueue() = checkTransport {
        val peer = FakePeer()
        val transport = LinuxLiveTransport { listener -> peer.apply { events = listener } }
        try {
            transport.connect { "answer" }
            repeat(LinuxLiveTransport.MAX_QUEUED_EVENTS) { assertTrue(peer.events.message("queued".encodeToByteArray(), false)) }
            assertFalse(peer.events.message("overflow".encodeToByteArray(), false))
            val result = transport.events.receiveCatching()
            assertTrue(result.isClosed && result.exceptionOrNull() is IllegalStateException)
            assertFalse(transport.send("retired"))
        } finally { transport.close() }
    }

    @Test fun binaryMalformedAndOversizedEventsEndWithoutForwardingPayload() = checkTransport {
        for ((bytes, binary) in listOf(byteArrayOf(1) to true, byteArrayOf(0xc3.toByte(), 0x28) to false, ByteArray(1024 * 1024 + 1) to false)) {
            val peer = FakePeer()
            val transport = LinuxLiveTransport { listener -> peer.apply { events = listener } }
            try {
                transport.connect { "answer" }
                assertFalse(peer.events.message(bytes, binary))
                val result = transport.events.receiveCatching()
                assertTrue(result.isClosed && result.exceptionOrNull() !is CancellationException)
                assertFalse(transport.send("retired"))
            } finally { transport.close() }
        }
    }

    @Test fun oversizedOutgoingEventEndsTheCallInsteadOfDroppingItsReply() = checkTransport {
        val peer = FakePeer()
        val transport = LinuxLiveTransport { listener -> peer.apply { events = listener } }
        try {
            transport.connect { "answer" }
            assertFalse(transport.send("x".repeat(1024 * 1024 + 1)))
            val result = transport.events.tryReceive()
            assertTrue(result.isClosed && result.exceptionOrNull() is IllegalStateException)
            assertTrue(peer.closed)
        } finally { transport.close() }
    }

    @Test fun closingBeforeConnectDoesNotCreateNativeResources() = checkTransport {
        var created = 0
        val transport = LinuxLiveTransport { created++; FakePeer() }
        transport.close()
        runCatching { transport.connect { "answer" } }
        assertEquals(0, created)
    }

    @Test fun secondConnectCannotRetireTheExistingCall() = checkTransport {
        val peer = FakePeer()
        val transport = LinuxLiveTransport { listener -> peer.apply { events = listener } }
        try {
            transport.connect { "answer" }
            assertFailsWith<IllegalStateException> { transport.connect { error("Must not exchange twice") } }
            assertFalse(peer.closed)
            assertTrue(transport.send("still active"))
        } finally { transport.close() }
    }

    @Test fun closeDuringConstructionReleasesTheLatePeerExactlyOnce() = checkTransport {
        val creating = CountDownLatch(1); val release = CountDownLatch(1)
        val peer = FakePeer()
        val transport = LinuxLiveTransport { listener ->
            peer.events = listener; creating.countDown()
            check(release.await(2, TimeUnit.SECONDS)); peer
        }
        val operation = async { runCatching { transport.connect { "answer" } } }
        try {
            kotlinx.coroutines.yield()
            assertTrue(withContext(Dispatchers.IO) { creating.await(1, TimeUnit.SECONDS) })
            transport.close(); release.countDown()
            operation.await()
            assertEquals(1, peer.closes)
            assertTrue(transport.events.receiveCatching().isClosed)
        } finally { release.countDown(); transport.close() }
    }

    @Test fun callerDeadlineRemainsCancellation() = checkTransport {
        val peer = FakePeer()
        val transport = LinuxLiveTransport { listener -> peer.apply { events = listener } }
        val failure = runCatching { withTimeout(50) { transport.connect { awaitCancellation() } } }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertTrue(peer.closed)
    }

    @Test fun nativeDescriptionDeadlineIsAnOperationFailure() = checkTransport {
        val peer = FakePeer().apply { offerBlock = { withTimeout(25) { awaitCancellation() } } }
        val transport = LinuxLiveTransport { listener -> peer.apply { events = listener } }
        val failure = runCatching { transport.connect { error("No offer was created") } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException && failure !is CancellationException)
        assertTrue(peer.closed)
    }

    @Test fun connectionFailureKeepsCleanupError() = checkTransport {
        val original = IllegalStateException("Synthetic offer failure")
        val cleanup = IllegalStateException("Synthetic close failure")
        val peer = FakePeer().apply { offerBlock = { throw original }; closeFailure = cleanup }
        val transport = LinuxLiveTransport { listener -> peer.apply { events = listener } }
        val failure = runCatching { transport.connect { error("No offer") } }.exceptionOrNull()
        assertTrue(peer.closed)
        assertTrue(generateSequence(failure) { it.cause }.any { it === original })
        assertTrue(generateSequence(failure) { it.cause }.flatMap { it.suppressed.asSequence() }
            .any { suppressed -> generateSequence(suppressed) { it.cause }.any { it === cleanup } })
    }

    @Test fun anInlineConsumerCanCloseWhileNativeSendWaitsForItsCallback() = checkTransport {
        val peer = FakePeer()
        val transport = LinuxLiveTransport { listener -> peer.apply { events = listener } }
        var callback: Thread? = null
        try {
            transport.connect { "answer" }
            val receiver = async(Dispatchers.Unconfined) { transport.events.receive(); transport.close() }
            peer.sendBlock = {
                val returned = CompletableFuture<Unit>()
                callback = Thread.startVirtualThread {
                    try { peer.events.message("close".encodeToByteArray(), false); returned.complete(Unit) }
                    catch (error: Throwable) { returned.completeExceptionally(error) }
                }
                returned.get(1, TimeUnit.SECONDS)
                true
            }
            assertTrue(transport.send("trigger"))
            receiver.await()
            assertTrue(peer.closed)
        } finally { transport.close(); callback?.join(2_000) }
    }

    @Test fun everyOwnerCloseWaitsForTheSingleRetirement() = checkTransport {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val peer = FakePeer().apply { closeBlock = { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) } }
        val transport = LinuxLiveTransport { listener -> peer.apply { events = listener } }
        transport.connect { "answer" }
        val first = async(Dispatchers.IO) { transport.close() }
        assertTrue(withContext(Dispatchers.IO) { entered.await(1, TimeUnit.SECONDS) })
        val second = async(Dispatchers.IO) { transport.close() }
        try {
            kotlinx.coroutines.delay(50)
            assertFalse(second.isCompleted, "A later owner close must join the in-progress release")
        } finally { release.countDown(); first.await(); second.await() }
        assertEquals(1, peer.closes)
    }

    @Test fun theRetirementErrorIsReportedOnce() = checkTransport {
        val peer = FakePeer().apply { closeFailure = IllegalStateException("Synthetic release failure") }
        val transport = LinuxLiveTransport { listener -> peer.apply { events = listener } }
        transport.connect { "answer" }
        assertFailsWith<IllegalStateException> { transport.close() }
        transport.close()
        assertEquals(1, peer.closes)
    }

    @Test fun aCloseInFinallyDoesNotHideTheConnectFailure() = checkTransport {
        val original = IllegalStateException("Synthetic offer failure")
        val peer = FakePeer().apply { offerBlock = { throw original }; closeFailure = IllegalStateException("Synthetic close failure") }
        val transport = LinuxLiveTransport { listener -> peer.apply { events = listener } }
        val failure = runCatching { try { transport.connect { "answer" } } finally { transport.close() } }.exceptionOrNull()
        assertTrue(generateSequence(failure) { it.cause }.any { it === original })
    }

    @Test fun aCloseFromElsewhereDuringSetupFailsTheCallerInsteadOfCancellingIt() = checkTransport {
        val peer = FakePeer()
        val transport = LinuxLiveTransport { listener -> peer.apply { events = listener } }
        val answering = CompletableDeferred<Unit>()
        val operation = async { runCatching { transport.connect { answering.complete(Unit); awaitCancellation() } } }
        answering.await()
        transport.close()
        val failure = operation.await().exceptionOrNull()
        assertTrue(failure is IllegalStateException, "Got $failure")
        assertTrue(peer.closed)
    }

    @Test fun anOversizedSendDoesNotWaitForTheRelease() = checkTransport {
        val release = CountDownLatch(1)
        val peer = FakePeer().apply { closeBlock = { check(release.await(2, TimeUnit.SECONDS)) } }
        val transport = LinuxLiveTransport { listener -> peer.apply { events = listener } }
        transport.connect { "answer" }
        try {
            val started = System.nanoTime()
            assertFalse(transport.send("x".repeat(1024 * 1024 + 1)))
            assertTrue(System.nanoTime() - started < 500_000_000L, "send blocked on the native release")
            assertTrue(peer.closed)
        } finally { release.countDown(); transport.close() }
    }

    private class FakePeer : LivePeer {
        lateinit var events: LivePeerEvents
        var applied: String? = null
        var offerBlock: (suspend () -> String)? = null
        var closeFailure: Throwable? = null
        var closeBlock: (() -> Unit)? = null
        var sendBlock: ((ByteArray) -> Boolean)? = null
        val sent = mutableListOf<String>()
        var closed = false
        var closes = 0
        override suspend fun offer() = offerBlock?.invoke() ?: "v=0\r\nm=audio synthetic\r\n"
        override suspend fun answer(sdp: String) { applied = sdp }
        override fun send(bytes: ByteArray): Boolean {
            if (closed || applied == null) return false
            sendBlock?.let { return it(bytes) }
            sent += bytes.decodeToString(); return true
        }
        override fun retire() { if (!closed) { closes++; closed = true } }
        override fun close() { retire(); closeBlock?.invoke(); closeFailure?.let { throw it } }
    }
    private fun checkTransport(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(5_000, block) }
}
