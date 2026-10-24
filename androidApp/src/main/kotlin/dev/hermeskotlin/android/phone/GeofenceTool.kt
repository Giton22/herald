package dev.hermeskotlin.android.phone

import android.content.Context
import android.location.Location
import android.location.LocationManager
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.SessionWatcher
import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.phone.PhoneToolException
import dev.hermeskotlin.core.phone.WatchedPlace
import dev.hermeskotlin.core.phone.forQuestion
import dev.hermeskotlin.core.phone.hidden
import dev.hermeskotlin.core.profiles.ProfileStore
import dev.hermeskotlin.core.profiles.ProfilesApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Locale
import java.util.UUID

/** The chat a place's message goes to: the one on this phone the agent was called from. */
data class ChatTarget(val gateway: String, val profile: String?, val storedSessionId: String, val title: String?)

/** Which chat on this phone the agent called from: the open one, or one followed in the background. */
class ChatTargets(
    private val host: ChatHost,
    private val watcher: SessionWatcher,
    private val gateways: GatewayRepository,
    private val profiles: ProfileStore,
) {
    /** The gateway Kyllene is signed in to now. */
    suspend fun gateway(): String? = gateways.current()?.gatewayUrl?.value

    /** [id] is what the gateway calls the chat: its stored id (`HERMES_UI_SESSION_ID`), or the live one on this socket. */
    suspend fun of(id: String): ChatTarget? {
        if (id.isBlank()) return null
        val gateway = gateways.current()?.gatewayUrl ?: return null
        host.session.value?.let { open ->
            val state = open.state.value
            val stored = state.storedSessionId
            if (stored != null && (stored == id || state.runtimeSessionId == id)) return ChatTarget(gateway.value, open.profile, stored, state.title)
        }
        val watched = watcher.chats.value.values.firstOrNull { it.storedId == id || it.runtimeId == id } ?: return null
        return ChatTarget(gateway.value, profiles.get(gateway), watched.storedId, watched.title)
    }
}

/**
 * `phone_geofence`: places the phone watches. Adding one asks first and shows exactly what will be sent and where,
 * and removing one asks too; listing doesn't. Watching needs precise location all the time,
 * since the place matters most while Kyllene is closed.
 */
internal class GeofenceTool(
    private val context: Context,
    private val confirmations: PhoneConfirmations,
    private val location: LocationTool,
    private val places: Geofences,
    private val chats: ChatTargets,
) {
    suspend fun run(args: JsonObject, session: String): JsonObject = when (args.string("action")) {
        "list" -> list(session)
        "remove" -> remove(args, session)
        "add" -> add(args, session)
        else -> throw PhoneToolException("action is add, list or remove.")
    }

    /**
     * The places the calling agent may see and remove: those added on the gateway Kyllene is signed in to, in the
     * calling chat's profile. A call from no chat on this phone (a scheduled job, Desktop) sees the default profile's
     * only. Another gateway's or profile's places are none of its business.
     */
    private suspend fun visible(session: String): List<WatchedPlace> {
        val target = chats.of(session)
        val gateway = target?.gateway ?: chats.gateway() ?: return emptyList()
        val profile = profileOf(target?.profile)
        return places.places.value.filter { it.gateway == gateway && profileOf(it.profile) == profile }
    }

    /** No profile picked and the one named "default" are the same profile. */
    private fun profileOf(name: String?): String? = name?.takeUnless { it == ProfilesApi.DEFAULT }

    private suspend fun list(session: String): JsonObject {
        val shown = visible(session)
        val on = places.enabled()
        return list(shown, on)
    }

    private fun list(shown: List<WatchedPlace>, on: Boolean) = buildJsonObject {
        putJsonArray("places") {
            for (place in shown) {
                addJsonObject {
                    put("id", place.id)
                    put("name", place.name)
                    put("latitude", place.latitude)
                    put("longitude", place.longitude)
                    put("radius_m", place.radiusMeters)
                    put("trigger", place.trigger)
                    put("message", place.message)
                    place.chatTitle?.let { put("chat", it) }
                }
            }
        }
        if (!on) put("note", "The Watched places tool is off, so none of these fire.")
    }

    private suspend fun remove(args: JsonObject, session: String): JsonObject {
        val id = args.string("id") ?: throw PhoneToolException("id is needed: take it from action=list.")
        val place = visible(session).firstOrNull { it.id == id } ?: throw PhoneToolException("No watched place has that id.")
        // The user allowed the place: only the user takes it back.
        val event = if (place.arriving) "arrive at" else "leave"
        confirmations.confirm("Stop watching ${forQuestion(place.name)}? Nothing is sent any more when you $event it.", TITLE_REMOVE) {
            places.remove(id)
        }
        return buildJsonObject {
            put("removed", true)
            put("name", place.name)
        }
    }

    private suspend fun add(args: JsonObject, session: String): JsonObject {
        val name = args.string("name")?.trim()?.takeIf { it.isNotEmpty() } ?: throw PhoneToolException("name is needed, such as Home.")
        val message = args.string("message")?.trim()?.takeIf { it.isNotEmpty() } ?: throw PhoneToolException("message is needed: what to send when it fires.")
        if (name.length > MAX_NAME) throw PhoneToolException("name is at most $MAX_NAME characters.")
        if (message.length > MAX_MESSAGE) throw PhoneToolException("message is at most $MAX_MESSAGE characters.")
        if (hidden(name, newlines = false) || hidden(message, newlines = true)) {
            throw PhoneToolException("name or message has hidden characters or odd spaces in it.")
        }
        // The name goes inside the "[Arrived at … · watched place]" mark: it mustn't close or fake it.
        if (name.any { it in "[]·" }) throw PhoneToolException("name can't have [, ] or · in it.")
        val trigger = args.string("trigger") ?: WatchedPlace.ENTER
        if (trigger != WatchedPlace.ENTER && trigger != WatchedPlace.EXIT) throw PhoneToolException("trigger is enter or exit.")
        val radius = args.int("radius_m") ?: DEFAULT_RADIUS
        if (radius !in MIN_RADIUS..MAX_RADIUS) throw PhoneToolException("radius_m is $MIN_RADIUS to $MAX_RADIUS.")
        if (!places.allowed()) {
            throw PhoneToolException(
                "Watching places needs precise location all the time, which Kyllene doesn't have. The user can allow it in " +
                    "Kyllene: Settings → Phone tools → Watched places.",
            )
        }
        if (context.getSystemService(LocationManager::class.java) == null) throw PhoneToolException("The phone has no location service.")
        val target = chats.of(session) ?: throw PhoneToolException(
            "A place can only be watched for a chat open on this phone, so its message has somewhere to go. Ask the user " +
                "to add it from a chat in Kyllene.",
        )
        val lat = args.double("latitude")
        val lon = args.double("longitude")
        if ((lat == null) != (lon == null)) throw PhoneToolException("latitude and longitude go together; leave both out for where the phone is now.")
        // Where the phone is goes back to the agent: that's the Location tool's, and only while it's on.
        if (lat == null && !places.locationOn()) {
            throw PhoneToolException("Give latitude and longitude: the Location tool is off, so the phone doesn't say where it is.")
        }
        if (lat != null && lon != null && (lat !in -90.0..90.0 || lon !in -180.0..180.0)) {
            throw PhoneToolException("latitude or longitude is out of range.")
        }
        // Here and now, never an old fix: a place set where the phone was hours ago would watch the wrong spot.
        val here = if (lat == null) location.locate(fresh = true, old = false) else runCatching { location.locate(fresh = false) }.getOrNull()
        val latitude = lat ?: here!!.latitude
        val longitude = lon ?: here!!.longitude
        // Where the phone is now, so Android's first report after arming isn't taken for an arrival.
        val inside = here?.let {
            val meters = FloatArray(1)
            Location.distanceBetween(it.latitude, it.longitude, latitude, longitude, meters)
            meters[0] <= radius
        }
        if (places.places.value.size >= Geofences.MAX_PLACES) {
            throw PhoneToolException("The phone already watches ${Geofences.MAX_PLACES} places. Remove one first.")
        }
        val place = WatchedPlace(
            id = UUID.randomUUID().toString().replace("-", "").take(12),
            name = name,
            latitude = latitude,
            longitude = longitude,
            radiusMeters = radius,
            trigger = trigger,
            message = message,
            gateway = target.gateway,
            profile = target.profile,
            storedSessionId = target.storedSessionId,
            chatTitle = target.title,
            addedAtMillis = System.currentTimeMillis(),
            inside = inside,
        )
        val point = String.format(Locale.ROOT, "%.5f, %.5f", latitude, longitude)
        val where = if (lat == null) "where the phone is now, $point" else point
        val chat = target.title?.takeIf { it.isNotBlank() }?.let { "\"${forQuestion(it)}\"" } ?: "this chat"
        val event = if (place.arriving) "When you arrive at" else "When you leave"
        confirmations.confirm(
            "$event ${forQuestion(name)} ($where, $radius m), Kyllene sends this to $chat as you: ${forQuestion(place.sent)}",
            TITLE,
        ) {
            if (!places.add(place)) throw PhoneToolException("The phone already watches ${Geofences.MAX_PLACES} places. Remove one first.")
        }
        return buildJsonObject {
            put("added", true)
            put("id", place.id)
            put("latitude", place.latitude)
            put("longitude", place.longitude)
            put("radius_m", radius)
            put(
                "note",
                "The user allowed it. The message is sent to this chat as the user when it fires, marked as coming from the " +
                    "place. Android checks the place every few minutes, so it can fire a little late.",
            )
        }
    }

    private companion object {
        const val MAX_NAME = 60
        const val MAX_MESSAGE = 500
        const val DEFAULT_RADIUS = 200
        const val MIN_RADIUS = 100
        const val MAX_RADIUS = 5000
        const val TITLE = "The agent wants to watch a place"
        const val TITLE_REMOVE = "The agent wants to stop watching a place"
    }
}
