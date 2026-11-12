package dev.hermeskotlin.ui.di

import dev.hermeskotlin.core.di.coreModule
import dev.hermeskotlin.core.di.platformModule
import dev.hermeskotlin.core.voice.DeviceLock
import dev.hermeskotlin.ui.AppViewModel
import dev.hermeskotlin.ui.bots.BotsViewModel
import dev.hermeskotlin.ui.chat.ChatViewModel
import dev.hermeskotlin.ui.chat.ReplySpeakerSource
import dev.hermeskotlin.ui.connect.ConnectViewModel
import dev.hermeskotlin.ui.sessions.CapabilitiesViewModel
import dev.hermeskotlin.ui.sessions.SkillsHubViewModel
import dev.hermeskotlin.ui.sessions.ConfigViewModel
import dev.hermeskotlin.ui.plugins.PluginsViewModel
import dev.hermeskotlin.ui.sessions.InsightsViewModel
import dev.hermeskotlin.ui.inbox.InboxViewModel
import dev.hermeskotlin.core.inbox.InboxNotices
import dev.hermeskotlin.core.inbox.NoInboxNotices
import dev.hermeskotlin.ui.sessions.ScheduledViewModel
import dev.hermeskotlin.ui.rooms.RoomsViewModel
import dev.hermeskotlin.ui.screen.ScreenViewModel
import dev.hermeskotlin.ui.sessions.OtherChatsViewModel
import dev.hermeskotlin.ui.sessions.SessionsViewModel
import dev.hermeskotlin.ui.settings.PhoneToolsViewModel
import dev.hermeskotlin.ui.settings.SettingsViewModel
import dev.hermeskotlin.ui.signin.SignInViewModel
import dev.hermeskotlin.core.phone.NoWatchedPlaces
import dev.hermeskotlin.core.phone.WatchedPlaces
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

val uiModule = module {
    viewModelOf(::AppViewModel)
    viewModelOf(::ConnectViewModel)
    viewModelOf(::SignInViewModel)
    viewModelOf(::SessionsViewModel)
    // Handed the sidebar's list view model, whose list it reads.
    viewModel { params -> OtherChatsViewModel(params.get(), get(), get(), get()) }
    viewModelOf(::BotsViewModel)
    viewModelOf(::RoomsViewModel)
    viewModelOf(::ScheduledViewModel)
    // One screen at a time, so one view model: it is told which bot's screen to watch.
    viewModelOf(::ScreenViewModel)
    viewModel { InboxViewModel(get(), get(), get(), get(), get(), get(), getOrNull<InboxNotices>() ?: NoInboxNotices) }
    viewModelOf(::CapabilitiesViewModel)
    viewModelOf(::SkillsHubViewModel)
    viewModelOf(::ConfigViewModel)
    viewModelOf(::PluginsViewModel)
    viewModelOf(::InsightsViewModel)
    // Spelled out: past 22 parameters viewModelOf has no overload, and the last two are optional.
    viewModel {
        ChatViewModel(
            get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
            get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
            seen = getOrNull(),
            threads = getOrNull(),
            linkPreviews = get(),
            readAloudVoices = getOrNull(),
            // Only Android says whether the phone is locked.
            deviceLock = getOrNull() ?: DeviceLock.None,
            nextPromptApi = get(),
        )
    }
    // A chat in a window of its own (the desktop's Open in new window), with the host it's handed: the app's one
    // host holds only the main window's chat.
    viewModel(named(CHAT_WINDOW)) { params ->
        ChatViewModel(
            get(), params.get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
            get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
            seen = getOrNull(),
            threads = getOrNull(),
            linkPreviews = get(),
            readAloudVoices = getOrNull(),
            deviceLock = getOrNull() ?: DeviceLock.None,
            nextPromptApi = get(),
        )
    }
    viewModelOf(::SettingsViewModel)
    single { ReplySpeakerSource() }
    // Watched places are the platform's; one without them shows none.
    viewModel { PhoneToolsViewModel(get(), get(), getOrNull<WatchedPlaces>() ?: NoWatchedPlaces) }
}

/** The [ChatViewModel] of a chat in its own window, made with that window's `ChatHost` as its parameter. */
const val CHAT_WINDOW = "chat-window"

/** Every shared module, in dependency order. */
val sharedModules = listOf(platformModule, coreModule, uiModule)
