package dev.hermeskotlin.ui.di

import dev.hermeskotlin.core.di.coreModule
import dev.hermeskotlin.core.di.platformModule
import dev.hermeskotlin.ui.AppViewModel
import dev.hermeskotlin.ui.connect.ConnectViewModel
import dev.hermeskotlin.ui.sessions.SessionsViewModel
import dev.hermeskotlin.ui.signin.SignInViewModel
import dev.hermeskotlin.ui.transcript.TranscriptViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val uiModule = module {
    viewModelOf(::AppViewModel)
    viewModelOf(::ConnectViewModel)
    viewModelOf(::SignInViewModel)
    viewModelOf(::SessionsViewModel)
    viewModelOf(::TranscriptViewModel)
}

/** Every shared module, in dependency order. */
val sharedModules = listOf(platformModule, coreModule, uiModule)
