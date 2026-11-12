package dev.hermeskotlin.core.widget

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.storage.KeyValueStore
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Where the home-screen widgets keep their snapshots between refreshes (D7/D8, `docs/WIDGETS-SPEC.md`),
 * over the platform [KeyValueStore] (encrypted at rest on Android like every other credential-adjacent
 * value). Keys are strictly scoped as `widget.snapshot.v1.<widget>.<gateway>#<profile>#<board>` — the
 * SeenStore convention of appending the gateway URL and profile raw — so switching gateways, profiles or
 * boards can never show another scope's data. A value over [MAX_VALUE_CHARS] isn't written (one Keystore
 * cipher per value); the previous value stands.
 *
 * [KeyValueStore] can't enumerate keys, so [forgetGateway] and [forgetProfile] work off a per-gateway
 * registry (`widget.scopes.v1.<gateway>`, a JSON set of `"<profile>#<board>"` suffixes) that every save
 * records its scope in — the same information the SQLite-backed stores keep with a prefix query.
 */
class WidgetSnapshotStore(private val store: KeyValueStore) {

    suspend fun saveNow(snapshot: NowWidgetSnapshot) = save(snapshot, NowWidgetSnapshot.serializer(), "now", snapshot.gatewayUrl, snapshot.profile ?: "", "")

    suspend fun readNow(gatewayUrl: String, profile: String?): NowWidgetSnapshot? =
        read(NowWidgetSnapshot.serializer(), key("now", gatewayUrl, profile ?: "", ""))

    suspend fun removeNow(gatewayUrl: String, profile: String?) = store.remove(key("now", gatewayUrl, profile ?: "", ""))

    suspend fun saveChats(snapshot: ChatsWidgetSnapshot) = save(snapshot, ChatsWidgetSnapshot.serializer(), "chats", snapshot.gatewayUrl, snapshot.profile ?: "", "")

    suspend fun readChats(gatewayUrl: String, profile: String?): ChatsWidgetSnapshot? =
        read(ChatsWidgetSnapshot.serializer(), key("chats", gatewayUrl, profile ?: "", ""))

    suspend fun removeChats(gatewayUrl: String, profile: String?) = store.remove(key("chats", gatewayUrl, profile ?: "", ""))

    suspend fun saveKanban(snapshot: KanbanWidgetSnapshot) = save(snapshot, KanbanWidgetSnapshot.serializer(), "kanban", snapshot.gatewayUrl, "", snapshot.boardSlug)

    suspend fun readKanban(gatewayUrl: String, boardSlug: String): KanbanWidgetSnapshot? =
        read(KanbanWidgetSnapshot.serializer(), key("kanban", gatewayUrl, "", boardSlug))

    suspend fun removeKanban(gatewayUrl: String, boardSlug: String) = store.remove(key("kanban", gatewayUrl, "", boardSlug))

    suspend fun saveScheduled(snapshot: ScheduledWidgetSnapshot) =
        save(snapshot, ScheduledWidgetSnapshot.serializer(), "scheduled", snapshot.gatewayUrl, "", "")

    suspend fun readScheduled(gatewayUrl: String): ScheduledWidgetSnapshot? =
        read(ScheduledWidgetSnapshot.serializer(), key("scheduled", gatewayUrl, "", ""))

    suspend fun removeScheduled(gatewayUrl: String) = store.remove(key("scheduled", gatewayUrl, "", ""))

    /** Every widget key [gatewayUrl] ever wrote, gone: called where `OfflineCache.forgetGateway` is (signing out). */
    suspend fun forgetGateway(gatewayUrl: String) {
        for (suffix in scopes(gatewayUrl)) {
            for (widget in WIDGETS) store.remove(key(widget, gatewayUrl, suffix))
        }
        store.remove(scopesKey(gatewayUrl))
    }

    /** Every widget key [profile] on [gatewayUrl] wrote, gone: called where `OfflineCache.forgetProfile` is (profile deleted). */
    suspend fun forgetProfile(gatewayUrl: String, profile: String) {
        val suffixes = scopes(gatewayUrl)
        val prefix = "$profile#"
        for (suffix in suffixes.filter { it.startsWith(prefix) }) {
            // Profile-shaped scopes are Now and Chats; the board/profile-less widgets are untouched.
            for (widget in listOf("now", "chats")) store.remove(key(widget, gatewayUrl, suffix))
        }
        val rest = suffixes.filterNot { it.startsWith(prefix) }
        if (rest.isEmpty()) store.remove(scopesKey(gatewayUrl))
        else store.put(scopesKey(gatewayUrl), json.encodeToString(SetSerializer(String.serializer()), rest.toSet()))
    }

    private suspend fun <T> save(snapshot: T, serializer: KSerializer<T>, widget: String, gateway: String, profile: String, board: String) {
        val jsonText = json.encodeToString(serializer, snapshot)
        if (jsonText.length > MAX_VALUE_CHARS) return
        store.put(key(widget, gateway, profile, board), jsonText)
        val suffix = "$profile#$board"
        val known = scopes(gateway)
        if (suffix !in known) {
            store.put(scopesKey(gateway), json.encodeToString(SetSerializer(String.serializer()), known + suffix))
        }
    }

    private suspend fun <T> read(serializer: KSerializer<T>, key: String): T? =
        store.get(key)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    /** The registered `"<profile>#<board>"` suffixes for [gatewayUrl]; empty when nothing was ever saved. */
    private suspend fun scopes(gatewayUrl: String): Set<String> =
        store.get(scopesKey(gatewayUrl))
            ?.let { runCatching { json.decodeFromString(SetSerializer(String.serializer()), it) }.getOrNull() }
            ?: emptySet()

    private fun key(widget: String, gateway: String, profile: String, board: String) =
        "widget.snapshot.v1.$widget.$gateway#$profile#$board"

    /** [suffix] is `"<profile>#<board>"`; it rebuilds the key tail verbatim, so no re-escaping is possible. */
    private fun key(widget: String, gateway: String, suffix: String) =
        "widget.snapshot.v1.$widget.$gateway#$suffix"

    private fun scopesKey(gateway: String) = "widget.scopes.v1.$gateway"

    companion object {
        private val WIDGETS = listOf("now", "chats", "kanban", "scheduled")

        /** A serialized snapshot over this isn't written; the previous value stands. */
        const val MAX_VALUE_CHARS = 256_000

        /** The shared lenient decode: a failed decode returns null and the widget renders its empty state. */
        private val json = Json(HermesJson) { coerceInputValues = true }
    }
}
