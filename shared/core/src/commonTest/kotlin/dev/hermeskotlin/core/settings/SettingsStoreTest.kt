package dev.hermeskotlin.core.settings

import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsStoreTest {

    @Test
    fun startsWithDefaultsAndRemembersChanges() = runTest {
        val disk = InMemoryKeyValueStore()
        val first = SettingsStore(disk, backgroundScope)
        assertEquals(AppSettings(), first.settings.filterNotNull().first())

        first.update { it.copy(theme = ThemeMode.Dark, pureBlack = true, textSize = TextSize.Large) }
        advanceUntilIdle()

        val reopened = SettingsStore(disk, backgroundScope)
        val restored = reopened.settings.filterNotNull().first()
        assertEquals(ThemeMode.Dark, restored.theme)
        assertEquals(true, restored.pureBlack)
        assertEquals(TextSize.Large, restored.textSize)
    }

    @Test
    fun dictationIsOnUnlessTurnedOff() = runTest {
        val disk = InMemoryKeyValueStore()
        // Settings saved before the option existed keep the mic.
        disk.put("settings.v1", """{"dictationEngine":"Gateway"}""")
        val first = SettingsStore(disk, backgroundScope)
        assertEquals(true, first.settings.filterNotNull().first().dictation)

        first.update { it.copy(dictation = false) }
        advanceUntilIdle()

        val restored = SettingsStore(disk, backgroundScope).settings.filterNotNull().first()
        assertEquals(false, restored.dictation)
        assertEquals(DictationEngine.Gateway, restored.dictationEngine)
    }

    @Test
    fun glowIsOnUnlessTurnedOff() = runTest {
        val disk = InMemoryKeyValueStore()
        // Settings saved before the option existed keep the glow.
        disk.put("settings.v1", """{"theme":"Dark"}""")
        val first = SettingsStore(disk, backgroundScope)
        assertEquals(true, first.settings.filterNotNull().first().glow)

        first.update { it.copy(glow = false) }
        advanceUntilIdle()

        assertEquals(false, SettingsStore(disk, backgroundScope).settings.filterNotNull().first().glow)
    }

    @Test
    fun sendWhileRunningSteersUnlessChangedAndRemembersTheChoice() = runTest {
        val disk = InMemoryKeyValueStore()
        // Settings saved before the option existed still read, with Steer.
        disk.put("settings.v1", """{"theme":"Dark"}""")
        val first = SettingsStore(disk, backgroundScope)
        assertEquals(RunningSend.Steer, first.settings.filterNotNull().first().runningSend)
        assertEquals(RunningSend.Steer, AppSettings().runningSend)

        first.update { it.copy(runningSend = RunningSend.StopAndSend) }
        advanceUntilIdle()

        assertEquals(RunningSend.StopAndSend, SettingsStore(disk, backgroundScope).settings.filterNotNull().first().runningSend)
    }

    @Test
    fun theAccentIsRememberedAndAnUnknownOneKeepsTheOtherSettings() = runTest {
        val disk = InMemoryKeyValueStore()
        val first = SettingsStore(disk, backgroundScope)
        assertEquals(DEFAULT_ACCENT, first.settings.filterNotNull().first().accent)
        first.update { it.copy(accent = "Teal") }
        advanceUntilIdle()
        assertEquals("Teal", SettingsStore(disk, backgroundScope).settings.filterNotNull().first().accent)

        // A preset from a newer version is only a name here; the rest still reads.
        disk.put("settings.v1", """{"theme":"Dark","accent":"Neon"}""")
        val restored = SettingsStore(disk, backgroundScope).settings.filterNotNull().first()
        assertEquals(ThemeMode.Dark, restored.theme)
        assertEquals("Neon", restored.accent)
    }

    @Test
    fun unreadableStoredSettingsFallBackToDefaults() = runTest {
        val disk = InMemoryKeyValueStore().apply { put("settings.v1", """{"theme":"Neon"}""") }
        assertEquals(AppSettings(), SettingsStore(disk, backgroundScope).settings.filterNotNull().first())
    }
}
