package dev.hermeskotlin.core.bots

import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.DeliveryOutcome
import dev.hermeskotlin.core.chat.TranscriptEvent
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A failure that keeps a bot from working until someone fixes something, Desktop's needs-attention
 * classes (data.ts `BOT_ATTENTION_CLASSES`). Rate limits, server errors and timeouts pass on their own,
 * so they never count.
 */
enum class BotProblem(val hint: String) {
    /** `provider_auth_or_access`: the provider turned the bot's key or sign-in down. */
    SignIn("Its provider sign-in or key was refused"),

    /** `provider_quota_limit`: out of quota, credits or balance. */
    Quota("Its provider's quota or balance ran out"),

    /** `missing_config`: no provider or model it can use. */
    Setup("It has no model it can use"),

    /** `agent_blocked`: the agent stopped itself and says why in its chat. */
    Blocked("It's blocked; its last message says why"),
    ;

    companion object {
        private val TRANSIENT = Regex("""rate.?limit|too many requests|\b429\b|\b5\d\d\b|server error|overloaded|timed?.?out|timeout|temporar""")
        private val SETUP = Regex("""no llm provider|no access token|not configured|no api key|missing api key|no hermes provider|has no model""")
        private val SIGN_IN = Regex("""\b401\b|\b403\b|unauthorized|forbidden|authentication|invalid.?api.?key|credentials? (are )?(invalid|expired)""")
        private val QUOTA = Regex("""quota|out of funds|insufficient (credits?|funds|balance)|payment required|\b402\b|billing""")
        private val BLOCKED = Regex("""\bblocked\b""")

        /**
         * The lasting problem a typed reason or an error's text stands for, or null when it's passing or
         * unknown (attentionReasonFromError, ported): passing failures are ruled out first.
         */
        fun classify(reasonOrText: String?): BotProblem? {
            val raw = reasonOrText?.trim().orEmpty()
            if (raw.isEmpty()) return null
            when (raw) {
                "provider_auth_or_access" -> return SignIn
                "provider_quota_limit" -> return Quota
                "missing_config" -> return Setup
                "agent_blocked" -> return Blocked
            }
            val text = raw.lowercase()
            return when {
                TRANSIENT.containsMatchIn(text) -> null
                SETUP.containsMatchIn(text) -> Setup
                SIGN_IN.containsMatchIn(text) -> SignIn
                QUOTA.containsMatchIn(text) -> Quota
                BLOCKED.containsMatchIn(text) -> Blocked
                else -> null
            }
        }
    }
}

/** What's wrong with a bot: the [problem], the gateway's own words for it, and how this phone learned it. */
data class BotTrouble(val problem: BotProblem, val detail: String, val source: Source) {
    enum class Source {
        /** `setup.runtime_check` said the bot can't be served: true until a check says otherwise. */
        Check,

        /** A turn or a delivery to the bot failed: true until the bot next answers. */
        Turn,
    }
}

/**
 * The bots that need the user before they can work again, by profile: the roster's ⚠. Learned from a
 * readiness check of each bot once per connection (`setup.runtime_check`, which runs the same resolver as
 * a new session), and from the failures this phone sees: a turn of the bot's that failed, a message to it
 * that couldn't be delivered. A good turn clears what a failed one said; only a check clears a check's.
 * Kept on the phone only, never written anywhere.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BotHealth(
    private val connection: GatewayConnection,
    host: ChatHost,
    private val scope: CoroutineScope,
    private val check: suspend (profile: String) -> RuntimeCheck,
) {
    private val _troubles = MutableStateFlow<Map<String, BotTrouble>>(emptyMap())
    val troubles: StateFlow<Map<String, BotTrouble>> = _troubles.asStateFlow()

    /** Bots checked on this connection; a new socket checks them again. */
    private val checked = mutableSetOf<String>()

    init {
        scope.launch {
            connection.state.distinctUntilChangedBy { (it as? ConnectionState.Connected)?.client }.collect { checked.clear() }
        }
        scope.launch {
            host.session.collectLatest { session -> if (session != null) watch(session) }
        }
    }

    /**
     * Reads what happens in the open chat while it's open: its own turns failing or answering (when it runs
     * in a bot's profile), and the outcome of every message it sends another bot. What the transcript held
     * when it opened is history, possibly long since fixed, so only rows that arrive later count.
     */
    private suspend fun watch(session: ChatSession) {
        var seen: MutableSet<String>? = null
        var lastError: String? = null
        // The newest finished reply seen; one after it is the bot answering. Read by key, not by watching
        // `running` flip: a state flow may skip a short turn's running state altogether.
        var lastReply: String? = null
        session.state.collect { state ->
            if (!state.historyLoaded) return@collect
            val reply = (state.messages.lastOrNull() as? ChatMessage.Assistant)
                ?.takeIf { !it.streaming && !state.running && it.text.isNotBlank() }?.key
            val known = seen ?: state.messages.mapTo(HashSet()) { it.key }.also {
                seen = it
                lastReply = reply
            }
            // An earlier page loaded on scroll-up lands above the first stored row already seen: history too.
            // Only stored rows anchor it; a live reply or a local bubble can sit anywhere meanwhile.
            val firstKnown = state.messages.indexOfFirst { it.key in known && it.key.startsWith(STORED_ROW) }
            val fresh = state.messages.filterIndexed { i, message -> message.key !in known && i > firstKnown }
            state.messages.mapTo(known) { it.key }
            val self = session.profile
            fresh.forEach { message ->
                val event = (message as? ChatMessage.Event)?.event
                when {
                    event is TranscriptEvent.Delivery -> event.target?.let(::profileOf)?.let { target ->
                        when (val outcome = event.outcome) {
                            is DeliveryOutcome.Failed -> noteFailure(target, outcome.reason ?: outcome.detail)
                            is DeliveryOutcome.Replied, DeliveryOutcome.NoReply -> noteAnswered(target)
                            is DeliveryOutcome.Waiting -> Unit
                        }
                    }
                    event is TranscriptEvent.FailedTurn && self != null -> noteFailure(self, event.text)
                }
            }
            if (self != null) {
                if (state.error != null && state.error != lastError) noteFailure(self, state.error)
                // A turn that ended in a reply is the bot working again.
                if (reply != null && reply != lastReply && state.error == null) noteAnswered(self)
            }
            if (reply != null) lastReply = reply
            lastError = state.error
        }
    }

    /** A delivery names its target by handle: `hermes` is the default profile's. */
    private fun profileOf(target: String): String? =
        target.trim().removePrefix("@").takeIf { it.isNotEmpty() }?.let { if (it == "hermes") Bot.DEFAULT else it }

    /** Forgets everything: another gateway's bots are other bots. */
    fun reset() {
        checked.clear()
        _troubles.value = emptyMap()
    }

    /** Checks each of [profiles] not yet checked on this connection. */
    fun checkOnce(profiles: Collection<String>) {
        profiles.filter { checked.add(it) }.forEach { recheck(it) }
    }

    /** Checks [profile] now, e.g. after its model or keys were changed. */
    fun recheck(profile: String) {
        checked.add(profile)
        scope.launch {
            val result = try {
                check(profile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No verdict: asked again on the next connection.
                checked.remove(profile)
                return@launch
            }
            _troubles.update { all ->
                val current = all[profile]
                val problem = if (result.ok) null else BotProblem.classify(result.error) ?: BotProblem.Setup
                when {
                    problem != null -> all + (profile to BotTrouble(problem, result.error.orEmpty(), BotTrouble.Source.Check))
                    current?.source == BotTrouble.Source.Check -> all - profile
                    else -> all
                }
            }
        }
    }

    /** A turn of [profile]'s, or a delivery to it, failed with [reasonOrText]; noted when it lasts. */
    fun noteFailure(profile: String, reasonOrText: String?) {
        val problem = BotProblem.classify(reasonOrText) ?: return
        _troubles.update { it + (profile to BotTrouble(problem, reasonOrText.orEmpty().trim().take(MAX_DETAIL), BotTrouble.Source.Turn)) }
    }

    /** [profile] answered: whatever a failed turn said no longer holds. */
    fun noteAnswered(profile: String) {
        _troubles.update { all -> if (all[profile]?.source == BotTrouble.Source.Turn) all - profile else all }
    }

    private companion object {
        const val MAX_DETAIL = 200

        /** The key prefix of a message read from the stored transcript (ChatReducer). */
        const val STORED_ROW = "row-"
    }
}

/** `setup.runtime_check`'s answer: whether the bot's model can be served, and the gateway's reason if not. */
data class RuntimeCheck(val ok: Boolean, val error: String? = null)
