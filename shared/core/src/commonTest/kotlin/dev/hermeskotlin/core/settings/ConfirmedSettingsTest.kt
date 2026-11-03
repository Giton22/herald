package dev.hermeskotlin.core.settings

import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import dev.hermeskotlin.core.storage.KeyValueStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfirmedSettingsTest {
    @Test fun earlyUpdatesPersistWithoutDurableWrite() = runTest {
        val memory = InMemoryKeyValueStore()
        memory.put("settings.v1", """{"theme":"Dark","uncertainGitInstalls":["old-marker"]}""")
        val release = CompletableDeferred<Unit>()
        val backing = object : KeyValueStore by memory {
            override suspend fun get(key: String): String? {
                release.await()
                return memory.get(key)
            }
        }
        val settings = SettingsStore(backing, backgroundScope)
        settings.update { it.copy(accent = it.accent + "-one") }
        settings.update { it.copy(accent = it.accent + "-two") }
        runCurrent()
        assertEquals(null, settings.settings.value)
        release.complete(Unit)
        settings.settings.filterNotNull().first()
        runCurrent()
        val restored = SettingsStore(memory, backgroundScope).settings.filterNotNull().first()
        assertEquals("Blue-one-two", restored.accent)
        assertEquals(ThemeMode.Dark, restored.theme)
        assertEquals(setOf("old-marker"), restored.uncertainGitInstalls)
    }

    @Test fun earlyUpdatesMergeWithStoredMarkerAndSurviveDurableWriteRace() = runTest {
        val memory = InMemoryKeyValueStore()
        memory.put("settings.v1", """{"theme":"Dark","uncertainGitInstalls":["old-marker"]}""")
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val backing = object : KeyValueStore by memory {
            override suspend fun get(key: String): String? {
                readStarted.complete(Unit)
                releaseRead.await()
                return memory.get(key)
            }
            override suspend fun putConfirmed(key: String, value: String): Boolean {
                writeStarted.complete(Unit)
                releaseWrite.await()
                memory.put(key, value)
                return true
            }
        }
        val settings = SettingsStore(backing, backgroundScope)
        readStarted.await()
        val first = async { settings.update { it.copy(accent = it.accent + "-first") } }
        val second = async { first.await(); settings.update { it.copy(accent = it.accent + "-second") } }
        second.await()
        assertEquals(null, settings.settings.value)
        releaseRead.complete(Unit)
        val loaded = settings.settings.filterNotNull().first()
        assertEquals("Blue-first-second", loaded.accent)
        assertEquals(ThemeMode.Dark, loaded.theme)
        assertEquals(setOf("old-marker"), loaded.uncertainGitInstalls)

        val durable = launch { settings.updateDurably { it.copy(uncertainGitInstalls = it.uncertainGitInstalls + "new-marker") } }
        writeStarted.await()
        settings.update { it.copy(pureBlack = true) }
        assertEquals(true, settings.settings.value!!.pureBlack)
        releaseWrite.complete(Unit)
        durable.join()
        runCurrent()
        val restored = SettingsStore(memory, backgroundScope).settings.filterNotNull().first()
        assertEquals(setOf("old-marker", "new-marker"), restored.uncertainGitInstalls)
        assertEquals("Blue-first-second", restored.accent)
        assertEquals(ThemeMode.Dark, restored.theme)
        assertEquals(true, restored.pureBlack)
    }

    @Test fun concurrentOrdinaryUpdateSurvivesMarkerRefresh() = runTest {
        val memory = InMemoryKeyValueStore()
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val backing = object : KeyValueStore by memory {
            override suspend fun putConfirmed(key: String, value: String): Boolean {
                started.complete(Unit)
                resume.await()
                memory.put(key, value)
                return true
            }
        }
        val settings = SettingsStore(backing, backgroundScope)
        runCurrent()
        val write = launch { settings.updateDurably { it.copy(uncertainGitInstalls = it.uncertainGitInstalls + "marker") } }
        started.await()
        settings.update { it.copy(accent = "Teal") }
        resume.complete(Unit)
        write.join()
        runCurrent()
        assertEquals("Teal", settings.settings.value!!.accent)
        assertTrue("marker" in settings.settings.value!!.uncertainGitInstalls)
        val restored = SettingsStore(memory, backgroundScope)
        runCurrent()
        assertEquals("Teal", restored.settings.value!!.accent)
        assertTrue("marker" in restored.settings.value!!.uncertainGitInstalls)
    }
}
