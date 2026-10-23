package dev.hermeskotlin.ui

import dev.hermeskotlin.core.gateway.ClientCertAliases
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every way to build [AppViewModel] must take the Koin [ClientCertAliases] singleton: a pick that
 * lands in a ViewModel-local map never reaches the mTLS engine, so the probe handshakes without
 * the picked certificate. (Other trailing parameters legitimately keep defaults, so a synthetic
 * overload exists — the invariant is that no overload omits `certs`.)
 */
class AppViewModelInjectionTest {

    @Test fun everyConstructorRequiresTheSharedCertMap() {
        val ctors = AppViewModel::class.java.declaredConstructors
        assertTrue(ctors.isNotEmpty(), "no constructors found")
        ctors.forEach { ctor ->
            assertTrue(
                ctor.parameterTypes.any { it == ClientCertAliases::class.java },
                "constructor without ClientCertAliases: $ctor",
            )
        }
        val primary = ctors.singleOrNull { c -> c.parameterTypes.none { it.name.contains("DefaultConstructorMarker") } }
        assertTrue(primary != null, "no primary constructor among $ctors")
        assertEquals(
            ClientCertAliases::class.java,
            primary.parameterTypes.last(),
            "certs must be the last parameter of $primary",
        )
    }
}
