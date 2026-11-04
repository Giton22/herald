package dev.hermeskotlin.core.chat

/** Checks required to authorize an incoming request. */
data class CallRequestChecks(
    val connectionGatewayMatches: Boolean,
    val originGatewayMatches: Boolean,
    val profileMatches: Boolean,
    val connected: Boolean,
    val storedMatches: Boolean,
    val runtimeMatches: Boolean,
    val requestExactlyOnce: Boolean,
) {
    val valid: Boolean get() = connectionGatewayMatches && originGatewayMatches && profileMatches && connected &&
        storedMatches && runtimeMatches && requestExactlyOnce
}
