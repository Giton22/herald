package dev.hermeskotlin.core.gateway

import kotlin.test.Test
import kotlin.test.assertEquals

class GatewayShortlistTest {

    private val a = SavedGateway("https://a.example")
    private val b = SavedGateway("https://b.example")
    private val c = SavedGateway("https://c.example")
    private val d = SavedGateway("https://d.example")

    @Test
    fun oneGatewayHasNothingToSwitchTo() {
        assertEquals(emptyList(), GatewayList(listOf(a), currentUrl = a.url).shortlist(3))
    }

    @Test
    fun thePrimaryComesFirst() {
        assertEquals(listOf(c, a, b), GatewayList(listOf(a, b, c), currentUrl = a.url, primaryUrl = c.url).shortlist(3))
    }

    @Test
    fun theOneInUseIsShownWhenItIsntInTheFirstFew() {
        assertEquals(listOf(a, b, d), GatewayList(listOf(a, b, c, d), currentUrl = d.url, primaryUrl = a.url).shortlist(3))
        assertEquals(listOf(a, b, c), GatewayList(listOf(a, b, c, d), currentUrl = b.url, primaryUrl = a.url).shortlist(3))
    }
}
