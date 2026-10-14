package dev.hermeskotlin.core.profiles

import dev.hermeskotlin.core.rpc.JsonRpcClient
import dev.hermeskotlin.core.rpc.RpcException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Writes [block] as the profile [name]'s `ui_meta` [key], the way Desktop writes it: the gateway merges `ui_meta`
 * per top-level key, so the whole block goes. Named by the [revision] it was read at (null: the gateway keeps no
 * revisions), so a concurrent edit from another device isn't overwritten. Answers false when one got there
 * first: read again and retry. A write the gateway refuses outright is an [RpcException] saying [refused].
 */
internal suspend fun JsonRpcClient.writeUiMeta(name: String, key: String, block: JsonObject, revision: Int?, refused: String): Boolean {
    val reply = request(
        "profiles.configure",
        buildJsonObject {
            put("name", name)
            put("ui_meta", buildJsonObject { put(key, block) })
            revision?.let { put("ui_meta_expected_revisions", buildJsonObject { put(key, it) }) }
        },
    ) as? JsonObject
    val applied = reply?.get("applied") as? JsonObject
    if (applied?.get("ui_meta_conflicts") != null) return false
    if ((applied?.get("ui_meta") as? JsonPrimitive)?.booleanOrNull == false) throw RpcException(0, refused)
    return true
}

/** How many times a `ui_meta` write that keeps losing races is read and tried again. */
internal const val UI_META_ATTEMPTS = 4
