package dev.hermeskotlin.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hermeskotlin.core.phone.NoWatchedPlaces
import dev.hermeskotlin.core.phone.PhoneTool
import dev.hermeskotlin.core.phone.WatchedPlace
import dev.hermeskotlin.core.phone.WatchedPlaces
import dev.hermeskotlin.core.phone.PhoneToolUse
import dev.hermeskotlin.core.phone.PhoneToolsBridge
import dev.hermeskotlin.core.phone.PhoneToolsStatus
import dev.hermeskotlin.core.push.KnownPhone
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn

/** Settings → Phone tools: the switch for all of them, one per tool, and what the agent used lately. */
class PhoneToolsViewModel(
    private val store: SettingsStore,
    private val bridge: PhoneToolsBridge,
    private val watched: WatchedPlaces = NoWatchedPlaces,
) : ViewModel() {

    /** The places the phone watches for the agent. */
    val places: StateFlow<List<WatchedPlace>> = watched.places

    fun removePlace(id: String) = watched.remove(id)

    fun refreshPlaces() = watched.refresh()

    val settings: StateFlow<AppSettings> =
        store.settings.filterNotNull().stateIn(viewModelScope, SharingStarted.Eagerly, store.settings.value ?: AppSettings())
    val status: StateFlow<PhoneToolsStatus> = bridge.status
    val recent: StateFlow<List<PhoneToolUse>> = bridge.recent

    /** Turning it on sets the gateway up too (installs the plugin there when it's missing). */
    fun setAll(on: Boolean) {
        store.update { it.copy(phoneTools = on) }
        if (on) bridge.setUp()
    }

    fun setTool(tool: PhoneTool, on: Boolean) = store.update { s ->
        s.copy(phoneToolsOn = if (on) (s.phoneToolsOn + tool.id).distinct() else s.phoneToolsOn - tool.id)
    }

    fun setRings(on: Boolean) = store.update { it.copy(phoneRings = on) }

    fun setRingsTalk(on: Boolean) = store.update { it.copy(phoneRingsTalk = on) }

    fun setWatchRings(on: Boolean) = store.update { it.copy(watchRings = on) }

    fun setQuietHours(on: Boolean) = store.update { it.copy(quietHours = on) }

    fun setQuietFrom(minuteOfDay: Int) = store.update { it.copy(quietFrom = minuteOfDay) }

    fun setQuietUntil(minuteOfDay: Int) = store.update { it.copy(quietUntil = minuteOfDay) }

    fun setUp() = bridge.setUp()

    /** The user asked for the newer plugin the page offers. */
    fun updatePlugin() = bridge.updatePlugin()

    /** The phones the gateway knows, in the order calls no phone started try them. */
    val phones: StateFlow<List<KnownPhone>> = bridge.phones

    fun refreshPhones() = bridge.refreshPhones()

    fun movePhone(id: String, by: Int) = bridge.movePhone(id, by)

    fun removePhone(id: String) = bridge.removePhone(id)
}
