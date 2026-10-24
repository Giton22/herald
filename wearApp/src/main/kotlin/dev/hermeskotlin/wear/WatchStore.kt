package dev.hermeskotlin.wear

import android.content.Context
import androidx.core.content.edit
import dev.hermeskotlin.wearlink.WatchScope
import dev.hermeskotlin.wearlink.WatchAccent
import dev.hermeskotlin.wearlink.WearJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The chat the watch talks in (null id: the next ask starts one), with its [model] and how full its context is
 * ([contextPercent]) as the last turn there told.
 */
data class WatchChatChoice(val id: String?, val title: String?, val model: String? = null, val contextPercent: Int? = null, val botProfile: String? = null, val scope: WatchScope? = null)

/** What the watch remembers between launches: its chat, whether replies are read aloud, and the last reply (for the tile). */
class WatchStore(context: Context) {

    private val prefs = context.getSharedPreferences("herald_watch", Context.MODE_PRIVATE)

    var phoneScope: WatchScope?
        get() = prefs.getString(PHONE_SCOPE, null)?.let { WearJson.decode<WatchScope>(it.encodeToByteArray()) }
        set(value) = prefs.edit { putString(PHONE_SCOPE, value?.let { WearJson.encode(it).decodeToString() }) }

    var phoneNode: String?
        get() = prefs.getString(PHONE_NODE, null)
        set(value) = prefs.edit { putString(PHONE_NODE, value) }

    private val savedScope = prefs.getString(CHAT_SCOPE, null)?.let { WearJson.decode<WatchScope>(it.encodeToByteArray()) }
    private val _accent = MutableStateFlow(prefs.getString(ACCENT, null)
        ?.let { WearJson.decode<WatchAccent>(it.encodeToByteArray()) })
    val accent: StateFlow<WatchAccent?> = _accent.asStateFlow()

    /**
     * Keeps the phone's accent; false when it's the one already kept. Locked: two status replies can land at once,
     * and the saved copy must end on the same one as [accent].
     */
    @Synchronized
    fun setAccent(value: WatchAccent): Boolean {
        if (_accent.value == value) return false
        _accent.value = value
        prefs.edit { putString(ACCENT, WearJson.encode(value).decodeToString()) }
        return true
    }

    private val _chat = MutableStateFlow(
        WatchChatChoice(
            prefs.getString(CHAT_ID, null),
            prefs.getString(CHAT_TITLE, null),
            prefs.getString(MODEL, null),
            prefs.getInt(CONTEXT, -1).takeIf { it >= 0 },
            prefs.getString(BOT_PROFILE, null),
            savedScope,
        ).let { if (prefs.contains(CHAT_SCOPE) && savedScope == null) WatchChatChoice(null, null) else it },
    )
    val chat: StateFlow<WatchChatChoice> = _chat.asStateFlow()

    private val _speak = MutableStateFlow(prefs.getBoolean(SPEAK, true))
    val speak: StateFlow<Boolean> = _speak.asStateFlow()

    /** Picked here: another chat forgets the last one's model and context. */
    fun setChat(id: String?, title: String?, botProfile: String? = null, scope: WatchScope? = null) =
        save(id, title, keepStats = id == _chat.value.id && botProfile == _chat.value.botProfile && scope == _chat.value.scope, botProfile = botProfile, scope = scope)

    /** Named in a reply: a new chat getting its id keeps what its turn told. */
    fun adoptChat(id: String, title: String?, scope: WatchScope? = null) {
        val now = _chat.value
        save(id, if (now.botProfile != null) now.title else title, keepStats = id == now.id || now.id == null, botProfile = now.botProfile, scope = scope ?: now.scope)
    }

    private fun save(id: String?, title: String?, keepStats: Boolean, botProfile: String?, scope: WatchScope?) {
        val now = _chat.value
        save(WatchChatChoice(id, title?.takeIf { it.isNotBlank() }, now.model.takeIf { keepStats }, now.contextPercent.takeIf { keepStats }, botProfile, scope))
    }

    fun setStats(model: String?, contextPercent: Int?) {
        val now = _chat.value
        save(now.copy(model = model ?: now.model, contextPercent = contextPercent ?: now.contextPercent))
    }

    private fun save(choice: WatchChatChoice) {
        if (choice == _chat.value) return
        _chat.value = choice
        prefs.edit {
            putString(CHAT_ID, choice.id)
            putString(CHAT_TITLE, choice.title)
            putString(MODEL, choice.model)
            putInt(CONTEXT, choice.contextPercent ?: -1)
            putString(BOT_PROFILE, choice.botProfile)
            putString(CHAT_SCOPE, choice.scope?.let { WearJson.encode(it).decodeToString() })
        }
    }

    private val _live = MutableStateFlow(prefs.getBoolean(LIVE, true))

    /** Talk live, in a voice call, when the gateway can; off, the watch listens and sends what it heard. */
    val live: StateFlow<Boolean> = _live.asStateFlow()

    fun setLive(on: Boolean) {
        _live.value = on
        prefs.edit { putBoolean(LIVE, on) }
    }

    fun setSpeak(on: Boolean) {
        _speak.value = on
        prefs.edit { putBoolean(SPEAK, on) }
    }

    /** Notifications were asked for once, for the rings. */
    var askedToRing: Boolean
        get() = prefs.getBoolean(ASKED_TO_RING, false)
        set(value) = prefs.edit { putBoolean(ASKED_TO_RING, value) }

    var lastReply: String?
        get() = prefs.getString(LAST_REPLY, null)
        set(value) = prefs.edit { putString(LAST_REPLY, value?.take(MAX_KEPT)) }

    private companion object {
        const val PHONE_SCOPE = "phone_scope"
        const val PHONE_NODE = "phone_node"
        const val CHAT_SCOPE = "chat_scope"
        const val ACCENT = "accent"
        const val CHAT_ID = "chat_id"
        const val BOT_PROFILE = "bot_profile"
        const val CHAT_TITLE = "chat_title"
        const val MODEL = "chat_model"
        const val CONTEXT = "chat_context"
        const val SPEAK = "speak"
        const val LIVE = "live"
        const val LAST_REPLY = "last_reply"
        const val ASKED_TO_RING = "asked_to_ring"
        const val MAX_KEPT = 400
    }
}
