package dev.hermeskotlin.core.gateway

import dev.hermeskotlin.core.storage.KeyValueStore
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

// No picking from the Keychain's identities yet, so it presents no client certificate.
internal actual fun mtlsEngine(certs: ClientCertAliases, gateways: GatewayRepository, store: KeyValueStore): HttpClientEngine =
    Darwin.create()
