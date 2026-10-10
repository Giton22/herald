package dev.hermeskotlin.ui.platform

import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.platform.toNSData
import dev.hermeskotlin.core.sessions.RecentChats
import dev.hermeskotlin.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import platform.Foundation.NSFileManager
import platform.Foundation.writeToURL

/**
 * Feeds the Home Screen and Lock Screen widgets (the HeraldWidgets extension): the newest chats as the chat
 * list last read them, written to the App Group for the extension to read. With App lock on the widget shows
 * no chats, and signing out empties it.
 */
internal class IosWidgets(
    recentChats: RecentChats,
    settings: SettingsStore,
    connection: GatewayConnection,
    scope: CoroutineScope,
) {
    private data class Shown(val locked: Boolean, val signedIn: Boolean, val chats: List<Chat>)

    private data class Chat(val id: String, val title: String, val at: Double?)

    init {
        val signedIn = connection.state.map { it !is ConnectionState.Idle && it !is ConnectionState.SessionExpired }.distinctUntilChanged()
        val locked = settings.settings.map { it?.appLock == true }.distinctUntilChanged()
        scope.launch {
            combine(recentChats.latest, locked, signedIn) { latest, locked, signedIn ->
                val sessions = if (locked || !signedIn) emptyList() else latest?.sessions.orEmpty()
                Shown(locked, signedIn, sessions.map { Chat(it.id, it.displayTitle, it.activityAt) })
            }.distinctUntilChanged().collect { shown ->
                withContext(Dispatchers.IO) { write(json(shown)) }
                withContext(Dispatchers.Main) { reload?.invoke() }
            }
        }
    }

    private fun json(shown: Shown): String = JsonObject(
        mapOf(
            "locked" to JsonPrimitive(shown.locked),
            "signedIn" to JsonPrimitive(shown.signedIn),
            "chats" to JsonArray(
                shown.chats.map { chat ->
                    JsonObject(
                        buildMap {
                            put("id", JsonPrimitive(chat.id))
                            put("title", JsonPrimitive(chat.title))
                            chat.at?.let { put("at", JsonPrimitive(it)) }
                        },
                    )
                },
            ),
        ),
    ).toString()

    private fun write(json: String) {
        val folder = NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(IosLinks.APP_GROUP) ?: return
        val file = folder.URLByAppendingPathComponent(FILE) ?: return
        json.encodeToByteArray().toNSData().writeToURL(file, atomically = true)
    }

    companion object {
        /** What the widget extension reads; WidgetData.swift reads the same name. */
        const val FILE = "widget.json"

        /** Set by the Swift host at launch: WidgetKit's reload, which only Swift can call. */
        var reload: (() -> Unit)? = null
    }
}

/** Called by the Swift host at launch with WidgetKit's reload of Herald's widgets. */
fun setWidgetReloader(reload: () -> Unit) {
    IosWidgets.reload = reload
}
