package dev.hermeskotlin.android.widget

import dev.hermeskotlin.core.bots.Bot
import dev.hermeskotlin.core.common.runCatchingCancellable
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.widget.ChatsWidgetSnapshot
import dev.hermeskotlin.core.widget.KanbanWidgetSnapshot
import dev.hermeskotlin.core.widget.NowWidgetSnapshot
import dev.hermeskotlin.core.widget.ScheduledWidgetSnapshot
import dev.hermeskotlin.core.widget.WidgetRefreshPipelines
import dev.hermeskotlin.core.widget.WidgetSnapshotStore
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Decides what each widget instance shows after a refresh attempt (docs/WIDGETS-SPEC.md D26): the
 * signed-out state, the per-widget pipelines, and the stored-snapshot fallbacks with their "Saved
 * copy" notices. Pure decision logic over [WidgetDataAssembler]/[WidgetSnapshotStore]; rendering
 * and `AppWidgetManager` calls live in [WidgetUpdater].
 *
 * The whole pass runs under D26's deadline: a broadcast receiver's window outlives the shared
 * client's 15 s connect / 30 s request timeouts, so on timeout the in-flight calls are cancelled,
 * nothing is written over the stored snapshots, and every widget falls back to what the store
 * still holds (with its "Couldn't refresh · Saved" notice) or its error state.
 */
class WidgetRefresher(
    private val assembler: WidgetRefreshPipelines,
    private val store: WidgetSnapshotStore,
    private val gateways: GatewayRepository,
    /** The roster a cold refresh may rely on (D14): [BotNotifier.knownRoster] in the app. */
    private val knownBots: suspend () -> List<Bot> = { emptyList() },
    /** D26's refresh deadline; injectable so tests can prove the timeout behaviour. */
    private val deadlineMs: Long = DEFAULT_DEADLINE_MS,
) {

    /** What one refresh produced, per widget, before the App-lock redaction (D16) is applied. */
    data class Outcome(
        /** Null when no gateway is signed in: every widget shows its signed-out state. */
        val gatewayUrl: String?,
        val gatewayLabel: String?,
        val profile: String?,
        /** True when the socket was down, so Now's renderer can mark cold-carried rows (§6.5). */
        val nowCold: Boolean,
        val now: WidgetViews.UiState<NowWidgetSnapshot>,
        val chats: WidgetViews.UiState<ChatsWidgetSnapshot>,
        val kanban: WidgetViews.UiState<KanbanWidgetSnapshot>,
        val scheduled: WidgetViews.UiState<ScheduledWidgetSnapshot>,
    )

    /**
     * One debounced pass over all four widgets. [savedNotice] formats the strip's "Couldn't refresh ·
     * Saved HH:MM" line from a stored snapshot's `savedAtEpochSeconds`, keeping this class free of
     * Android context. Each widget's failure is independent (D26): one pipeline's error never blocks
     * the others.
     */
    suspend fun refresh(profile: String?, connected: Boolean, savedNotice: (Long) -> String): Outcome =
        withTimeoutOrNull(deadlineMs) { refreshAttempt(profile, connected, savedNotice) }
            ?: fallback(profile, savedNotice)

    private suspend fun refreshAttempt(profile: String?, connected: Boolean, savedNotice: (Long) -> String): Outcome {
        val list = gateways.list.value
        val gateway = list.current ?: list.primary
            ?: return Outcome(
                gatewayUrl = null, gatewayLabel = null, profile = profile, nowCold = true,
                now = WidgetViews.UiState.SignedOut, chats = WidgetViews.UiState.SignedOut,
                kanban = WidgetViews.UiState.SignedOut, scheduled = WidgetViews.UiState.SignedOut,
            )
        val url = gateway.gatewayUrl

        // Cold (REST-only) refreshes must not run the live Now pipeline: with empty socket maps it
        // would wipe every carried socket-only row. The cold path merges the stored snapshot's rows
        // across (D14), judging them against the known/saved roster so a bot the socket never listed
        // in this process isn't dropped as unknown.
        val nowSnapshot = runCatchingCancellable {
            if (connected) assembler.refreshNow(url, profile)
            else assembler.refreshNowCold(url, profile, knownBots = knownBots())
        }.getOrNull()
        val now = if (nowSnapshot != null) {
            WidgetViews.UiState.Content(nowSnapshot)
        } else {
            store.readNow(url.value, profile)
                ?.let { WidgetViews.UiState.Content(it, savedNotice(it.savedAtEpochSeconds)) }
                ?: WidgetViews.UiState.Error
        }
        val chatsSnapshot = runCatchingCancellable { assembler.refreshChats(url, profile) }.getOrNull()
        val chats = if (chatsSnapshot != null) {
            WidgetViews.UiState.Content(chatsSnapshot)
        } else {
            store.readChats(url.value, profile)
                ?.let { WidgetViews.UiState.Content(it, savedNotice(it.savedAtEpochSeconds)) }
                ?: WidgetViews.UiState.Error
        }
        val kanban = runCatchingCancellable { assembler.refreshKanban(url) }.getOrNull()
            ?.let { WidgetViews.UiState.Content(it) }
            ?: WidgetViews.UiState.Error
        val scheduledSnapshot = runCatchingCancellable { assembler.refreshScheduled(url, live = connected) }.getOrNull()
        val scheduled = if (scheduledSnapshot != null) {
            WidgetViews.UiState.Content(scheduledSnapshot)
        } else {
            store.readScheduled(url.value)
                ?.let { WidgetViews.UiState.Content(it, savedNotice(it.savedAtEpochSeconds)) }
                ?: WidgetViews.UiState.Error
        }

        return Outcome(
            gatewayUrl = url.value,
            gatewayLabel = gateway.label,
            profile = profile,
            nowCold = !connected,
            now = now,
            chats = chats,
            kanban = kanban,
            scheduled = scheduled,
        )
    }

    /**
     * The deadline-expired outcome (D26): the refresh was cancelled mid-flight and nothing was
     * written over the stored snapshots, so every widget shows what the store still holds — with
     * the "Couldn't refresh · Saved HH:MM" notice — or its error state, never a loading spinner.
     */
    private suspend fun fallback(profile: String?, savedNotice: (Long) -> String): Outcome {
        val list = gateways.list.value
        val gateway = list.current ?: list.primary
            ?: return Outcome(
                gatewayUrl = null, gatewayLabel = null, profile = profile, nowCold = true,
                now = WidgetViews.UiState.SignedOut, chats = WidgetViews.UiState.SignedOut,
                kanban = WidgetViews.UiState.SignedOut, scheduled = WidgetViews.UiState.SignedOut,
            )
        val url = gateway.gatewayUrl.value
        suspend fun <T> stored(read: suspend () -> T?, notice: (T) -> String): WidgetViews.UiState<T> {
            val snapshot = read()
            return if (snapshot != null) WidgetViews.UiState.Content(snapshot, notice(snapshot)) else WidgetViews.UiState.Error
        }
        return Outcome(
            gatewayUrl = url,
            gatewayLabel = gateway.label,
            profile = profile,
            nowCold = true,
            now = stored({ store.readNow(url, profile) }) { savedNotice(it.savedAtEpochSeconds) },
            chats = stored({ store.readChats(url, profile) }) { savedNotice(it.savedAtEpochSeconds) },
            kanban = WidgetViews.UiState.Error,
            scheduled = stored({ store.readScheduled(url) }) { savedNotice(it.savedAtEpochSeconds) },
        )
    }

    companion object {
        /** D26's hard deadline for one pass over all four widgets; the receiver window is 10 s. */
        const val DEFAULT_DEADLINE_MS = 8_000L

        /**
         * One widget's outcome from its refresh attempt and what the store still holds (docs/WIDGETS-SPEC.md
         * D26): a fresh snapshot wins; else the stored one with its "Couldn't refresh · Saved" notice; else
         * the error state. Pure, so the fallback chain is testable without Android.
         */
        internal fun <T> decide(fresh: T?, stored: T?, notice: (T) -> String): WidgetViews.UiState<T> = when {
            fresh != null -> WidgetViews.UiState.Content(fresh)
            stored != null -> WidgetViews.UiState.Content(stored, notice(stored))
            else -> WidgetViews.UiState.Error
        }
    }
}
