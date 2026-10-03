package dev.hermeskotlin.core.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** Lenient field reads for gateway payloads: a missing or wrongly typed field is null, never an exception. */
internal fun JsonObject?.string(key: String): String? = (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject?.boolean(key: String): Boolean? = (this?.get(key) as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject?.double(key: String): Double? = (this?.get(key) as? JsonPrimitive)?.doubleOrNull
