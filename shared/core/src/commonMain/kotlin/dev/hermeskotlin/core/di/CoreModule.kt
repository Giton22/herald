package dev.hermeskotlin.core.di

import dev.hermeskotlin.core.auth.AuthApi
import dev.hermeskotlin.core.auth.PersistentCookiesStorage
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.LastChatStore
import dev.hermeskotlin.core.chat.ToolRiskStore
import dev.hermeskotlin.core.capabilities.CapabilitiesApi
import dev.hermeskotlin.core.cron.CronApi
import dev.hermeskotlin.core.insights.InsightsApi
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.ConnectionCheck
import dev.hermeskotlin.core.gateway.GatewayProbe
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.media.MediaApi
import dev.hermeskotlin.core.models.ModelsApi
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.profiles.ProfilesApi
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.rpc.openGatewaySocket
import dev.hermeskotlin.core.sessions.SessionsApi
import dev.hermeskotlin.core.slash.SlashApi
import dev.hermeskotlin.core.update.UpdateChecker
import dev.hermeskotlin.core.journey.JourneyApi
import dev.hermeskotlin.core.pet.PetApi
import dev.hermeskotlin.core.voice.AudioApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

private const val OUTSIDE_CLIENT = "outside"

/** Platform bindings: a [dev.hermeskotlin.core.storage.KeyValueStore] implementation. */
expect val platformModule: Module

val coreModule = module {
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { PersistentCookiesStorage(get()) }
    single { createHttpClient(cookies = get<PersistentCookiesStorage>()) }
    single { GatewayProbe(get()) }
    single {
        val client = get<HttpClient>()
        ConnectionCheck(get(), get(), openSocket = { url, ticket -> client.openGatewaySocket(url, ticket) })
    }
    single { AuthApi(get(), get()) }
    single { GatewayRepository(get()) }
    single { SessionsApi(get()) }
    single { CronApi(get()) }
    single { CapabilitiesApi(get()) }
    single { InsightsApi(get()) }
    single { LastChatStore(get()) }
    single { ProfilesApi(get()) }
    single { ProfileStore(get()) }
    single { SettingsStore(get(), get()) }
    single {
        val client = get<HttpClient>()
        GatewayConnection(
            auth = get(),
            openSocket = { url, ticket -> client.openGatewaySocket(url, ticket) },
            scope = get(),
        )
    }
    single { ToolRiskStore(get()) }
    single { ChatHost(get(), get(), get(), get()) }
    single { ModelsApi(get()) }
    single { SlashApi(get()) }
    single { PetApi(get()) }
    single { JourneyApi(get()) }
    single { AudioApi(get()) }
    // Outside requests (pictures from the web, GitHub) get a client without the gateway's cookies.
    single(named(OUTSIDE_CLIENT)) { createHttpClient() }
    single { MediaApi(get(), get(named(OUTSIDE_CLIENT))) }
    single { UpdateChecker(get(named(OUTSIDE_CLIENT)), get()) }
}
