package dev.hermeskotlin.ui

import dev.hermeskotlin.ui.bots.BotsViewModel
import dev.hermeskotlin.ui.chat.ChatViewModel
import dev.hermeskotlin.ui.connect.ConnectViewModel
import dev.hermeskotlin.ui.di.sharedModules
import dev.hermeskotlin.ui.plugins.PluginsViewModel
import dev.hermeskotlin.ui.rooms.RoomsViewModel
import dev.hermeskotlin.ui.sessions.CapabilitiesViewModel
import dev.hermeskotlin.ui.sessions.InsightsViewModel
import dev.hermeskotlin.ui.sessions.ScheduledViewModel
import dev.hermeskotlin.ui.sessions.SessionsViewModel
import dev.hermeskotlin.ui.settings.SettingsViewModel
import dev.hermeskotlin.ui.signin.SignInViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull

/** The iOS app's dependency graph is complete: every screen's view model can be made, as at launch. */
@OptIn(ExperimentalCoroutinesApi::class)
class IosKoinGraphTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
    }

    @Test
    fun everyViewModelResolves() {
        // createdAtStart singletons (the attention tracker, the session watcher) are made here, as at launch.
        val koin = startKoin { modules(sharedModules + iosAppModule) }.koin
        assertNotNull(koin.get<AppViewModel>())
        assertNotNull(koin.get<ConnectViewModel>())
        assertNotNull(koin.get<SignInViewModel>())
        assertNotNull(koin.get<SessionsViewModel>())
        assertNotNull(koin.get<BotsViewModel>())
        assertNotNull(koin.get<RoomsViewModel>())
        assertNotNull(koin.get<ScheduledViewModel>())
        assertNotNull(koin.get<CapabilitiesViewModel>())
        assertNotNull(koin.get<PluginsViewModel>())
        assertNotNull(koin.get<InsightsViewModel>())
        assertNotNull(koin.get<ChatViewModel>())
        assertNotNull(koin.get<SettingsViewModel>())
    }
}
