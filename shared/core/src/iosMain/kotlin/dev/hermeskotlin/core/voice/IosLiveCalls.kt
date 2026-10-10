package dev.hermeskotlin.core.voice

/**
 * How live calls are made on iOS: WebRTC comes from the Swift host (a Swift package), which hands its maker to
 * the shared UI at launch, and the UI sets it here. Null until then: voice chats use the chained mode.
 */
object IosLiveCalls {
    var make: (() -> LiveCall?)? = null
}
