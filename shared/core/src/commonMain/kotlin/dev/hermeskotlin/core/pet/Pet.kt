package dev.hermeskotlin.core.pet

import dev.hermeskotlin.core.chat.asObjectList
import dev.hermeskotlin.core.chat.boolean
import dev.hermeskotlin.core.chat.double
import dev.hermeskotlin.core.chat.int
import dev.hermeskotlin.core.chat.string
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.rpc.RpcException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** What the pet is acting out; each maps to a row of its spritesheet (Desktop's PetState). */
enum class PetState(internal val aliases: List<String>) {
    Idle(listOf("idle")),
    Wave(listOf("wave", "waving")),
    Jump(listOf("jump", "jumping")),
    Run(listOf("run", "running")),
    Failed(listOf("failed")),
    Review(listOf("review")),
    Waiting(listOf("waiting")),
}

/**
 * The active pet's spritesheet and frame geometry (`pet.info`): a grid with one row per state in
 * [stateRows], [frameW]×[frameH] cells, stepped across [loopMs].
 */
class PetSprite(
    val slug: String,
    val displayName: String,
    val sheet: ByteArray,
    val revision: String?,
    val frameW: Int,
    val frameH: Int,
    val loopMs: Int,
    /** The gateway's display scale for the sprite (Desktop draws it at frame size × scale). */
    val scale: Double,
    private val framesPerState: Int,
    private val framesByState: Map<String, Int>,
    private val framesByRow: Map<String, Int>,
    private val stateRows: List<String>,
) {
    /** The row to draw for [state] and how many real frames it has; a state with none falls back to idle. */
    fun rowFor(state: PetState): Pair<Int, Int> {
        fun index(s: PetState) = s.aliases.firstNotNullOfOrNull { alias -> stateRows.indexOf(alias).takeIf { it >= 0 } } ?: 0
        val real = framesByState[state.name.lowercase()] ?: state.aliases.firstNotNullOfOrNull { framesByRow[it] } ?: framesPerState
        if (real > 0) return index(state) to real
        return index(PetState.Idle) to (framesByState["idle"] ?: framesPerState).coerceAtLeast(1)
    }

    companion object {
        /** Mirrors agent.pet.constants (Petdex's current layout), for sheets that leave fields out. */
        val DEFAULT_ROWS = listOf("idle", "running-right", "running-left", "waving", "jumping", "failed", "waiting", "running", "review")
        const val DEFAULT_FRAME_W = 192
        const val DEFAULT_FRAME_H = 208
        const val DEFAULT_FRAMES = 6
        const val DEFAULT_LOOP_MS = 1100
        const val DEFAULT_SCALE = 0.33
    }
}

/** One pet in the Petdex gallery, installed on the gateway or not. */
data class PetChoice(
    val slug: String,
    val displayName: String,
    val installed: Boolean,
    val spritesheetUrl: String,
    val generated: Boolean,
)

data class PetGallery(val enabled: Boolean, val active: String, val pets: List<PetChoice>)

/**
 * The gateway's pet (Petdex mascots): which one is active, its spritesheet, and the gallery to adopt
 * from. Pets belong to a profile, so every call names [profile] when one is picked.
 */
class PetApi(private val connection: GatewayConnection) {

    /**
     * The active pet, or null when the pet display is off. Passing the [current] sprite skips
     * resending a spritesheet that hasn't changed.
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun active(profile: String?, current: PetSprite? = null): PetSprite? {
        val info = client().request(
            "pet.info",
            buildJsonObject {
                profile?.let { put("profile", it) }
                current?.revision?.let { put("knownRevision", it) }
            },
            timeoutMs = SHEET_TIMEOUT_MS,
        ) as? JsonObject
        if (info.boolean("enabled") != true) return null
        val slug = info.string("slug") ?: return null
        val sheet = if (info.boolean("spritesheetUnchanged") == true && current?.slug == slug) {
            current.sheet
        } else {
            info.string("spritesheetBase64")?.let { Base64.decode(it) } ?: return null
        }
        return PetSprite(
            slug = slug,
            displayName = info.string("displayName")?.takeIf { it.isNotBlank() } ?: slug,
            sheet = sheet,
            revision = info.string("spritesheetRevision"),
            frameW = info.int("frameW") ?: PetSprite.DEFAULT_FRAME_W,
            frameH = info.int("frameH") ?: PetSprite.DEFAULT_FRAME_H,
            loopMs = info.int("loopMs") ?: PetSprite.DEFAULT_LOOP_MS,
            scale = info.double("scale") ?: PetSprite.DEFAULT_SCALE,
            framesPerState = info.int("framesPerState") ?: PetSprite.DEFAULT_FRAMES,
            framesByState = info.counts("framesByState"),
            framesByRow = info.counts("framesByRow"),
            stateRows = (info?.get("stateRows") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.takeIf { it.isNotEmpty() } ?: PetSprite.DEFAULT_ROWS,
        )
    }

    suspend fun gallery(profile: String?): PetGallery {
        val result = client().request(
            "pet.gallery",
            buildJsonObject { profile?.let { put("profile", it) } },
            timeoutMs = SHEET_TIMEOUT_MS,
        ) as? JsonObject
        val pets = result?.get("pets").asObjectList().mapNotNull { row ->
            val slug = row.string("slug") ?: return@mapNotNull null
            PetChoice(
                slug = slug,
                displayName = row.string("displayName")?.takeIf { it.isNotBlank() } ?: slug,
                installed = row.boolean("installed") == true,
                spritesheetUrl = row.string("spritesheetUrl").orEmpty(),
                generated = row.boolean("generated") == true,
            )
        }
        return PetGallery(result.boolean("enabled") == true, result.string("active").orEmpty(), pets)
    }

    /** The pet's idle frame as image bytes, for the gallery. */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun thumbnail(pet: PetChoice, profile: String?): ByteArray? {
        val result = client().request(
            "pet.thumb",
            buildJsonObject {
                put("slug", pet.slug)
                if (!pet.installed && pet.spritesheetUrl.isNotEmpty()) put("url", pet.spritesheetUrl)
                profile?.let { put("profile", it) }
            },
            timeoutMs = SHEET_TIMEOUT_MS,
        ) as? JsonObject
        val uri = result.string("dataUri")?.takeIf { result.boolean("ok") == true } ?: return null
        return runCatching { Base64.decode(uri.substringAfter("base64,")) }.getOrNull()
    }

    /** Adopts [slug] (installing it on the gateway first if needed) and turns the pet display on. */
    suspend fun select(slug: String, profile: String?): String {
        val result = client().request(
            "pet.select",
            buildJsonObject {
                put("slug", slug)
                profile?.let { put("profile", it) }
            },
            timeoutMs = SHEET_TIMEOUT_MS,
        ) as? JsonObject
        if (result.boolean("ok") != true) throw RpcException(0, "Couldn't adopt $slug.")
        return result.string("displayName")?.takeIf { it.isNotBlank() } ?: slug
    }

    /** Turns the pet display off (for every client of this profile, like Desktop's picker). */
    suspend fun disable(profile: String?) {
        client().request("pet.disable", buildJsonObject { profile?.let { put("profile", it) } })
    }

    private fun client() = (connection.state.value as? ConnectionState.Connected)?.client
        ?: throw RpcException(0, "Not connected to the gateway.")

    private companion object {
        /** Adopting downloads the spritesheet onto the gateway first. */
        const val SHEET_TIMEOUT_MS = 60_000L
    }
}

private fun JsonObject?.counts(key: String): Map<String, Int> =
    (this?.get(key) as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.intOrNull?.let { k to it } }.toMap()

/**
 * Desktop's pose priorities (store/pet.ts derivePetState): a failure or a finish shows briefly, then
 * waiting on the person, a running tool, reasoning, and any running turn.
 */
fun petStateOf(
    failed: Boolean,
    justFinished: Boolean,
    awaitingInput: Boolean,
    toolRunning: Boolean,
    reasoning: Boolean,
    busy: Boolean,
): PetState = when {
    failed -> PetState.Failed
    justFinished -> PetState.Wave
    awaitingInput -> PetState.Waiting
    busy && toolRunning -> PetState.Run
    busy && reasoning -> PetState.Review
    busy -> PetState.Run
    else -> PetState.Idle
}
