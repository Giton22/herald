package dev.hermeskotlin.android.pebble

import dev.hermeskotlin.android.notify.RequestAnswers
import dev.hermeskotlin.android.wear.WearRequests
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.settings.answersOnPebble
import dev.hermeskotlin.core.wear.WatchRelay
import dev.hermeskotlin.core.wear.watchAnswer
import dev.hermeskotlin.core.wear.watchView
import dev.hermeskotlin.wearlink.PebbleIncoming
import dev.hermeskotlin.wearlink.PebbleLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Approvals and questions on a Pebble, while answering there is on (Settings → Connected devices → Answer on the Pebble).
 * Those in the watch's chat go to the Pebble that asked, as they come, and again when Kyllene opens there; what's
 * picked or dictated there comes back through [answer]. Off, none are sent, and the watch says to answer on the
 * phone, where they're the usual notifications ([WearRequests]); on, those are posted quietly and on the phone only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PebbleRequests(
    private val wearRequests: WearRequests,
    private val relay: WatchRelay,
    private val outbox: PebbleOutbox,
    private val answers: RequestAnswers,
    private val settings: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The Pebble whose ask runs in the watch's chat, and a count that sends what waits there again. */
    private val node = MutableStateFlow<Pair<String, Int>?>(null)

    // Only touched on the main thread.
    private val shown = mutableSetOf<String>()

    /** Requests picked up from a ring, in any chat, and the Pebble each shows on. Only touched on the main thread. */
    private val fromRings = mutableMapOf<String, String>()

    private val on: Boolean get() = settings.settings.value?.answersOnPebble == true

    fun start() {
        // Shown here, they'd show twice: once from Kyllene, once mirrored from the phone's notification.
        // Only while Kyllene is open on that Pebble: closed or out of reach, nothing gets there, and the phone rings.
        wearRequests.shownOnWatch = { on && node.value?.first?.let { outbox.watch(it).isOpen } == true }
        scope.launch {
            val state = relay.session.flatMapLatest { it?.state ?: flowOf(null) }
            val answering = settings.settings.map { it?.answersOnPebble == true }.distinctUntilChanged()
            combine(state, answering, node) { chat, on, to -> Triple(chat, on, to) }.collect { (chat, on, to) ->
                val watch = to?.first ?: return@collect
                val open = if (on) chat?.inputRequests.orEmpty() else emptyList()
                for (request in open) {
                    if (shown.add(request.id)) show(watch, request, chat?.storedSessionId, chat?.title)
                }
                shown.filter { id -> open.none { it.id == id } }.forEach {
                    shown -= it
                    outbox.watch(watch).message(PebbleLink.requestEnd(it))
                }
            }
        }
    }

    /**
     * [watch] asked in the watch's chat: what waits there goes to it when it's a Pebble, and to none when it's a
     * Wear OS watch, which shows them from the phone's notifications. A Pebble that had them shows them no more.
     */
    fun askedFrom(watch: String) {
        scope.launch {
            val to = watch.takeIf(PebbleOutbox::isPebble)
            val was = node.value?.first
            if (was == to) return@launch
            was?.let { old -> shown.forEach { outbox.watch(old).message(PebbleLink.requestEnd(it)) } }
            shown.clear()
            node.value = to?.let { it to 0 }
        }
    }

    /** Kyllene opened on [watch], which starts with nothing: what waits for it goes again. */
    fun reopened(watch: String) {
        val now = node.value ?: return
        if (now.first != watch) return
        shown.clear()
        node.value = watch to now.second + 1
    }

    /** Sends [request], waiting in chat [chatId] ([title]), to [watch]. */
    fun show(watch: String, request: InputRequest, chatId: String?, title: String?) {
        val view = watchView(request)
        outbox.watch(watch).message(
            PebbleLink.request(request.id, chatId, title, view.approval, view.heading, view.detail, view.choices, view.answering.ordinal),
        )
    }

    /** Picked up on [watch] from a ring: [request], waiting in chat [chatId] ([title]), shows there until it's [gone]. */
    fun showFromRing(watch: String, request: InputRequest, chatId: String?, title: String?) {
        fromRings[request.id] = watch
        show(watch, request, chatId, title)
    }

    /** Request [requestId] is answered or over: a Pebble showing it from a ring closes it. */
    fun gone(requestId: String) {
        val watch = fromRings.remove(requestId) ?: return
        outbox.watch(watch).message(PebbleLink.requestEnd(requestId))
    }

    /** What [watch] answered: given only while answering on the Pebble is on, and the watch told how it went. */
    fun answer(watch: String, answer: PebbleIncoming.Answer) {
        scope.launch {
            val error = if (!on) {
                "Answering on the Pebble is off in Kyllene's settings. Answer on your phone."
            } else {
                when (answers.answer(answer.requestId) { watchAnswer(it, answer.index, answer.text) }) {
                    RequestAnswers.Outcome.Answered -> null
                    RequestAnswers.Outcome.Gone -> "It was already answered."
                    RequestAnswers.Outcome.Refused -> "That answer doesn't fit any more. Answer on your phone."
                    RequestAnswers.Outcome.NotConnected -> "Kyllene can't reach your gateway right now."
                }
            }
            outbox.watch(watch).message(PebbleLink.requestEnd(answer.requestId, error))
        }
    }
}
