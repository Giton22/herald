package dev.hermeskotlin.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import kotlin.system.exitProcess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinuxGlobalShortcutTest {
    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!check() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(check(), "Owned portal state did not arrive within its deadline")
    }
    private fun LinkedBlockingQueue<LinuxGlobalShortcut.State>.bound() {
        await { contains(LinuxGlobalShortcut.State.Bound) }
    }
    @Test fun versionOneEarlyResponsesBindAndOnlyTheOwnedShortcutActivates() {
        ShortcutPortalFixture().use { fixture ->
            val states = LinkedBlockingQueue<LinuxGlobalShortcut.State>()
            val presses = AtomicInteger()
            LinuxGlobalShortcut({ presses.incrementAndGet() }, { state, _ -> states.add(state) }, fixture.address).use {
                states.bound()
                fixture.connect().use { stranger ->
                    stranger.sendMessage(DBus.NameOwnerChanged("/org/freedesktop/DBus", ShortcutPortalFixture.NAME, fixture.bus.uniqueName, ""))
                    fixture.service.activate(sender = stranger)
                    stranger.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java).GetId()
                }
                fixture.service.activate(handle = fixture.service.session + "_other")
                fixture.service.activate(id = "other")
                fixture.service.activate()
                fixture.service.activate()
                await { presses.get() == 1 }
                fixture.service.release()
                fixture.service.activate()
                await { presses.get() == 2 }
                assertEquals(1, fixture.service.binds.get())
            }
            fixture.service.sessionClosed.get(3, TimeUnit.SECONDS)
        }
    }
    @Test fun declineAndAnEmptyBindingDoNotRetryOrActivate() {
        for (denied in listOf(true, false)) ShortcutPortalFixture().use { fixture ->
            fixture.service.response = if (denied) 1 else 0
            fixture.service.empty = !denied
            val states = LinkedBlockingQueue<LinuxGlobalShortcut.State>()
            val presses = AtomicInteger()
            LinuxGlobalShortcut({ presses.incrementAndGet() }, { state, _ -> states.add(state) }, fixture.address).use {
                await { states.contains(LinuxGlobalShortcut.State.Declined) }
                fixture.service.activate()
                Thread.sleep(100)
                assertEquals(0, presses.get())
                assertEquals(1, fixture.service.binds.get())
                fixture.service.sessionClosed.get(3, TimeUnit.SECONDS)
            }
        }
    }
    @Test fun disablingDuringConsentClosesRequestAndSessionAndIgnoresLateEvents() {
        ShortcutPortalFixture().use { fixture ->
            fixture.service.pending = true
            val presses = AtomicInteger()
            val shortcut = LinuxGlobalShortcut({ presses.incrementAndGet() }, { _, _ -> }, fixture.address)
            try { fixture.service.started.get(3, TimeUnit.SECONDS) } finally { shortcut.close() }
            fixture.service.requestClosed.get(3, TimeUnit.SECONDS)
            fixture.service.sessionClosed.get(3, TimeUnit.SECONDS)
            fixture.service.bus.sendMessage(ShortcutsPortal.Request.Response(fixture.service.request, UInt32(0), emptyMap()))
            fixture.service.activate()
            Thread.sleep(100)
            assertEquals(0, presses.get())
            shortcut.close()
        }
    }
    @Test fun wrongReturnedRequestHandleFailsClosedAndReleasesOwnedResources() {
        ShortcutPortalFixture().use { fixture ->
            fixture.service.wrongHandle = true
            val states = LinkedBlockingQueue<LinuxGlobalShortcut.State>()
            LinuxGlobalShortcut({ error("No activation") }, { state, _ -> states.add(state) }, fixture.address).use {
                await { states.contains(LinuxGlobalShortcut.State.Unavailable) }
                fixture.service.requestClosed.get(3, TimeUnit.SECONDS)
                fixture.service.sessionClosed.get(3, TimeUnit.SECONDS)
            }
        }
    }
    @Test fun portalOwnerReplacementRebindsAndRejectsTheOldSession() {
        ShortcutPortalFixture().use { fixture ->
            val states = LinkedBlockingQueue<LinuxGlobalShortcut.State>()
            val presses = AtomicInteger()
            fixture.connect().use { replacement ->
                val next = ShortcutPortalFixture.Service(replacement)
                replacement.exportObject(next)
                LinuxGlobalShortcut({ presses.incrementAndGet() }, { state, _ -> states.add(state) }, fixture.address).use {
                    states.bound()
                    states.clear()
                    fixture.bus.releaseBusName(ShortcutPortalFixture.NAME)
                    replacement.requestBusName(ShortcutPortalFixture.NAME)
                    states.bound()
                    fixture.service.activate()
                    next.activate()
                    await { presses.get() == 1 }
                    assertEquals(1, next.binds.get())
                }
                assertNotNull(next.sessionClosed.get(3, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun disablingBeforeSessionResponseStillRetiresTheOwnedSession() {
        ShortcutPortalFixture().use { fixture ->
            fixture.service.pendingCreate = true
            val shortcut = LinuxGlobalShortcut({ error("No activation") }, { _, _ -> }, fixture.address)
            try { fixture.service.createStarted.get(3, TimeUnit.SECONDS) } finally { shortcut.close() }
            fixture.service.requestClosed.get(3, TimeUnit.SECONDS)
            fixture.service.sessionClosed.get(3, TimeUnit.SECONDS)
            assertEquals(0, fixture.service.binds.get())
        }
    }

    @Test fun unsupportedPortalVersionNeverRequestsConsent() {
        ShortcutPortalFixture().use { fixture ->
            fixture.service.version = 0
            val states = LinkedBlockingQueue<LinuxGlobalShortcut.State>()
            LinuxGlobalShortcut({ error("No activation") }, { state, _ -> states.add(state) }, fixture.address).use {
                await { states.contains(LinuxGlobalShortcut.State.Unavailable) }
                assertEquals(0, fixture.service.binds.get())
                assertTrue(!fixture.service.createStarted.isDone)
            }
        }
    }

    @Test fun malformedTriggerDescriptionIsNotDisplayedAsABinding() {
        ShortcutPortalFixture().use { fixture ->
            fixture.service.trigger = "bad\ntrigger"
            val states = LinkedBlockingQueue<LinuxGlobalShortcut.State>()
            LinuxGlobalShortcut({ error("No activation") }, { state, _ -> states.add(state) }, fixture.address).use {
                await { states.contains(LinuxGlobalShortcut.State.Unavailable) }
                fixture.service.sessionClosed.get(3, TimeUnit.SECONDS)
                assertTrue(!states.contains(LinuxGlobalShortcut.State.Bound))
            }
        }
    }

    @Test fun portalRestartDuringConsentRetiresTheOldRequestBeforeRebinding() {
        ShortcutPortalFixture().use { fixture ->
            fixture.service.pending = true
            val states = LinkedBlockingQueue<LinuxGlobalShortcut.State>()
            fixture.connect().use { replacement ->
                val next = ShortcutPortalFixture.Service(replacement)
                replacement.exportObject(next)
                LinuxGlobalShortcut({}, { state, _ -> states.add(state) }, fixture.address).use {
                    fixture.service.started.get(3, TimeUnit.SECONDS)
                    fixture.bus.releaseBusName(ShortcutPortalFixture.NAME)
                    replacement.requestBusName(ShortcutPortalFixture.NAME)
                    states.bound()
                    fixture.service.requestClosed.get(3, TimeUnit.SECONDS)
                    fixture.service.sessionClosed.get(3, TimeUnit.SECONDS)
                    assertEquals(1, next.binds.get())
                }
            }
        }
    }

    @Test fun revocationDuringConsentEndsTheWaitAndClosesTheRequest() {
        ShortcutPortalFixture().use { fixture ->
            fixture.service.pending = true
            val states = LinkedBlockingQueue<LinuxGlobalShortcut.State>()
            val presses = AtomicInteger()
            LinuxGlobalShortcut({ presses.incrementAndGet() }, { state, _ -> states.add(state) }, fixture.address).use {
                fixture.service.started.get(3, TimeUnit.SECONDS)
                fixture.bus.sendMessage(ShortcutsPortal.Session.Closed(fixture.service.session, emptyMap()))
                // The desktop ended it, like a refusal: the portal itself still works.
                await { states.contains(LinuxGlobalShortcut.State.Declined) }
                assertTrue(LinuxGlobalShortcut.State.Unavailable !in states)
                fixture.service.requestClosed.get(3, TimeUnit.SECONDS)
                fixture.service.sessionClosed.get(3, TimeUnit.SECONDS)
                fixture.service.activate()
                Thread.sleep(100)
                assertEquals(0, presses.get())
            }
        }
    }

    @Test fun aHostInstallNamesItselfBeforeItsFirstRequestAndAFlatpakDoesNot() {
        // Newer portals refuse a host app without an ID ("An app id is required"), so a .deb never bound.
        ShortcutPortalFixture().use { fixture ->
            val states = LinkedBlockingQueue<LinuxGlobalShortcut.State>()
            LinuxGlobalShortcut({}, { state, _ -> states.add(state) }, fixture.address, flatpakInfo = File("/nonexistent/.flatpak-info")).use {
                states.bound()
                assertEquals(listOf("Register kyllene-Kyllene", "CreateSession"), fixture.service.calls.toList())
            }
        }
        val sandbox = Files.createTempFile("kyllene-flatpak-info", "")
        try {
            ShortcutPortalFixture().use { fixture ->
                val states = LinkedBlockingQueue<LinuxGlobalShortcut.State>()
                LinuxGlobalShortcut({}, { state, _ -> states.add(state) }, fixture.address, flatpakInfo = sandbox.toFile()).use {
                    states.bound()
                    assertEquals(listOf("CreateSession"), fixture.service.calls.toList())
                }
            }
        } finally { Files.deleteIfExists(sandbox) }
    }

    @Test fun aPortalWithoutTheRegistryStillBindsAHostInstall() {
        ShortcutPortalFixture().use { fixture ->
            fixture.service.registry = false
            val states = LinkedBlockingQueue<LinuxGlobalShortcut.State>()
            LinuxGlobalShortcut({}, { state, _ -> states.add(state) }, fixture.address, flatpakInfo = File("/nonexistent/.flatpak-info")).use {
                states.bound()
            }
        }
    }

    @Test fun aGrantWithoutKeysIsUnassignedUntilTheUserPicksOne() {
        // KDE answers "" once its dialog was closed or the key cleared, and remembers it.
        ShortcutPortalFixture().use { fixture ->
            fixture.service.trigger = ""
            val states = LinkedBlockingQueue<Pair<LinuxGlobalShortcut.State, String?>>()
            LinuxGlobalShortcut({}, { state, trigger -> states.add(state to trigger) }, fixture.address).use {
                await { states.any { it.first == LinuxGlobalShortcut.State.Unassigned } }
                assertTrue(states.none { it.first == LinuxGlobalShortcut.State.Bound })
                fixture.service.rebind("Ctrl+Alt+K")
                await { (LinuxGlobalShortcut.State.Bound to "Ctrl+Alt+K") in states }
                assertEquals(1, fixture.service.binds.get())
            }
        }
    }

    @Test fun aMissingReleaseDoesNotLockTheShortcut() {
        // KDE sends no Deactivated when another tool fires the shortcut.
        ShortcutPortalFixture().use { fixture ->
            val states = LinkedBlockingQueue<LinuxGlobalShortcut.State>()
            val presses = AtomicInteger()
            LinuxGlobalShortcut({ presses.incrementAndGet() }, { state, _ -> states.add(state) }, fixture.address).use {
                states.bound()
                fixture.service.activate()
                await { presses.get() == 1 }
                Thread.sleep(1_100)
                fixture.service.activate()
                await { presses.get() == 2 }
            }
        }
    }

    @Test fun revocationAfterBindingIsDeclinedNotUnavailable() {
        ShortcutPortalFixture().use { fixture ->
            val states = LinkedBlockingQueue<LinuxGlobalShortcut.State>()
            LinuxGlobalShortcut({}, { state, _ -> states.add(state) }, fixture.address).use {
                states.bound()
                fixture.bus.sendMessage(ShortcutsPortal.Session.Closed(fixture.service.session, emptyMap()))
                await { states.contains(LinuxGlobalShortcut.State.Declined) }
                assertTrue(LinuxGlobalShortcut.State.Unavailable !in states)
            }
        }
    }

    @Test fun aSessionHandleSentAsAnObjectPathBinds() {
        ShortcutPortalFixture().use { fixture ->
            fixture.service.handleAsPath = true
            val states = LinkedBlockingQueue<LinuxGlobalShortcut.State>()
            LinuxGlobalShortcut({}, { state, _ -> states.add(state) }, fixture.address).use { states.bound() }
        }
    }

    @Test fun aPortalThatCannotBeStartedYetBindsOnceItAppears() {
        // At login the portal may not be up; a failed start used to end the worker for good.
        ShortcutPortalFixture(claim = false).use { fixture ->
            val states = LinkedBlockingQueue<LinuxGlobalShortcut.State>()
            LinuxGlobalShortcut({}, { state, _ -> states.add(state) }, fixture.address).use {
                await { states.contains(LinuxGlobalShortcut.State.Unavailable) }
                fixture.claimName()
                states.bound()
            }
        }
    }

    @Test fun closingWhileThePortalIsAbsentEndsTheWorker() {
        ShortcutPortalFixture(claim = false).use { fixture ->
            fun workers() = Thread.getAllStackTraces().keys.count { it.name == "kyllene-shortcut-portal" && it.isAlive }
            val before = workers()
            val states = LinkedBlockingQueue<LinuxGlobalShortcut.State>()
            val shortcut = LinuxGlobalShortcut({}, { state, _ -> states.add(state) }, fixture.address)
            await { states.contains(LinuxGlobalShortcut.State.Unavailable) }
            assertEquals(before + 1, workers())
            shortcut.close()
            await { workers() == before }
        }
    }

    @Test fun aKeyChangeRightAfterRevocationDoesNotOverwriteDeclined() {
        ShortcutPortalFixture().use { fixture ->
            val states = LinkedBlockingQueue<LinuxGlobalShortcut.State>()
            LinuxGlobalShortcut({}, { state, _ -> states.add(state) }, fixture.address).use {
                states.bound()
                states.clear()
                fixture.bus.sendMessage(ShortcutsPortal.Session.Closed(fixture.service.session, emptyMap()))
                fixture.service.rebind("Ctrl+Alt+K")
                await { states.contains(LinuxGlobalShortcut.State.Declined) }
                Thread.sleep(200)
                assertEquals(LinuxGlobalShortcut.State.Declined, states.last())
            }
        }
    }

    @Test fun aRuntimeWithoutAuthenticationClassesReportsUnavailable() =
        runtime("java.base,java.logging", LinuxGlobalShortcut.State.Unavailable)

    @Test fun theMinimalLinuxRuntimeAuthenticatesAndBinds() =
        runtime("java.base,java.logging,jdk.security.auth", LinuxGlobalShortcut.State.Bound)

    private fun runtime(modules: String, expected: LinuxGlobalShortcut.State) {
        ShortcutPortalFixture().use { fixture ->
            val classes = listOf(LinuxGlobalShortcut::class.java, ShortcutRuntimeProbe::class.java, Unit::class.java,
                DBusConnectionBuilder::class.java, Class.forName("org.slf4j.LoggerFactory"),
                Class.forName("org.freedesktop.dbus.transport.junixsocket.JUnixSocketTransportProvider"),
                Class.forName("org.newsclub.net.unix.AFUNIXSocket"),
                Class.forName("org.newsclub.lib.junixsocket.common.NarMetadata"))
            val classpath = classes.map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
            val java = File(System.getProperty("java.home"), "bin/java").path
            val child = ProcessBuilder(java, "--limit-modules", modules, "-cp", classpath,
                ShortcutRuntimeProbe::class.java.name, fixture.address, expected.name).redirectErrorStream(true).start()
            try {
                assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Owned backend must retire")
                assertEquals(0, child.exitValue(), child.inputStream.bufferedReader().readText())
                assertEquals(if (expected == LinuxGlobalShortcut.State.Bound) 1 else 0, fixture.service.binds.get())
            } finally {
                if (child.isAlive) child.destroyForcibly()
                child.waitFor(3, TimeUnit.SECONDS)
            }
        }
    }
}

/** Simulates the small runtime used by an installer, without changing the test JVM's module graph. */
object ShortcutRuntimeProbe {
    @JvmStatic fun main(args: Array<String>) {
        val expected = LinuxGlobalShortcut.State.valueOf(args[1])
        val authentication = runCatching { Class.forName("com.sun.security.auth.module.UnixSystem") }
        if (expected == LinuxGlobalShortcut.State.Bound) authentication.getOrThrow()
        else check(authentication.exceptionOrNull() is ClassNotFoundException)
        Class.forName("org.freedesktop.dbus.transport.junixsocket.JUnixSocketTransportProvider")
        // Warm the JDK's process-lifetime Unix socket plumbing before counting backend resources.
        java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX).close()
        (Class.forName("org.newsclub.net.unix.AFUNIXSocket").getMethod("newInstance").invoke(null) as AutoCloseable).close()
        val sockets = sockets()
        val reached = CountDownLatch(1)
        val shortcut = LinuxGlobalShortcut({ error("No activation") }, { state, _ ->
            println("Shortcut state: $state")
            if (state == expected) reached.countDown()
        }, args[0])
        val reported = try { reached.await(3, TimeUnit.SECONDS) } finally { shortcut.close() }
        check(sockets() == sockets) { "Unavailable backend left a socket open" }
        exitProcess(if (reported) 0 else 2)
    }

    private fun sockets(): Long = Files.list(Path.of("/proc/self/fd")).use { entries ->
        entries.filter { runCatching { Files.readSymbolicLink(it).toString().startsWith("socket:") }.getOrDefault(false) }.count()
    }
}
