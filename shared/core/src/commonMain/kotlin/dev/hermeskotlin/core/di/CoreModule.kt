package dev.hermeskotlin.core.di

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.cron.CronApi
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayProbe
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.models.ModelsApi
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.rpc.openGatewaySocket
import dev.hermeskotlin.core.sessions.SessionsApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.dsl.module

/** Platform bindings: a [dev.hermeskotlin.core.storage.KeyValueStore] implementation. */
expect val platformModule: Module

val coreModule = module {
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { PersistentCookiesStorage(get()) }
    single { createHttpClient(cookies = get<PersistentCookiesStorage>()) }
    single { GatewayProbe(get()) }
    single { AuthApi(get(), get()) }
    single { GatewayRepository(get()) }
    single { SessionsApi(get()) }
    single { CronApi(get()) }
    single { LastChatStore(get()) }
    single { SettingsStore(get(), get()) }
    single {
        val client = get<HttpClient>()
        GatewayConnection(
            auth = get(),
            openSocket = { url, ticket -> client.openGatewaySocket(url, ticket) },
            scope = get(),
        )
    }
    single { ChatHost(get(), get(), get()) }
    single { ModelsApi(get()) }
}
