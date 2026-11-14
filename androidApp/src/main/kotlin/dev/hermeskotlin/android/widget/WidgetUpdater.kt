package dev.hermeskotlin.android.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.core.content.edit
import dev.hermeskotlin.android.R
import dev.hermeskotlin.android.notify.BotNotifier
import dev.hermeskotlin.core.chat.AttentionTracker
import dev.hermeskotlin.core.common.runCatchingCancellable
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.settings.AppLockTimer
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.widget.WidgetSnapshotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Runs the widgets while the app process is alive (docs/WIDGETS-SPEC.md D26): subscribes to the
 * in-app state that changes what a widget shows — the socket's state and events, the attention the
 * user's chats wait on, settings (accent, App lock, the pinned board), the gateway list and the bot
 * roster — coalesces the burst into one pass per [DEBOUNCE_MS] window, refreshes the snapshots
 * through [WidgetRefresher], then renders and pushes every widget instance with `AppWidgetManager`.
 * The providers' manual-refresh broadcasts and the system's `ACTION_APPWIDGET_UPDATE` land here too.
 */
@OptIn(FlowPreview::class)
class WidgetUpdater(
    private val context: Context,
    private val refresher: WidgetRefresher,
    private val settings: SettingsStore,
    private val gateways: GatewayRepository,
    private val connection: GatewayConnection,
    private val attention: AttentionTracker,
    private val bots: BotNotifier,
    private val appLock: AppLockTimer,
    /** The widgets' snapshots, removed when their last instance goes (D23). */
    private val store: WidgetSnapshotStore? = null,
    /** The app's scope: refreshes outlive any one trigger. */
    val scope: CoroutineScope,
) {

    private val kick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Volatile
    var started = false
        private set

    /** Idempotent: HermesApplication calls it at startup, tests may call it per case. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            // Any of these changing means at least one widget's pixels would.
            merge(
                connection.state.asSignal(),
                connection.events.asSignal(),
                attention.waiting.asSignal(),
                settings.settings.filterNotNull().asSignal(),
                gateways.list.asSignal(),
                bots.rosterChanges.asSignal(),
            ).collect { kick.tryEmit(Unit) }
        }
        scope.launch {
            kick.debounce(DEBOUNCE_MS).collectLatest {
                runCatchingCancellable { refreshAndRender() }
            }
        }
    }

    /** A provider's update or manual refresh: worth a (coalesced) pass. */
    fun kick() {
        kick.tryEmit(Unit)
    }

    /** One refresh of every widget. Provider broadcasts force the REST-only path; in-process signals may use live state. */
    suspend fun refreshAndRender(cold: Boolean = false) {
        val locked = isWidgetLocked(settings, appLock)
        val target = currentGateway()
        val outcome = refresher.refresh(
            profile = null,
            connected = !cold && connection.state.value is ConnectionState.Connected,
            savedNotice = ::savedNotice,
        )
        // A slow refresh can outlive the gateway it read: never push another scope's data, and a
        // cancelled refresh must not touch the widgets at all (M4).
        currentCoroutineContext().ensureActive()
        if (currentGateway() != target) return
        render(outcome, locked)
    }

    /** The gateway a pass would read: load the saved list before choosing the current pick or primary. */
    private suspend fun currentGateway(): String? =
        gateways.all().let { it.current ?: it.primary }?.gatewayUrl?.value

    /** Renders one refresh outcome to every instance of the four providers. */
    fun render(outcome: WidgetRefresher.Outcome, locked: Boolean) {
        val manager = AppWidgetManager.getInstance(context)
        val colors = WidgetColors(context, settings.settings.value?.accent)

        fun ids(provider: Class<*>): IntArray =
            manager.getAppWidgetIds(ComponentName(context, provider))

        val nowIds = ids(NowWidgetProvider::class.java)
        for (id in nowIds) manager.updateAppWidget(id, WidgetViews.renderNow(context, outcome.now, locked, outcome.nowCold, colors, bots, id))
        rememberScopes(nowIds, KIND_NOW, outcome)

        val chatsIds = ids(ChatsWidgetProvider::class.java)
        for (id in chatsIds) manager.updateAppWidget(id, WidgetViews.renderChats(context, outcome.chats, locked, colors, outcome.gatewayLabel, outcome.profile, id))
        if (!locked) manager.notifyAppWidgetViewDataChanged(chatsIds, R.id.list)
        rememberScopes(chatsIds, KIND_CHATS, outcome)

        val kanbanIds = ids(KanbanWidgetProvider::class.java)
        for (id in kanbanIds) manager.updateAppWidget(id, WidgetViews.renderKanban(context, outcome.kanban, locked, colors, id))
        if (!locked) manager.notifyAppWidgetViewDataChanged(kanbanIds, R.id.list)
        rememberScopes(kanbanIds, KIND_KANBAN, outcome)

        val scheduledIds = ids(ScheduledWidgetProvider::class.java)
        for (id in scheduledIds) manager.updateAppWidget(id, WidgetViews.renderScheduled(context, outcome.scheduled, locked, colors, id))
        if (!locked) manager.notifyAppWidgetViewDataChanged(scheduledIds, R.id.list)
        rememberScopes(scheduledIds, KIND_SCHEDULED, outcome)
    }

    /**
     * D23's deletion hook: a removed instance's scope goes with it. Snapshots are shared per
     * (gateway, profile, board), so they are removed only when the widget's last instance is gone —
     * the remaining instances would otherwise re-read an empty store.
     */
    fun onDeleted(appWidgetId: Int) {
        val remembered = instancePrefs.getString(key(appWidgetId), null) ?: return
        scope.launch {
            val manager = AppWidgetManager.getInstance(context)
            val parts = remembered.split('|')
            val remaining = when (parts[0]) {
                KIND_NOW -> manager.getAppWidgetIds(ComponentName(context, NowWidgetProvider::class.java)).isEmpty()
                KIND_CHATS -> manager.getAppWidgetIds(ComponentName(context, ChatsWidgetProvider::class.java)).isEmpty()
                KIND_KANBAN -> manager.getAppWidgetIds(ComponentName(context, KanbanWidgetProvider::class.java)).isEmpty()
                else -> manager.getAppWidgetIds(ComponentName(context, ScheduledWidgetProvider::class.java)).isEmpty()
            }
            if (remaining) store?.let { removeScope(it, parts) }
            instancePrefs.edit { remove(key(appWidgetId)) }
        }
    }

    private suspend fun removeScope(store: WidgetSnapshotStore, parts: List<String>) {
        val gateway = parts[1]
        when (parts[0]) {
            KIND_NOW -> store.removeNow(gateway, parts[2].ifEmpty { null })
            KIND_CHATS -> store.removeChats(gateway, parts[2].ifEmpty { null })
            KIND_KANBAN -> store.removeKanban(gateway, parts[3])
            KIND_SCHEDULED -> store.removeScheduled(gateway)
        }
    }

    /** Remembers what one instance showed, so [onDeleted] can remove exactly that scope (D23). */
    private fun rememberScopes(ids: IntArray, kind: String, outcome: WidgetRefresher.Outcome) {
        if (ids.isEmpty() || store == null) return
        val snapshot = when (kind) {
            KIND_NOW -> (outcome.now as? WidgetViews.UiState.Content)?.snapshot
            KIND_CHATS -> (outcome.chats as? WidgetViews.UiState.Content)?.snapshot
            KIND_KANBAN -> (outcome.kanban as? WidgetViews.UiState.Content)?.snapshot
            else -> (outcome.scheduled as? WidgetViews.UiState.Content)?.snapshot
        } ?: return
        val gateway = outcome.gatewayUrl ?: return
        instancePrefs.edit {
            for (id in ids) {
                putString(key(id), listOf(kind, gateway, outcome.profile.orEmpty(), kanbanSlug(snapshot)).joinToString("|"))
            }
        }
    }

    private fun kanbanSlug(snapshot: Any): String =
        (snapshot as? dev.hermeskotlin.core.widget.KanbanWidgetSnapshot)?.boardSlug.orEmpty()

    private val instancePrefs = context.getSharedPreferences("widget.instances", Context.MODE_PRIVATE)

    private fun key(appWidgetId: Int) = "widget.instance.v1.$appWidgetId"

    /** "Couldn't refresh · Saved HH:MM" — the reserved strip's copy after a failed refresh (D26). */
    private fun savedNotice(savedAtEpochSeconds: Long): String = context.getString(
        R.string.widget_refresh_saved_at,
        TIME_FORMAT.withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(savedAtEpochSeconds)),
    )

    private companion object {
        /** D26's coalesce window: one pass per burst of triggers. */
        const val DEBOUNCE_MS = 5_000L
        const val KIND_NOW = "now"
        const val KIND_CHATS = "chats"
        const val KIND_KANBAN = "kanban"
        const val KIND_SCHEDULED = "scheduled"
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    }
}

/**
 * Whether the widgets must render their locked variant (D16): the App lock setting on, and either
 * the timer says locked or the activity never launched in this process — a broadcast-started
 * process has no unlocked activity, so the cold default is locked. Settings that can't be read
 * within [settingsTimeoutMs] count as locked too: showing counts is the safe side.
 */
suspend fun isWidgetLocked(
    settings: SettingsStore,
    appLock: AppLockTimer,
    settingsTimeoutMs: Long = 2_000L,
): Boolean {
    val prefs = withTimeoutOrNull(settingsTimeoutMs) { settings.settings.filterNotNull().first() }
        ?: return true
    return prefs.appLock && (appLock.locked.value || !appLock.hasLaunched)
}

/** Any flow as the unit signal the debouncer merges on. */
private fun <T> kotlinx.coroutines.flow.Flow<T>.asSignal(): kotlinx.coroutines.flow.Flow<Unit> = map { }
