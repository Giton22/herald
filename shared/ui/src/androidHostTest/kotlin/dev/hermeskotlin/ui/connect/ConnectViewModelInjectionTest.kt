package dev.hermeskotlin.ui.connect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Connect screen's pick must land in the Koin singleton the mTLS engine reads. If [ConnectViewModel]'s
 * `certs` parameter ever regains a default (`= ClientCertAliases()`), Kotlin generates a synthetic
 * `(..., int, DefaultConstructorMarker)` overload and a hand-built ViewModel (or a future Koin change)
 * could fall back to a ViewModel-local map the engine never sees — the probe then handshakes without
 * the picked certificate. This test fails while that default exists and passes once the parameter is
 * required.
 */
class ConnectViewModelInjectionTest {

    @Test fun certsHasNoDefaultSoKoinMustInjectTheSingleton() {
        val ctors = ConnectViewModel::class.java.declaredConstructors
        val synthetic = ctors.filter { c -> c.parameterTypes.any { it.name.contains("DefaultConstructorMarker") } }
        assertTrue(
            synthetic.isEmpty(),
            "ConnectViewModel must require ClientCertAliases (found synthetic overload among $ctors)",
        )
        assertEquals(1, ctors.size, "expected the single (probe, access, certs, gateways) constructor")
    }
}
