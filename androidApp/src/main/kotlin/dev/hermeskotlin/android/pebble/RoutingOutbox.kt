package dev.hermeskotlin.android.pebble

import dev.hermeskotlin.core.wear.WatchOutbox

/** One [WatchOutbox] for every watch: a Pebble's node goes to [pebble], any other to Wear OS's Data Layer ([wear]). */
class RoutingOutbox(private val wear: WatchOutbox, private val pebble: PebbleOutbox) : WatchOutbox {

    override suspend fun send(node: String, path: String, data: ByteArray): Boolean =
        outboxFor(node).send(node, path, data)

    override suspend fun stream(node: String, path: String, data: ByteArray): Boolean =
        outboxFor(node).stream(node, path, data)

    private fun outboxFor(node: String): WatchOutbox = if (PebbleOutbox.isPebble(node)) pebble else wear
}
