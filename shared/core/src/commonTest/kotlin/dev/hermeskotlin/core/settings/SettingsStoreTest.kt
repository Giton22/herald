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
    fun unreadableStoredSettingsFallBackToDefaults() = runTest {
        val disk = InMemoryKeyValueStore().apply { put("settings.v1", """{"theme":"Neon"}""") }
        assertEquals(AppSettings(), SettingsStore(disk, backgroundScope).settings.filterNotNull().first())
    }
}
