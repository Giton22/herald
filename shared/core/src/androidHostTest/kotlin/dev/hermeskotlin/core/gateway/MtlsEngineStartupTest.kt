package dev.hermeskotlin.core.gateway

import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlin.test.Test

/**
 * Cold start builds the gateway engine eagerly during startKoin, before the platform module's
 * createdAtStart setter publishes the application context. The engine must therefore not read
 * [MtlsAppContext] at construction time (it is only needed on the handshake thread, inside the
 * KeyChain lambdas). This test constructs the engine with [MtlsAppContext] uninitialized: the old
 * eager read threw UninitializedPropertyAccessException here, killing every cold start.
 */
class MtlsEngineStartupTest {

    @Test fun engineBuildDoesNotReadAppContext() {
        val engine = mtlsEngine(ClientCertAliases(), GatewayRepository(InMemoryKeyValueStore()))
        engine.close()
    }
}
