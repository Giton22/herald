package dev.hermeskotlin.core.settings

import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.storage.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

enum class ThemeMode { System, Light, Dark }

/** How much bigger or smaller than the system font size the app draws text. */
enum class TextSize(val scale: Float) {
    Small(0.9f),
    Default(1f),
    Large(1.15f),
    Largest(1.3f),
}

/**
 * How long a voice chat waits after you stop talking before it sends. Desktop's chat uses 1.25 s
 * and the terminal 3 s; on a phone people pause mid-thought, so the default sits between.
 */
enum class VoicePause(val millis: Long) {
    Short(1_250),
    Normal(2_000),
    Long(3_000),
}

/** App-side preferences, kept on the device (not on the gateway). New fields need defaults. */
@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.System,
    /** Dark mode uses true black backgrounds (OLED). */
    val pureBlack: Boolean = false,
    val textSize: TextSize = TextSize.Default,
    val showReasoning: Boolean = true,
    val showToolActivity: Boolean = true,
    /** Tokens each reply took, under it. */
    val showUsage: Boolean = true,
    /** Show the profile's pet (when the gateway has one on) above the composer. */
    val showPet: Boolean = true,
    val voicePause: VoicePause = VoicePause.Normal,
    /** Notify when a turn finishes while the app is in the background. */
    val notifyReplies: Boolean = true,
    /** Notify when the agent waits on an approval or a question while the app is in the background. */
    val notifyRequests: Boolean = true,
    /** Keep the gateway connection up in the background, so turns started on other devices notify too. */
    val stayConnected: Boolean = false,
    /** Bot messages reach the phone off the gateway's network too, end-to-end encrypted through ntfy (herald-push). */
    val pushAnywhere: Boolean = false,
    /** Ask GitHub, where the app is published, whether a newer release is out. */
    val checkForUpdates: Boolean = true,
)

/**
 * Holds [AppSettings]: null until the stored copy is read, so the first frame can wait for the
 * right theme instead of flashing the wrong one. Changes apply at once and are saved behind.
 */
class SettingsStore(private val store: KeyValueStore, private val scope: CoroutineScope) {

    private val _settings = MutableStateFlow<AppSettings?>(null)
    val settings: StateFlow<AppSettings?> = _settings.asStateFlow()

    private val writes = Mutex()

    init {
        scope.launch {
            val stored = store.get(KEY)?.let { runCatching { HermesJson.decodeFromString(AppSettings.serializer(), it) }.getOrNull() }
            // An update made before the read finished wins over the stored copy.
            _settings.compareAndSet(null, stored ?: AppSettings())
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = _settings.updateAndGet { transform(it ?: AppSettings()) } ?: return
        scope.launch {
            writes.withLock {
                // Only the newest value matters if several writes queue up.
                if (_settings.value == next) store.put(KEY, HermesJson.encodeToString(AppSettings.serializer(), next))
            }
        }
    }

    private companion object {
        const val KEY = "settings.v1"
    }
}
