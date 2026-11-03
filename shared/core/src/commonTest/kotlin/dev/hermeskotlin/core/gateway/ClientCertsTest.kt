package dev.hermeskotlin.core.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClientCertsTest {

    private val home = SavedGateway("https://hermes.example.ts.net", clientCertAlias = "homelab-cert")

    @Test
    fun noPickAndNoSavedGatewayMeansNoCertificate() {
        assertNull(clientCertAliasFor("hermes.example.ts.net", ClientCertAliases(), emptyList()))
    }

    @Test
    fun aPendingPickAppliesBeforeTheGatewayIsSaved() {
        val pending = ClientCertAliases()
        pending.set("hermes.example.ts.net", "fresh-pick")

        assertEquals("fresh-pick", clientCertAliasFor("hermes.example.ts.net", pending, emptyList()))
    }

    @Test
    fun aSavedGatewaySuppliesItsCertificate() {
        assertEquals("homelab-cert", clientCertAliasFor("hermes.example.ts.net", ClientCertAliases(), listOf(home)))
    }

    @Test
    fun aPendingPickWinsOverTheSavedOne() {
        val pending = ClientCertAliases()
        pending.set("hermes.example.ts.net", "fresh-pick")

        assertEquals("fresh-pick", clientCertAliasFor("hermes.example.ts.net", pending, listOf(home)))
    }

    @Test
    fun forgettingAPendingPickFallsBackToTheSavedOne() {
        val pending = ClientCertAliases()
        pending.set("hermes.example.ts.net", "fresh-pick")
        pending.set("hermes.example.ts.net", null)

        assertEquals("homelab-cert", clientCertAliasFor("hermes.example.ts.net", pending, listOf(home)))
    }

    @Test
    fun hostsMatchCaseInsensitively() {
        val pending = ClientCertAliases()
        pending.set("HERMES.EXAMPLE.TS.NET", "fresh-pick")

        assertEquals("fresh-pick", clientCertAliasFor("hermes.example.ts.net", pending, emptyList()))
        assertEquals("homelab-cert", clientCertAliasFor("HERMES.EXAMPLE.TS.NET", ClientCertAliases(), listOf(home)))
    }

    @Test
    fun otherHostsGetNothing() {
        val pending = ClientCertAliases()
        pending.set("hermes.example.ts.net", "fresh-pick")

        assertNull(clientCertAliasFor("other.example.net", pending, listOf(home)))
    }

    @Test
    fun aGatewayWithoutACertificateDoesNotHideOneOnTheSameHost() {
        val bare = SavedGateway("https://hermes.example.ts.net:9119")
        assertEquals("homelab-cert", clientCertAliasFor("hermes.example.ts.net", ClientCertAliases(), listOf(bare, home)))
    }

    @Test
    fun everyChangeIsReportedWithItsNormalizedHost() {
        val pending = ClientCertAliases()
        val seen = mutableListOf<String>()
        pending.onChanged { seen += it }

        pending.set("HERMES.example.ts.net", "fresh-pick")
        pending.set("hermes.example.ts.net", null)
        pending.changed("Other.Example.net")

        assertEquals(listOf("hermes.example.ts.net", "hermes.example.ts.net", "other.example.net"), seen)
    }

    @Test
    fun clearingForgetsEveryPickAndReportsEachHost() {
        val pending = ClientCertAliases()
        pending.set("a.example.net", "a-cert")
        pending.set("b.example.net", "b-cert")
        val seen = mutableListOf<String>()
        pending.onChanged { seen += it }

        pending.clear()

        assertNull(pending.get("a.example.net"))
        assertNull(pending.get("b.example.net"))
        assertEquals(setOf("a.example.net", "b.example.net"), seen.toSet())
    }
}
