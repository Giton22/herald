package dev.hermeskotlin.core.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigValuesTest {

    private fun entry(control: ConfigControl, value: JsonElement?, key: String = "some.key") = ConfigEntry(key, "Label", null, value, control)

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun summariesSayWhatsSet() {
        assertEquals(ConfigSummary.NotSet, entry(ConfigControl.Text, null).summary())
        assertEquals(ConfigSummary.NotSet, entry(ConfigControl.Text, JsonPrimitive("")).summary())
        assertEquals(ConfigSummary.Text("smart"), entry(ConfigControl.Text, JsonPrimitive("smart")).summary())
        assertEquals(ConfigSummary.Text("ls, git status"), entry(ConfigControl.Lines, json("""["ls","git status"]""")).summary())
        assertEquals(ConfigSummary.None, entry(ConfigControl.Lines, json("[]")).summary())
        assertEquals(ConfigSummary.Entries(2), entry(ConfigControl.Json, json("""{"a":1,"b":2}""")).summary())
        assertEquals(ConfigSummary.On, entry(ConfigControl.Toggle, JsonPrimitive(true)).summary())
        assertEquals(ConfigSummary.Off, entry(ConfigControl.Toggle, JsonPrimitive(false)).summary())
        assertEquals(
            ConfigSummary.Text("opencode-go:glm-5.3, openrouter:anthropic/claude-sonnet-4.6"),
            entry(ConfigControl.Fallbacks, json("""[{"provider":"opencode-go","model":"glm-5.3"},"openrouter/anthropic/claude-sonnet-4.6"]""")).summary(),
        )
    }

    @Test
    fun numbersAreReadAsWholeOrDecimal() {
        val e = entry(ConfigControl.Number, JsonPrimitive(90))

        assertEquals(JsonPrimitive(120L), parseConfigInput(e, " 120 ").getOrThrow())
        assertEquals(JsonPrimitive(0.75), parseConfigInput(e, "0.75").getOrThrow())
        assertEquals("“ninety” isn't a number.", parseConfigInput(e, "ninety").exceptionOrNull()?.message)
    }

    @Test
    fun anEmptiedNumberIsNeverZero() {
        // A speed or timeout of 0 means something, and null over a value reads as None, not the default.
        assertEquals("Type a number.", parseConfigInput(entry(ConfigControl.Number, JsonPrimitive(300)), "  ").exceptionOrNull()?.message)
        // One that was unset stays so.
        assertEquals(JsonNull, parseConfigInput(entry(ConfigControl.Number, JsonNull), "").getOrThrow())
    }

    @Test
    fun twoFallbacksThatReadTheSameKeepTheirOwnRouting() {
        val current = json("""[{"provider":"custom","model":"m","base_url":"http://a"},{"provider":"custom","model":"m","base_url":"http://b"}]""")
        val e = entry(ConfigControl.Fallbacks, current, key = "fallback_providers")

        assertEquals(current, parseConfigInput(e, e.editText()).getOrThrow())
    }

    @Test
    fun fallbacksThePhoneWouldNotWriteStillSave() {
        // A bare model string and an object without a provider, as written by hand.
        val current = json("""["gpt-4o-mini",{"model":"llama3"},{"provider":"openrouter","model":"x"}]""")
        val e = entry(ConfigControl.Fallbacks, current, key = "fallback_providers")

        assertEquals("gpt-4o-mini\nllama3\nopenrouter:x", e.editText())
        // One line added: the rest stay exactly as they were.
        assertEquals(
            json("""["gpt-4o-mini",{"model":"llama3"},{"provider":"openrouter","model":"x"},{"provider":"groq","model":"y"}]"""),
            parseConfigInput(e, e.editText() + "\ngroq:y").getOrThrow(),
        )
    }

    @Test
    fun linesBecomeAListWithBlanksDropped() {
        val e = entry(ConfigControl.Lines, json("""["a"]"""))

        assertEquals("a", e.editText())
        assertEquals(json("""["git status","ls -la"]"""), parseConfigInput(e, "git status\n\n  ls -la  \n").getOrThrow())
    }

    @Test
    fun fallbacksKeepTheRoutingWrittenBesideThem() {
        val current = json("""[{"provider":"custom","model":"llama3:8b","base_url":"http://box:11434/v1"},{"provider":"openrouter","model":"x"}]""")
        val e = entry(ConfigControl.Fallbacks, current, key = "fallback_providers")

        assertEquals("custom:llama3:8b\nopenrouter:x", e.editText())
        // Reordered, one dropped and one added: the kept pair keeps its base_url.
        val next = parseConfigInput(e, "opencode-go:glm-5.3\ncustom:llama3:8b").getOrThrow()

        assertEquals(
            json("""[{"provider":"opencode-go","model":"glm-5.3"},{"provider":"custom","model":"llama3:8b","base_url":"http://box:11434/v1"}]"""),
            next,
        )
        assertTrue(parseConfigInput(e, "just-a-model").isFailure)
    }

    @Test
    fun jsonMustParse() {
        val e = entry(ConfigControl.Json, json("""{"a":1}"""))

        assertEquals("{\n    \"a\": 1\n}", e.editText())
        assertEquals(json("""{"b":[1,2]}"""), parseConfigInput(e, """{"b":[1,2]}""").getOrThrow())
        assertTrue(parseConfigInput(e, "{nope").exceptionOrNull()!!.message!!.startsWith("That isn't valid JSON"))
    }

    @Test
    fun emptyingTheToolsetsIsCaught() {
        val toolsets = entry(ConfigControl.Lines, json("""["hermes-cli"]"""), key = "toolsets")

        assertTrue(clearsToolsets(toolsets, JsonArray(emptyList())))
        assertFalse(clearsToolsets(toolsets, json("""["web"]""")))
        assertFalse(clearsToolsets(entry(ConfigControl.Lines, json("[]"), key = "toolsets"), JsonArray(emptyList())))
        assertFalse(clearsToolsets(entry(ConfigControl.Lines, json("""["a"]""")), JsonArray(emptyList())))
    }

    @Test
    fun aNumberWrittenEitherWayIsTheSame() {
        assertTrue(sameConfigValue(JsonPrimitive(30), JsonPrimitive(30.0)))
        assertFalse(sameConfigValue(JsonPrimitive(30), JsonPrimitive("30")))
        assertTrue(sameConfigValue(json("""["a"]"""), json("""["a"]""")))
        assertFalse(sameConfigValue(JsonPrimitive(true), JsonPrimitive(false)))
    }
}
