package dev.hermeskotlin.core.screen

import dev.hermeskotlin.core.auth.sha256
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.rpc.GatewayEvent
import dev.hermeskotlin.core.rpc.RpcException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** How far the screen has come. */
enum class ScreenStage {
    /** Nothing has been asked of the gateway yet. */
    Idle,

    /** The gateway has been asked where the screen is; the viewer is opening it. */
    Opening,

    /** The viewer is showing the screen. */
    Connected,

    /** The viewer could not open it, or it closed again. The reason says which. */
    Failed,
}

/**
 * Where the viewer opens the screen.
 *
 * The address carries the gateway's own single-use ticket, so it is worth nothing once it has been used and
 * it is not a credential of its own: the gateway checks the ticket and then the client's identity.
 */
data class ScreenConnection(
    /** The address of the display stream, with the ticket in it. */
    val url: String,
    /** The id the gateway minted for this client; the lease is held in its name. */
    val viewerId: String,
)

/** What the app knows about a bot's screen. */
data class ScreenState(
    /** The gateway's own snapshot, or null when it has no screen for this bot. */
    val status: DisplayStatus? = null,
    /** Who holds the screen: the agent, or a human. */
    val lease: DisplayLease? = null,
    /** The id the gateway minted for this client; the lease is held in its name. */
    val viewerId: String? = null,
    /** Where the viewer should open the screen, or null when nothing is open. */
    val connection: ScreenConnection? = null,
    /** How far the screen has come. */
    val stage: ScreenStage = ScreenStage.Idle,
    /** False when the gateway is too old to have a screen at all. */
    val supported: Boolean = true,
    /** Why something didn't work, in the user's words. */
    val error: String? = null,
) {
    /**
     * Whether this client is the one holding the screen.
     *
     * The gateway says a human holds it and names them by a hash of their viewer id; this client knows its
     * own viewer id, and so its own hash, and compares the two.
     */
    val holding: Boolean get() = holdsTheScreen(lease, viewerId)
}

/**
 * Whether the lease names the client with [viewerId].
 *
 * The holder's viewer id is a capability and never leaves the gateway, only a hash of it, so a client that
 * knows its own id can tell whether the lease it is told about is its own.
 */
fun holdsTheScreen(lease: DisplayLease?, viewerId: String?): Boolean {
    if (lease == null || !lease.heldByHuman) return false
    val mine = viewerId?.let(::viewerHash) ?: return false
    // A gateway that names no one leaves the client where it was.
    return lease.viewerHash == null || lease.viewerHash == mine
}

/**
 * A bot's screen, and this app's hold on it.
 *
 * The gateway keeps the screen on its own host, so the app watches it over the display stream and asks
 * the gateway for control when the user takes over. The gateway decides what reaches the screen: it
 * drops the keyboard and pointer of a viewer that does not hold the lease, so the viewer is told what
 * it may do and the gateway has the last word.
 *
 * The app holds no part of the protocol: the viewer draws the screen and sends what the user does, and
 * the app only asks the gateway where to open it, who holds it, and for the lease.
 *
 * A lost link does not hand control back. The human may be half-way through a sign-in, so the gateway
 * keeps them as the holder and this client comes back with the same viewer id to find the lease waiting.
 */
class ScreenSession(
    private val profile: String,
    private val connection: GatewayConnection,
    private val gateway: GatewayUrl,
    private val scope: CoroutineScope,
) {

    private val api = DisplayApi(connection)

    /**
     * The session's own work: the events it watches and the opening in flight.
     *
     * It is a child of the scope it was given, so a session that is put away takes its watchers with it. A
     * session that was left behind keeps asking the gateway about a bot the user is no longer looking at.
     */
    private val work = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private val _state = MutableStateFlow(ScreenState())
    val state: StateFlow<ScreenState> = _state.asStateFlow()

    private var viewerId: String? = null

    /** The opening in flight. It is the session's, so closing the pane can call it off. */
    private var opening: Job? = null

    /**
     * How many times the pane has gone away.
     *
     * Work that was in flight when it did gives up when it comes back: a gateway that answers a start for a
     * pane that is no longer there must not open the screen behind the user's back.
     */
    private var generation = 0

    init {
        work.launch {
            connection.events.collect { event ->
                // The gateway's events are for every profile on the connection; this session is about one.
                if (!event.isAboutProfile(profile)) return@collect
                when (event.type) {
                    "display.lease" -> refreshLease()
                    "display.status" -> refresh()
                    else -> Unit
                }
            }
        }
    }

    /** Reads the gateway's state of this bot's screen. */
    suspend fun refresh() {
        val mine = generation
        val status = ask { api.status(profile) }.getOrElse { return }
        // The pane went away while the gateway was thinking: its answer is about a screen nobody watches.
        if (mine != generation) return
        if (status == null) {
            _state.update { it.copy(supported = false, status = null, lease = null) }
            return
        }
        // A refresh says how the screen is, not whether the last attempt worked: a failure stays until
        // an attempt after it succeeds.
        _state.update { it.copy(supported = true, status = status, lease = status.lease) }
    }

    /** Starts the screen and opens it. */
    suspend fun start() {
        val mine = generation
        val status = ask { api.start(profile) }.getOrElse { return }
        // The pane went away while the gateway was starting the screen: it is not opened for nobody.
        if (mine != generation) return
        if (status == null) {
            _state.update { it.copy(supported = false, error = "This gateway has no Bot Screen.") }
            return
        }
        _state.update { it.copy(supported = true, status = status, lease = status.lease) }
        open()
    }

    /**
     * Opens the screen. The gateway mints a ticket for it and a viewer id to go with it; that id is what
     * the lease is held in, so it is kept for the life of the session.
     *
     * The whole opening belongs to the session, so closing the pane calls it off: a ticket that arrives
     * after the pane is gone opens nothing. An opening already in flight is called off and replaced.
     */
    fun open() {
        opening?.cancel()
        opening = work.launch { openNow() }
    }

    /** The viewer is showing the screen. */
    fun viewerConnected() {
        _state.update { it.copy(stage = ScreenStage.Connected, error = null) }
    }

    /** The viewer could not open the screen. Its ticket is spent, so the screen is closed again. */
    fun viewerFailed(reason: String) {
        _state.update { it.copy(stage = ScreenStage.Failed, connection = null, error = reason) }
    }

    /** The viewer closed the screen again. */
    fun viewerClosed() {
        _state.update { it.copy(stage = ScreenStage.Failed, connection = null, error = "The screen closed.") }
    }

    /** Puts the screen away: the viewer is closed and any opening in flight is called off. */
    fun close() {
        generation++
        opening?.cancel()
        opening = null
        _state.update { it.copy(stage = ScreenStage.Idle, connection = null) }
    }

    /**
     * The session is done with: it is not coming back.
     *
     * Its watchers stop with it, so a bot the user has left asks the gateway nothing more.
     */
    fun dispose() {
        close()
        work.cancel()
    }

    /**
     * Takes over: the user drives the bot's screen until they hand back.
     *
     * True when the gateway confirmed it; false when it refused or never answered, and the app keeps
     * what it knew.
     */
    suspend fun takeOver(): Boolean {
        val id = viewerId
        if (id == null) {
            _state.update { it.copy(error = "The screen isn't open yet.") }
            return false
        }
        val lease = ask { api.acquire(profile, id) }.getOrElse { return false }
        if (lease == null) {
            _state.update { it.copy(error = "The gateway refused the takeover.") }
            return false
        }
        _state.update { it.copy(lease = lease, error = null) }
        return true
    }

    /**
     * Hands the screen back to the agent.
     *
     * True when the gateway confirmed it. A refusal leaves the lease as it was: the gateway may still
     * consider this viewer in control, and the app says so rather than the other way round.
     */
    suspend fun handBack(): Boolean {
        val lease = ask { api.release(profile, viewerId) }.getOrElse { return false }
        if (lease == null) {
            _state.update { it.copy(error = "The gateway refused to hand the screen back.") }
            return false
        }
        _state.update { it.copy(lease = lease, error = null) }
        return true
    }

    /** Where the viewer should open the screen, or null when nothing is open. */
    val stream: ScreenConnection? get() = _state.value.connection

    private suspend fun openNow() {
        _state.update { it.copy(stage = ScreenStage.Opening, error = null) }
        val ticket = ask { api.observe(profile, viewerId) }.getOrElse {
            // The call never got there or the gateway said no: there is nothing to open, and the way
            // back is to try again.
            _state.update { it.copy(stage = ScreenStage.Failed) }
            return
        }
        if (ticket == null) {
            _state.update { it.copy(stage = ScreenStage.Failed, error = "This gateway has no Bot Screen.") }
            return
        }
        viewerId = ticket.viewerId
        val url = gateway.siblingSocketUrl(ticket.path) + "?display_ticket=" + ticket.ticket
        _state.update {
            it.copy(
                viewerId = ticket.viewerId,
                connection = ScreenConnection(url, ticket.viewerId),
                stage = ScreenStage.Opening,
                error = null,
            )
        }
    }

    private suspend fun refreshLease() {
        val status = ask { api.status(profile) }.getOrElse { return } ?: return
        _state.update { it.copy(lease = status.lease, status = status) }
    }

    /**
     * Asks the gateway something, and keeps the answer.
     *
     * The link to the gateway drops now and then — the phone is on Wi-Fi, and the gateway may restart — and a
     * call that never got there says nothing about the screen. The pane keeps what it last knew and says why
     * it stopped; the app does not fall over.
     */
    private suspend fun <T> ask(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _state.update { it.copy(error = failureMessage(e)) }
        Result.failure(e)
    }

    /** What the pane says when a call to the gateway didn't work. */
    private fun failureMessage(e: Throwable): String = when (e) {
        // The call never got there: the link is down, not the screen.
        is GatewayUnreachable -> e.message
        // The gateway's own words: it answered, and it said no.
        is RpcException -> e.message
        else -> e.message ?: "The gateway didn't answer."
    }
}

/**
 * The gateway's own hash of a viewer id: the first twelve hex digits of its sha256.
 *
 * The gateway never gives the id of the holder away, only this much of it, so a client that knows its own
 * id can tell whether the lease it is told about is its own.
 */
internal fun viewerHash(viewerId: String): String =
    sha256(viewerId.encodeToByteArray())
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        .take(12)

/**
 * Whether a gateway event is about this profile's screen.
 *
 * One connection serves every profile on the gateway, and the events name the profile they are about. An
 * event that names none is taken as ours: a gateway that does not say is not saying it is about somebody
 * else, and a screen that never refreshes its lease is worse than one that refreshes it too often.
 */
internal fun GatewayEvent.isAboutProfile(profile: String): Boolean {
    val named = (payload as? JsonObject)
        ?.get("profile_key")
        ?.let { (it as? JsonPrimitive)?.contentOrNull }
    return named == null || named == profile
}
