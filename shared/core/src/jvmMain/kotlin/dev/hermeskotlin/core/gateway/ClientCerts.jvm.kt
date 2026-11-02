package dev.hermeskotlin.core.gateway

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

// The desktop has no system certificate store to pick from yet, so it presents no client certificate.
internal actual fun mtlsEngine(certs: ClientCertAliases, gateways: GatewayRepository): HttpClientEngine = OkHttp.create()
