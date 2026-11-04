package dev.hermeskotlin.core.settings

import dev.hermeskotlin.core.bots.SidebarMode
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsStoreTest {

    @Test
    fun callTargetsSurviveLoadingAndSavingAlongsideOtherPreferences() = runTest {
        val disk = InMemoryKeyValueStore()
        disk.put("settings.v1", """{"theme":"Dark","callTargets":{"https://home.example":{"profile":"sample_bot","sessionId":"r1","label":"Sample · Planning"}}}""")
        val settings = SettingsStore(disk, backgroundScope)
        val restored = settings.settings.filterNotNull().first()
        assertEquals(ThemeMode.Dark, restored.theme)
        assertEquals(CallTarget(profile = "sample_bot", sessionId = "r1", label = "Sample · Planning"), restored.callTargets["https://home.example"])
        settings.update { it.copy(callTargets = it.callTargets + ("https://work.example" to CallTarget(profile = "helper", label = "Helper"))) }
        advanceUntilIdle()
        val saved = SettingsStore(disk, backgroundScope).settings.filterNotNull().first()
        assertEquals(restored.callTargets["https://home.example"], saved.callTargets["https://home.example"])
        assertEquals(CallTarget(profile = "helper", label = "Helper"), saved.callTargets["https://work.example"])
        assertEquals(ThemeMode.Dark, saved.theme)
    }

    @Test
    fun theLastProjectIsRememberedPerGatewayAndProfile() {
        val home = GatewayUrl.parse("https://hermes.example")
        val work = GatewayUrl.parse("http://100.64.0.2:9119")
        val settings = AppSettings()
            .withLastProject(home, null, "/srv/site")
            .withLastProject(home, "work", "/srv/work")
        assertEquals("/srv/site", settings.startProject(home, null))
        assertEquals("/srv/work", settings.startProject(home, "work"))
        assertNull(settings.startProject(work, null))
        // No project last time: none next time.
        assertNull(settings.withLastProject(home, null, null).startProject(home, null))
        assertEquals("/srv/work", settings.withLastProject(home, null, null).startProject(home, "work"))
        // Turned off, nothing is started in, though it's still kept for when it's back on.
        val off = settings.copy(useLastProject = false)
        assertNull(off.startProject(home, null))
        assertEquals("/srv/site", off.copy(useLastProject = true).startProject(home, null))
    }

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
    fun languageFollowsTheSystemUntilPickedAndBackAgain() = runTest {
        val disk = InMemoryKeyValueStore()
        // Settings saved before the option existed follow the system.
        disk.put("settings.v1", """{"theme":"Dark"}""")
        val first = SettingsStore(disk, backgroundScope)
        assertEquals(null, first.settings.filterNotNull().first().language)

        first.update { it.copy(language = "pt-BR") }
        advanceUntilIdle()
        val picked = SettingsStore(disk, backgroundScope)
        assertEquals("pt-BR", picked.settings.filterNotNull().first().language)

        // System default again: the tag goes, rather than staying as a stale choice.
        picked.update { it.copy(language = null) }
        advanceUntilIdle()
        assertEquals(null, SettingsStore(disk, backgroundScope).settings.filterNotNull().first().language)
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
    fun pinnedStaysOpenUntilFoldedAndRemembersIt() = runTest {
        val disk = InMemoryKeyValueStore()
        // Settings saved before the option existed show every pinned chat.
        disk.put("settings.v1", """{"theme":"Dark"}""")
        val first = SettingsStore(disk, backgroundScope)
        assertEquals(false, first.settings.filterNotNull().first().pinnedCollapsed)

        first.update { it.copy(pinnedCollapsed = true) }
        advanceUntilIdle()

        assertEquals(true, SettingsStore(disk, backgroundScope).settings.filterNotNull().first().pinnedCollapsed)
    }

    @Test
    fun threadsStayOpenUntilFoldedAndRemembersIt() = runTest {
        val disk = InMemoryKeyValueStore()
        // Settings saved before the option existed show every thread, whatever Pinned does.
        disk.put("settings.v1", """{"theme":"Dark","pinnedCollapsed":true}""")
        val first = SettingsStore(disk, backgroundScope)
        assertEquals(false, first.settings.filterNotNull().first().threadsCollapsed)

        first.update { it.copy(threadsCollapsed = true) }
        advanceUntilIdle()

        val again = SettingsStore(disk, backgroundScope).settings.filterNotNull().first()
        assertEquals(true, again.threadsCollapsed)
        assertEquals(true, again.pinnedCollapsed)
    }

    @Test
    fun projectsShowAsChipsUntilSwitchedToAListAndRemembersIt() = runTest {
        val disk = InMemoryKeyValueStore()
        // Settings saved before the option existed keep the chips.
        disk.put("settings.v1", """{"theme":"Dark"}""")
        val first = SettingsStore(disk, backgroundScope)
        assertEquals(false, first.settings.filterNotNull().first().projectsAsDropdown)

        first.update { it.copy(projectsAsDropdown = true) }
        advanceUntilIdle()

        assertEquals(true, SettingsStore(disk, backgroundScope).settings.filterNotNull().first().projectsAsDropdown)
    }

    @Test
    fun linkPreviewsRequireOptInAndRememberTheChoice() = runTest {
        assertEquals(false, AppSettings().showLinkPreviews)
        val disk = InMemoryKeyValueStore().apply { put("settings.v1", """{"theme":"Dark"}""") }
        val first = SettingsStore(disk, backgroundScope)
        assertEquals(false, first.settings.filterNotNull().first().showLinkPreviews)

        first.update { it.copy(showLinkPreviews = true) }
        advanceUntilIdle()
        val reopened = SettingsStore(disk, backgroundScope)
        assertEquals(true, reopened.settings.filterNotNull().first().showLinkPreviews)
        assertEquals(ThemeMode.Dark, reopened.settings.value?.theme)

        reopened.update { it.copy(showLinkPreviews = false) }
        advanceUntilIdle()
        assertEquals(false, SettingsStore(disk, backgroundScope).settings.filterNotNull().first().showLinkPreviews)
    }

    @Test
    fun removedVoiceDiagnosticsDoesNotResetOtherStoredSettings() = runTest {
        val disk = InMemoryKeyValueStore().apply {
            put("settings.v1", """{"theme":"Dark","subscriptionVoicePreview":true,"subscriptionVoiceDiagnostics":true}""")
        }
        val store = SettingsStore(disk, backgroundScope)
        val restored = store.settings.filterNotNull().first()
        assertEquals(ThemeMode.Dark, restored.theme)
        assertEquals(true, restored.subscriptionVoicePreview)
        store.update { it.copy(pureBlack = true) }
        runCurrent()
        val saved = disk.get("settings.v1")!!
        assertEquals(false, saved.contains("subscriptionVoiceDiagnostics"))
        assertEquals(true, SettingsStore(disk, backgroundScope).settings.filterNotNull().first().subscriptionVoicePreview)
    }

    @Test
    fun unreadableStoredSettingsFallBackToDefaults() = runTest {
        val disk = InMemoryKeyValueStore().apply { put("settings.v1", """{"theme":"Neon"}""") }
        assertEquals(AppSettings(), SettingsStore(disk, backgroundScope).settings.filterNotNull().first())
    }

    @Test
    fun theSidebarKeepsItsSwitchAndPagesUntilChangedAndRemembersTheChanges() = runTest {
        val disk = InMemoryKeyValueStore()
        // Settings saved before the options existed keep today's sidebar.
        disk.put("settings.v1", """{"theme":"Dark","projectsAsDropdown":true}""")
        val first = SettingsStore(disk, backgroundScope)
        val old = first.settings.filterNotNull().first()
        assertTrue(old.sidebarModeSwitch)
        assertEquals(SidebarMode.Chats, old.sidebarView)
        assertTrue(old.sidebarNav)
        assertEquals(emptyList(), old.sidebarNavHidden)
        assertTrue(old.projectsAsDropdown)

        first.update { it.copy(sidebarModeSwitch = false, sidebarView = SidebarMode.Bots, sidebarNav = false).withSidebarPage("Insights", shown = false) }
        advanceUntilIdle()

        val restored = SettingsStore(disk, backgroundScope).settings.filterNotNull().first()
        assertFalse(restored.sidebarModeSwitch)
        assertEquals(SidebarMode.Bots, restored.sidebarView)
        assertFalse(restored.sidebarNav)
        assertEquals(listOf("Insights"), restored.sidebarNavHidden)
        assertEquals(ThemeMode.Dark, restored.theme)
    }

    @Test
    fun withoutItsSwitchTheSidebarShowsThePickedView() {
        // With the switch, the side last picked shows, whatever view is set for without it.
        val switch = AppSettings(sidebarView = SidebarMode.Bots)
        assertEquals(SidebarMode.Chats, switch.sidebarMode(SidebarMode.Chats))
        assertEquals(SidebarMode.Bots, switch.sidebarMode(SidebarMode.Bots))
        val bots = switch.copy(sidebarModeSwitch = false)
        assertEquals(SidebarMode.Bots, bots.sidebarMode(SidebarMode.Chats))
        assertEquals(SidebarMode.Chats, bots.copy(sidebarView = SidebarMode.Chats).sidebarMode(SidebarMode.Bots))
    }

    @Test
    fun aSidebarPageHidesOnItsOwnOrWithTheWholeSection() {
        val all = AppSettings()
        assertTrue(all.showsSidebarPage("Scheduled"))
        val noInsights = all.withSidebarPage("Insights", shown = false)
        assertFalse(noInsights.showsSidebarPage("Insights"))
        assertTrue(noInsights.showsSidebarPage("Scheduled"))
        // Hidden twice is still hidden once, and shown again it's gone from the list.
        assertEquals(listOf("Insights"), noInsights.withSidebarPage("Insights", shown = false).sidebarNavHidden)
        assertEquals(emptyList(), noInsights.withSidebarPage("Insights", shown = true).sidebarNavHidden)
        // The whole section off hides every page, and keeps which ones were hidden for when it's back on.
        val off = noInsights.copy(sidebarNav = false)
        assertFalse(off.showsSidebarPage("Scheduled"))
        val on = off.copy(sidebarNav = true)
        assertTrue(on.showsSidebarPage("Scheduled"))
        assertFalse(on.showsSidebarPage("Insights"))
    }
}
