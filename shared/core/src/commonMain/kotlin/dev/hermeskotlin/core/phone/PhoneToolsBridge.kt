package dev.hermeskotlin.core.phone

import dev.hermeskotlin.core.auth.secureRandomBytes
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.phone.PhoneToolsStatus.State
import dev.hermeskotlin.core.push.KnownPhone
import dev.hermeskotlin.core.push.PhoneRequest
import dev.hermeskotlin.core.push.PluginState
import dev.hermeskotlin.core.push.PushApi
import dev.hermeskotlin.core.push.PushKeys
import dev.hermeskotlin.core.push.PushMessage
import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.settings.SettingsStore
import dev.hermeskotlin.core.storage.KeyValueStore
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Answers the agent's phone tool calls. While connected it tells the gateway's herald-push plugin which tools
 * are switched on (the plugin offers the agent only those), then fetches and runs the calls waiting for this
 * phone: when a chat it watches starts a `phone_*` tool, when a push says one waits, and every
 * [POLL_MS] for chats it doesn't watch.
 *
 * Nothing runs that is switched off now: a call for a tool turned off, even one already on screen, is refused.
 */
class PhoneToolsBridge(
    private val api: PushApi,
    private val keys: PushKeys,
    private val store: KeyValueStore,
    private val connection: GatewayConnection,
    private val gateways: GatewayRepository,
    private val settings: SettingsStore,
    private val runner: PhoneToolRunner,
    private val scope: CoroutineScope,
    private val deviceName: () -> String,
    private val now: () -> Long = ::getTimeMillis,
) {
    private val _status = MutableStateFlow(PhoneToolsStatus())
    val status: StateFlow<PhoneToolsStatus> = _status.asStateFlow()

    private val _recent = MutableStateFlow<List<PhoneToolUse>>(emptyList())

    /** The latest uses, newest first, for as long as the app runs. */
    val recent: StateFlow<List<PhoneToolUse>> = _recent.asStateFlow()

    private val _phones = MutableStateFlow<List<KnownPhone>>(emptyList())

    /** The phones the gateway knows, in the order calls no phone started try them; empty until fetched. */
    val phones: StateFlow<List<KnownPhone>> = _phones.asStateFlow()
    private val phonesLock = Mutex()
    private var listedOn: JsonRpcClient? = null

    /** Whether the gateway's plugin lists phones ([PushApi.PHONE_LIST_PLUGIN_VERSION]). */
    private var canListPhones = false

    /** What the user asked of the gateway's plugin: [n] counts the asks, [update] when the last was Update. */
    private data class Ask(val n: Int = 0, val update: Boolean = false)

    /** Bumped by [setUp] and [updatePlugin]: the next report may install the plugin. Spent only by a report that ran. */
    private val setUps = MutableStateFlow(Ask())
    private var setUpsDone = 0

    /** This phone's phone-tools identity: an id and the secret that binds it on the gateway, kept together. */
    @Serializable
    private data class Identity(val id: String, val key: String)

    private class Running(val tool: String, val job: Job)

    private val running = mutableMapOf<String, Running>()

    /** Calls already answered, so a fetch made before the answer landed doesn't run them twice. */
    private val answered = ArrayDeque<String>()
    private val runningLock = Mutex()
    private val draining = Mutex()
    private val identityLock = Mutex()

    fun start() {
        scope.launch {
            val client = connection.state.map { (it as? ConnectionState.Connected)?.client }.distinctUntilChanged()
            val enabled = settings.settings.filterNotNull().map { it.enabledPhoneTools() }.distinctUntilChanged()
            // A plugin update from Notifications anywhere: look again, so the page doesn't still offer it. Turned on
            // (or off) after the tools were reported: report again, so a call rings through the push off the network.
            combine(client, enabled, setUps, api.updates, api.registrations) { c, on, setUp, _, _ -> Triple(c, on, setUp) }.collectLatest { (c, on, setUp) ->
                refuseSwitchedOff(on)
                serve(c, on, setUp)
            }
        }
    }

    /** Installs or turns on the plugin when the gateway lacks it, then reports the tools. */
    fun setUp() = setUps.update { Ask(it.n + 1) }

    /** The user asked for the newer plugin the status offers. */
    fun updatePlugin() = setUps.update { Ask(it.n + 1, update = true) }

    /** A push said a call waits: fetch it now. */
    fun wake() {
        scope.launch {
            if (connection.state.value is ConnectionState.Connected && settings.settings.value?.enabledPhoneTools().orEmpty().isNotEmpty()) {
                drain()
            }
        }
    }

    private suspend fun serve(client: JsonRpcClient?, on: Set<PhoneTool>, setUp: Ask) {
        // Another connection may be another gateway: its phones aren't these. A switch flipped keeps them.
        if (client !== listedOn) _phones.value = emptyList()
        listedOn = client
        if (client == null) {
            _status.value = PhoneToolsStatus(if (on.isEmpty()) State.Off else State.Offline)
            return
        }
        if (on.isEmpty()) {
            // Told once, so the gateway stops offering them; a phone that never had any isn't announced.
            if (store.get(reportedKey()) == "1") report(on, install = false, update = false)
            _status.value = PhoneToolsStatus(State.Off)
            return
        }
        coroutineScope {
            // From the start: the turn sent while the report is on its way is the one about to call a tool.
            val teller = launch { tellWhereTurnsComeFrom(client) }
            if (!reportUntilDone(on, setUp)) {
                teller.cancel()
                return@coroutineScope
            }
            launch { phonesCall { api.phoneList(it.id, it.key) } }
            launch {
                client.events
                    .filter { it.type == "tool.start" && toolName(it.payload)?.startsWith(TOOL_PREFIX) == true }
                    .collect {
                        // The event can come before the gateway has written the call down: look again shortly.
                        launch {
                            for (wait in EVENT_DRAINS_MS) {
                                delay(wait)
                                drain()
                            }
                        }
                    }
            }
            while (true) {
                drain()
                delay(POLL_MS)
            }
        }
    }

    /** Reports until the gateway took it; false when it waits for the user (no plugin, or one too old). */
    private suspend fun reportUntilDone(on: Set<PhoneTool>, setUp: Ask): Boolean {
        var attempt = 0
        while (true) {
            val install = setUp.n > setUpsDone
            val reported = report(on, install, update = install && setUp.update)
            // Spent once a report with it ran to the end, not when a reconnect or a switch cut it short.
            if (install) setUpsDone = setUp.n
            if (reported) return true
            // Set up or Update waits for the user; anything else (a timeout, a refusal) is tried again.
            if (_status.value.state in WAITS_FOR_USER) return false
            delay(RETRY_MS[attempt.coerceAtMost(RETRY_MS.lastIndex)])
            attempt++
        }
    }

    /**
     * Each turn this phone sends is told to the gateway with its chat connection, so the agent's phone tool calls
     * in it come to this phone and not to another one with the same tool on. Every turn, not once per connection:
     * another phone may have sent on the same chat since. Not told (a dropped link), the call goes to the first
     * phone in the user's order with the tool on.
     */
    private suspend fun tellWhereTurnsComeFrom(client: JsonRpcClient) {
        // Replayed sends from before this started (a tool switched, which restarts this) are old news: telling
        // them again would take a chat back from a phone that sent on it since.
        val since = client.clockMillis() - REPLAY_WINDOW_MS
        client.sent.collect { request ->
            if (!request.method.startsWith(TURN_PREFIX) || request.atMillis < since) return@collect
            val sid = request.sessionId?.takeIf { it.isNotBlank() } ?: return@collect
            val me = identity()
            try {
                api.phoneHere(me.id, me.key, sid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The call falls back to the first phone in the user's order with the tool on.
            }
        }
    }

    /** Asks the gateway for the phones list again (the page opened). */
    fun refreshPhones() {
        scope.launch { phonesCall { api.phoneList(it.id, it.key) } }
    }

    /** Moves phone [id] one place up ([by] -1) or down (+1) in the order calls no phone started try them. */
    fun movePhone(id: String, by: Int) {
        scope.launch {
            phonesCall { me ->
                val ids = _phones.value.map { it.id }.toMutableList()
                val from = ids.indexOf(id)
                val to = from + by
                // Gone or already at the end: nothing to send, the list stands.
                if (from < 0 || to !in ids.indices) return@phonesCall _phones.value
                ids.add(to, ids.removeAt(from))
                api.phoneOrder(me.id, me.key, ids)
            }
        }
    }

    /** Forgets another phone on the gateway: one the user doesn't have any more. */
    fun removePhone(id: String) {
        scope.launch { phonesCall { api.phoneRemove(it.id, it.key, id) } }
    }

    /**
     * Runs one phones-list call while phone tools are on with the gateway, one at a time (each works on the list
     * the one before left), and keeps the list it answers. A refusal leaves the list and says so.
     */
    private suspend fun phonesCall(call: suspend (Identity) -> List<KnownPhone>?) = phonesLock.withLock {
        // An older plugin has no list: the page offers the update instead.
        if (_status.value.state != State.On || !canListPhones) return@withLock
        val gateway = listedOn
        val got = try {
            call(identity())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        // Another gateway since: this list isn't its.
        if (listedOn !== gateway) return@withLock
        if (got != null) {
            _phones.value = got
        } else if (_status.value.state == State.On) {
            _status.update { it.copy(detail = "The gateway didn't take the change to the phones list.") }
        }
    }

    /** Tells the gateway what's on. False when it can't take phone tools (no plugin, too old, refused, unreachable). */
    private suspend fun report(on: Set<PhoneTool>, install: Boolean, update: Boolean): Boolean {
        try {
            if (on.isNotEmpty()) _status.value = PhoneToolsStatus(State.Working)
            // Turning everything off must reach even a plugin too old to use: else it keeps offering the tools.
            val minimum = if (on.isEmpty()) PushApi.MIN_PLUGIN_VERSION else PushApi.MIN_PHONE_PLUGIN_VERSION
            val plugin = api.prepare(install, minimum, update) {
                if (on.isNotEmpty()) _status.value = PhoneToolsStatus(State.Working, it)
            }
            val ready = when (plugin) {
                PluginState.Missing -> {
                    _status.value = PhoneToolsStatus(if (on.isEmpty()) State.Off else State.NotInstalled)
                    return false
                }
                is PluginState.Outdated -> {
                    _status.value = if (on.isEmpty()) PhoneToolsStatus(State.Off) else PhoneToolsStatus(State.Outdated, update = plugin.update)
                    return false
                }
                is PluginState.Failed -> {
                    _status.value = PhoneToolsStatus(State.Failed, plugin.reason)
                    return false
                }
                is PluginState.Ready -> plugin
            }
            canListPhones = ready.info.versionAtLeast(PushApi.PHONE_LIST_PLUGIN_VERSION)
            if (!canListPhones) _phones.value = emptyList()
            val me = identity()
            val offered = api.phoneTools(me.id, me.key, deviceName(), on.map { it.id }.sorted(), pushId()) ?: return false
            store.put(reportedKey(), if (on.isEmpty()) "0" else "1")
            val missing = on.filter { it.id !in offered }
            _status.value = PhoneToolsStatus(
                State.On,
                listOfNotNull(ready.detail,
                    if (missing.isEmpty()) null else "The gateway's plugin doesn't know ${missing.joinToString { it.title }} yet.",
                ).joinToString("\n").ifEmpty { null },
                ready.update,
            )
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = PhoneToolsStatus(State.Failed, e.message)
            return false
        }
    }

    /** The push registration a call can nudge: this phone's, when it's registered with this gateway. */
    private suspend fun pushId(): String? {
        if (settings.settings.value?.pushAnywhere != true) return null
        val url = gateways.current()?.gatewayUrl?.value ?: return null
        return keys.deviceId.takeIf { keys.pinnedGatewayUrl() == url }
    }

    /** Fetches the calls waiting for this phone and starts each one not already running or answered. */
    private suspend fun drain() = draining.withLock {
        val me = identity()
        val waiting = try {
            api.phonePending(me.id, me.key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return@withLock
        startAll(waiting)
    }

    /**
     * A push carried a call that rings ([PhoneTool.CallMe]): it rings at once, even while the gateway is out of reach,
     * and the answer goes back once it isn't. Fetched over the socket too, it still rings once.
     */
    fun ring(message: PushMessage) {
        val id = message.requestId?.takeIf { REQUEST_ID.matches(it) } ?: return
        val reason = message.reason?.takeIf { it.isNotBlank() } ?: return
        val expires = message.expires ?: return
        val args = buildJsonObject {
            put("reason", reason)
            message.details?.takeIf { it.isNotBlank() }?.let { put("details", it) }
        }
        // The push says when it was sent, not when it got here: one read late (a backlog after the phone was off)
        // counts the delay against the call's time, on this phone's clock with some slack for a clock that's off.
        val late = (now() / 1_000 - message.ts - PUSH_SKEW_S).coerceAtLeast(0)
        val sent = message.ts + late
        if ((expires - sent) * 1_000 <= DEADLINE_MARGIN_MS) return
        val request = PhoneRequest(id, PhoneTool.CallMe.id, args, message.session.orEmpty(), expires, gatewayNow = sent)
        scope.launch { startAll(listOf(request)) }
    }

    /** Starts each of [waiting] not already running or answered. */
    private suspend fun startAll(waiting: List<PhoneRequest>) = runningLock.withLock {
        for (request in waiting) {
            if (request.id in running || request.id in answered) continue
            running[request.id] = Running(request.tool, scope.launch { handle(request) })
        }
    }

    private suspend fun handle(request: PhoneRequest) {
        val started = now()
        val tool = PhoneTool.byId(request.tool)
        // Up to the gateway's own deadline, less a margin: the phone may look late (a poll, Doze), and an Allow
        // the agent would never hear of must not change the phone. Measured on the gateway's clock, so a phone
        // clock that's off doesn't matter; an older plugin doesn't say its time, and gets the plain limit.
        val limit = if (request.expires > 0 && request.gatewayNow > 0) {
            ((request.expires - request.gatewayNow) * 1_000 - DEADLINE_MARGIN_MS).coerceAtMost(RUN_LIMIT_MS)
        } else {
            RUN_LIMIT_MS
        }
        val (result, error) = if (tool == null) {
            null to UNKNOWN_TOOL
        } else if (tool !in settings.settings.value?.enabledPhoneTools().orEmpty()) {
            null to SWITCHED_OFF
        } else if (limit <= 0) {
            // Too late to ask or do anything the agent would still hear of: nothing runs.
            null to TOO_LONG
        } else {
            try {
                withTimeout(limit) { runner.run(tool, request.args, request.session) } to null
            } catch (e: TimeoutCancellationException) {
                null to TOO_LONG
            } catch (e: CancellationException) {
                // Switched off meanwhile: refuseSwitchedOff answers it.
                throw e
            } catch (e: PhoneToolException) {
                null to (e.message ?: "The phone couldn't do it.")
            } catch (e: Exception) {
                null to "The phone couldn't do it (${e::class.simpleName})."
            }
        }
        if (tool != null && error != SWITCHED_OFF) _recent.update { (listOf(PhoneToolUse(tool, now(), error)) + it).take(MAX_RECENT) }
        // Out of the running list before answering: switching the tool off now can't send a second answer.
        if (!finish(request.id)) return
        // Until the gateway stops waiting: a call declined off the network gets there once the way back is up.
        answer(request.id, result, error, wait = (started + limit + DEADLINE_MARGIN_MS - now()).coerceAtLeast(ANSWER_WAIT_MS))
    }

    /** Takes [requestId] out of the running list; false when switching off already did (and answered it). */
    private suspend fun finish(requestId: String): Boolean = runningLock.withLock {
        val mine = running.remove(requestId) != null
        if (mine) markAnswered(requestId)
        mine
    }

    private fun markAnswered(requestId: String) {
        answered.addLast(requestId)
        while (answered.size > MAX_ANSWERED) answered.removeFirst()
    }

    /** Stops what runs for a tool that's off now (a question on screen, say) and refuses it. */
    private suspend fun refuseSwitchedOff(on: Set<PhoneTool>) {
        val ids = on.map { it.id }.toSet()
        val stopped = runningLock.withLock {
            running.filterValues { it.tool !in ids }.keys.toList().onEach { id ->
                running.remove(id)?.job?.cancel()
                markAnswered(id)
            }
        }
        for (id in stopped) scope.launch { answer(id, null, SWITCHED_OFF) }
    }

    private suspend fun answer(requestId: String, result: JsonElement?, error: String?, wait: Long = ANSWER_WAIT_MS) {
        try {
            // A call rung from a push may end while Kyllene can't reach the gateway: the answer waits for it, trying
            // again every few seconds rather than sitting out a long backoff once the network is back.
            if (connection.state.value !is ConnectionState.Connected) {
                withTimeoutOrNull(wait) {
                    while (connection.state.value !is ConnectionState.Connected) {
                        connection.retry()
                        withTimeoutOrNull(ANSWER_RETRY_MS) { connection.state.first { it is ConnectionState.Connected } }
                    }
                } ?: return
            }
            val me = identity()
            api.phoneAnswer(me.id, me.key, requestId, result, error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The gateway will tell the agent the phone didn't answer.
        }
    }

    /**
     * The id and secret this phone uses for phone tools, made the first time they're needed. Kept in one
     * entry: losing it loses both, and a fresh pair binds again instead of being refused for a mismatch.
     */
    private suspend fun identity(): Identity = identityLock.withLock {
        store.get(KEY_IDENTITY)?.let { stored ->
            runCatching { HermesJson.decodeFromString(Identity.serializer(), stored) }.getOrNull()?.let { return@withLock it }
        }
        val made = Identity(hex(ID_BYTES), hex(KEY_BYTES))
        store.put(KEY_IDENTITY, HermesJson.encodeToString(Identity.serializer(), made))
        made
    }

    private fun hex(bytes: Int) = secureRandomBytes(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** Whether this gateway was told about tools, per gateway: each keeps its own list. */
    private suspend fun reportedKey() = KEY_REPORTED + (gateways.current()?.gatewayUrl?.value ?: "")

    private fun toolName(payload: JsonElement?): String? =
        ((payload as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull

    companion object {
        const val TOOL_PREFIX = "phone_"

        /** The requests that start a turn: `prompt.submit`, `prompt.btw` and the like. */
        const val TURN_PREFIX = "prompt."

        /** A send this much older than the start of telling is replayed history, not the turn about to run. */
        const val REPLAY_WINDOW_MS = 5_000L

        /** For chats this phone doesn't watch: how long a call may wait before the phone looks. */
        const val POLL_MS = 15_000L

        /** After a chat's `phone_*` tool starts: when to look for its call. */
        val EVENT_DRAINS_MS = longArrayOf(300, 700, 1_500, 3_000)

        /** Between tries of a report that failed. */
        val RETRY_MS = longArrayOf(2_000, 5_000, 15_000, 30_000, 60_000)

        /** A change waits for the user; the gateway gives up after two minutes. */
        const val RUN_LIMIT_MS = 125_000L

        /** Stopped this long before the gateway's deadline, for the answer to get there. */
        const val DEADLINE_MARGIN_MS = 8_000L

        /** The least an answer waits for the way to the gateway, when there's none. */
        const val ANSWER_WAIT_MS = 20_000L

        /** How often a waiting answer asks the connection to try again. */
        const val ANSWER_RETRY_MS = 3_000L

        /** How far the phone's clock may be off the gateway's before a ring push counts as late. */
        const val PUSH_SKEW_S = 30L
        private val REQUEST_ID = Regex("^[0-9a-f]{32}$")
        const val MAX_RECENT = 20
        const val TOO_LONG = "The phone took too long."
        const val SWITCHED_OFF = "The user turned this tool off on the phone."
        const val UNKNOWN_TOOL = "This version of Kyllene doesn't have that tool; the user can update Kyllene."
        private val WAITS_FOR_USER = setOf(State.NotInstalled, State.Outdated)
        private const val MAX_ANSWERED = 200
        private const val ID_BYTES = 16
        private const val KEY_BYTES = 32
        private const val KEY_IDENTITY = "phone.identity"
        private const val KEY_REPORTED = "phone.reported."
    }
}
