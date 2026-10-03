package dev.hermeskotlin.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

val HermesJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * Shared Ktor client. [engine] is injectable for tests; otherwise the platform engine (OkHttp on Android)
 * is used. With [cookies], the dashboard session cookies are sent on every HTTP call and WS upgrade.
 */
fun createHttpClient(engine: HttpClientEngine? = null, cookies: CookiesStorage? = null): HttpClient {
    val config: HttpClientConfig<*>.() -> Unit = {
        expectSuccess = false
        // The dashboard answers some calls (logout, unauthenticated HTML) with redirects to /login.
        followRedirects = false
        install(ContentNegotiation) { json(HermesJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
        }
        install(WebSockets)
        if (cookies != null) install(HttpCookies) { storage = cookies }
    }
    return if (engine != null) HttpClient(engine, config) else HttpClient(config)
}
