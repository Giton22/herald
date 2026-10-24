package dev.hermeskotlin.core.media

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

// No link preview cards on iOS yet. A reply's links are fetched without a tap, so the cards need what Android has
// first: an HTML reader for the page's metadata, and a check of the addresses a name resolves to, so a public name
// can't reach the user's own networks. Until then no link is picked, and nothing is fetched.

actual fun replyWebLinks(text: String): List<String> = emptyList()

internal actual fun linkMetadata(bytes: ByteArray, url: String, charset: String?): LinkMetadata? = null

/** Never asked for a page while [replyWebLinks] picks none. */
internal actual fun publicWebEngine(): HttpClientEngine = Darwin.create()
