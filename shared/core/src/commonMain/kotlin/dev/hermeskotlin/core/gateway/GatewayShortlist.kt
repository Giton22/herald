package dev.hermeskotlin.core.gateway

/**
 * The gateways to offer where there's room for only [max] of them (the home-screen widget), primary first; none
 * with only one saved, as there's nothing to switch to. The one in use is among them even when it isn't in the
 * first few, in the last place.
 */
fun GatewayList.shortlist(max: Int): List<SavedGateway> {
    if (gateways.size < 2 || max <= 0) return emptyList()
    val first = ordered.take(max)
    val inUse = current ?: return first
    return if (first.any { it.url == inUse.url }) first else first.dropLast(1) + inUse
}
