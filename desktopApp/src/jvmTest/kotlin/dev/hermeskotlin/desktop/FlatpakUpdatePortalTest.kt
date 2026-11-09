package dev.hermeskotlin.desktop

import dev.hermeskotlin.ui.update.ManagedUpdateFailure
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.freedesktop.dbus.interfaces.Introspectable
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.UInt64
import org.freedesktop.dbus.types.Variant

class FlatpakUpdatePortalTest {
    @Test
    fun monitorSubscribesBeforeCreateReplyAndDoesNotUpdateWithoutConsent() = runBlocking {
        FlatpakPortalFixture().use { f ->
            val events = LinkedBlockingQueue<FlatpakUpdateEvent>()
            val gate = CompletableFuture<Unit>().also { f.service.createGate = it }
            val opening = async(Dispatchers.IO) { FlatpakUpdatePortal.open(f.address, 1_500) { events += it } }
            try {
                assertEquals(FlatpakUpdateInfo(null, "A", null), assertIs<FlatpakUpdateEvent.Info>(events.poll(1, TimeUnit.SECONDS)).info)
                assertFalse(opening.isCompleted)
            } finally { gate.complete(Unit) }
            assertNotNull(opening.await()).use { assertEquals(0, f.service.updates.get()) }
            f.awaitDisconnected()
            assertEquals(1, f.service.closes.get())
        }
    }

    @Test
    fun unsupportedOrMalformedVersionCreatesNoMonitor() = runBlocking {
        for (version in listOf(UInt32(1), UInt64(2))) FlatpakPortalFixture().use { f ->
            f.service.version = version
            assertNull(FlatpakUpdatePortal.open(f.address) {})
            assertEquals(0, f.service.creates.get())
        }
    }

    @Test
    fun explicitUpdateUsesParentAndEmptyOptionsWithEarlyTerminalProgress() = runBlocking {
        FlatpakPortalFixture().use { f ->
            val events = LinkedBlockingQueue<FlatpakUpdateEvent>()
            assertNotNull(FlatpakUpdatePortal.open(f.address) { events += it }).use { monitor ->
                assertIs<FlatpakUpdateEvent.Info>(events.poll(1, TimeUnit.SECONDS))
                monitor.update("x11:abc")
                assertEquals(FlatpakUpdateEvent.Progress(0, 100), events.poll(1, TimeUnit.SECONDS))
                assertEquals(FlatpakUpdateEvent.Progress(2), events.poll(1, TimeUnit.SECONDS))
                assertEquals(listOf("x11:abc"), f.service.parents.toList())
                assertEquals(1, f.service.updates.get())
            }
            f.awaitDisconnected()
        }
    }

    @Test
    fun progressRejectsWrongSenderPathAndUnsignedTypes() = runBlocking {
        FlatpakPortalFixture().use { f ->
            val events = LinkedBlockingQueue<FlatpakUpdateEvent>()
            assertNotNull(FlatpakUpdatePortal.open(f.address) { events += it }).use {
                assertIs<FlatpakUpdateEvent.Info>(events.poll(1, TimeUnit.SECONDS))
                f.connect().use { intruder ->
                    f.service.progress(mapOf("status" to Variant(UInt32(2))), sender = intruder)
                    f.service.progress(mapOf("status" to Variant(UInt32(2))), path = f.service.handle + "_other")
                    f.service.progress(mapOf("status" to Variant(UInt64(2)), "progress" to Variant(UInt64(100))))
                    assertEquals(FlatpakUpdateEvent.Progress(null), events.poll(1, TimeUnit.SECONDS))
                    f.service.progress(mapOf("status" to Variant(UInt32(0)), "progress" to Variant(UInt32(17))))
                    assertEquals(FlatpakUpdateEvent.Progress(0, 17), events.poll(1, TimeUnit.SECONDS))
                    assertNull(events.poll(100, TimeUnit.MILLISECONDS))
                }
            }
        }
    }

    @Test
    fun progressErrorsHaveSafeTypedGuidance() = runBlocking {
        val cases = mapOf("AccessDenied" to ManagedUpdateFailure.Denied,
            "NotSupported" to ManagedUpdateFailure.SystemUpdateRequired, "Failed" to ManagedUpdateFailure.Failed)
        for ((error, failure) in cases) FlatpakPortalFixture().use { f ->
            f.service.error = "org.freedesktop.DBus.Error.$error"
            val events = LinkedBlockingQueue<FlatpakUpdateEvent>()
            assertNotNull(FlatpakUpdatePortal.open(f.address) { events += it }).use { monitor ->
                assertIs<FlatpakUpdateEvent.Info>(events.poll(1, TimeUnit.SECONDS))
                monitor.update("")
                assertEquals(FlatpakUpdateEvent.Progress(0, 100), events.poll(1, TimeUnit.SECONDS))
                assertEquals(FlatpakUpdateEvent.Failure(failure), events.poll(1, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun unexpectedHandleDisconnectsWithoutClosingAnUnverifiedObject() = runBlocking {
        FlatpakPortalFixture().use { f ->
            f.service.wrongHandle = true
            assertNull(FlatpakUpdatePortal.open(f.address) {})
            assertEquals(1, f.service.creates.get())
            f.awaitDisconnected()
            assertEquals(0, f.service.closes.get())
        }
    }

    @Test
    fun restartUsesOnlyTheSameAppLatestVersionCommand() = runBlocking {
        FlatpakPortalFixture().use { f ->
            assertNotNull(FlatpakUpdatePortal.open(f.address) {}).use { monitor ->
                assertEquals(42L, monitor.restart())
                assertEquals(1, f.service.spawns.get())
                val xml = f.bus.getRemoteObject(f.bus.uniqueName, FlatpakPortalFixture.PATH, Introspectable::class.java).Introspect()
                val spawn = assertNotNull(Regex("<method\\s+name=['\"]Spawn['\"][^>]*>(.*?)</method>",
                    RegexOption.DOT_MATCHES_ALL).find(xml)).groupValues[1]
                val types = Regex("type=['\"]([^'\"]+)['\"]").findAll(spawn).map { it.groupValues[1] }.toList()
                assertEquals(listOf("ay", "aay", "a{uh}", "a{ss}", "u", "a{sv}", "u"), types)
            }
            f.awaitDisconnected()
        }
    }

    @Test
    fun lostPortalOwnerInvalidatesOperations() = runBlocking {
        FlatpakPortalFixture().use { f ->
            val events = LinkedBlockingQueue<FlatpakUpdateEvent>()
            assertNotNull(FlatpakUpdatePortal.open(f.address) { events += it }).use { monitor ->
                assertIs<FlatpakUpdateEvent.Info>(events.poll(1, TimeUnit.SECONDS))
                f.bus.releaseBusName(FlatpakPortalFixture.NAME)
                assertEquals(FlatpakUpdateEvent.Failure(ManagedUpdateFailure.Unavailable), events.poll(1, TimeUnit.SECONDS))
                assertTrue(runCatching { monitor.update("") }.isFailure)
                assertEquals(0, f.service.updates.get())
            }
            f.awaitDisconnected()
            assertEquals(0, f.service.closes.get())
        }
    }

    @Test
    fun methodTimeoutDisconnectsWithoutReplayingUpdate() = runBlocking {
        FlatpakPortalFixture().use { f ->
            val gate = CompletableFuture<Unit>().also { f.service.updateGate = it }
            assertNotNull(FlatpakUpdatePortal.open(f.address, 200) {}).use { monitor ->
                try { assertTrue(runCatching { monitor.update("") }.isFailure) }
                finally { gate.complete(Unit) }
                f.awaitDisconnected()
                assertEquals(1, f.service.updates.get())
            }
        }
    }

    @Test
    fun createTimeoutDisconnectsWithoutGuessingACloseTarget() = runBlocking {
        FlatpakPortalFixture().use { f ->
            val gate = CompletableFuture<Unit>().also { f.service.createGate = it }
            try { assertNull(FlatpakUpdatePortal.open(f.address, 200) {}) }
            finally { gate.complete(Unit) }
            assertEquals(1, f.service.creates.get())
            f.awaitDisconnected()
            assertEquals(0, f.service.closes.get())
        }
    }

    @Test
    fun cancelWhileCreatingDisconnectsWithoutWaitingForTheReply() = runBlocking {
        FlatpakPortalFixture().use { f ->
            val gate = CompletableFuture<Unit>().also { f.service.createGate = it }
            val events = LinkedBlockingQueue<FlatpakUpdateEvent>()
            val opening = async(Dispatchers.IO) { FlatpakUpdatePortal.open(f.address, 1_500) { events += it } }
            try {
                assertIs<FlatpakUpdateEvent.Info>(events.poll(1, TimeUnit.SECONDS))
                opening.cancelAndJoin()
                f.awaitDisconnected()
                assertEquals(0, f.service.closes.get())
            } finally { gate.complete(Unit) }
        }
    }
}
