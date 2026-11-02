package dev.hermeskotlin.core.gateway

import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import kotlin.test.Test

/**
 * Cold start builds the gateway engine eagerly during startKoin, before the platform module's
 * createdAtStart setter publishes the application context. The engine must therefore not require
 * [MtlsAppContext.context] at construction time (it is only read on the handshake thread, inside the
 * KeyChain lambdas, where a null context means no certificate). This test builds the engine with a
 * null context: the old eager read crashed here, killing every cold start.
 */
class MtlsEngineStartupTest {

    @Test fun engineBuildDoesNotReadAppContext() {
        // Order-independent: earlier tests (or Koin) may have published a context; the engine
        // must build with none, since cold start builds it before the platform module does.
        val previous = MtlsAppContext.context
        MtlsAppContext.context = null
        try {
            val engine = mtlsEngine(ClientCertAliases(), GatewayRepository(InMemoryKeyValueStore()))
            engine.close()
        } finally {
            MtlsAppContext.context = previous
        }
    }
}
