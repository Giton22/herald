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

/** App-side preferences, kept on the device (not on the gateway). New fields need defaults. */
@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.System,
    /** Dark mode uses true black backgrounds (OLED). */
    val pureBlack: Boolean = false,
    val textSize: TextSize = TextSize.Default,
    val showReasoning: Boolean = true,
    val showToolActivity: Boolean = true,
    /** Notify when a turn finishes while the app is in the background. */
    val notifyReplies: Boolean = true,
    /** Notify when the agent waits on an approval or a question while the app is in the background. */
    val notifyRequests: Boolean = true,
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
