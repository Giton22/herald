package dev.hermeskotlin.core.storage

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommitStringTest {
    @Test fun commitResultAndPayloadArePropagated() {
        var committed = false
        var written: Pair<String, String>? = null
        var result = false
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putString" -> { written = args!![0] as String to args[1] as String; editor }
                "commit" -> { committed = true; result }
                else -> error("unexpected editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            if (method.name == "edit") editor else error("unexpected preferences call: ${method.name}")
        } as SharedPreferences
        assertFalse(commitString(prefs, "guard", "encrypted"))
        assertTrue(committed)
        assertEquals("guard" to "encrypted", written)
        result = true
        assertTrue(commitString(prefs, "guard", "encrypted"))
    }
}
