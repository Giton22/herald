package dev.hermeskotlin.wearlink

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The watchapp's package.json and this module agree: the C side reads the keys from it, the phone from [PebbleKeys]. */
class PebbleAppTest {

    private val pebble = run {
        // The tests run from the module's folder; the watchapp sits at the repository's root.
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "pebbleApp/package.json") }
            .first { it.exists() }
        Json.parseToJsonElement(file.readText()).jsonObject.getValue("pebble").jsonObject
    }

    @Test
    fun theMessageKeysAreTheOnesThePhoneUses() {
        val keys = pebble.getValue("messageKeys").jsonObject.mapValues { it.value.jsonPrimitive.int }
        assertEquals(PebbleKeys.byName, keys)
    }

    @Test
    fun theUuidIsTheOneThePhoneSendsTo() {
        assertEquals(PebbleLink.APP_UUID, pebble.getValue("uuid").jsonPrimitive.content)
    }

    @Test
    fun bothHeraldBuildsMayTalkToTheWatchapp() {
        val packages = pebble.getValue("companionApp").jsonObject.getValue("android").jsonObject.getValue("apps").jsonArray
            .map { it.jsonObject.getValue("package").jsonPrimitive.content }
        assertTrue("dev.herald.android" in packages)
        assertTrue("dev.herald.android.dev" in packages)
    }
}
