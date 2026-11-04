package dev.hermeskotlin.core.gateway

import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Code that speaks OkHttp itself (the chat's media player) starts from the gateway client's own OkHttp, so a
 * gateway that asks for a client certificate gets one there too.
 */
class GatewayOkHttpTest {

    @Test fun theGatewayClientHandsOutItsCertificateTls() {
        val client = createHttpClient(engine = mtlsEngine(ClientCertAliases(), GatewayRepository(InMemoryKeyValueStore())))
        try {
            val okHttp = assertNotNull(client.gatewayOkHttp())
            assertIs<SniCapturingSocketFactory>(okHttp.sslSocketFactory)
            // What the player builds from it keeps the certificate's TLS and the pool a certificate change empties.
            val derived = okHttp.newBuilder().followRedirects(false).build()
            assertSame(okHttp.sslSocketFactory, derived.sslSocketFactory)
            assertSame(okHttp.connectionPool, derived.connectionPool)
        } finally {
            client.close()
        }
    }

    @Test fun anotherClientHasNone() {
        val client = createHttpClient()
        try {
            assertNull(client.gatewayOkHttp())
        } finally {
            client.close()
        }
    }
}
