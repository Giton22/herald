package dev.hermeskotlin.core.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigCatalogTest {

    private val schema = ConfigSchema(
        fields = mapOf(
            "approvals.mode" to ConfigField("select", "Dangerous command approval mode", "security", options("manual", "smart", "off")),
            "checkpoints.enabled" to ConfigField("boolean", "Checkpoints → Enabled", "agent"),
            "agent.max_turns" to ConfigField("number", "Agent → Max Turns", "agent"),
            "agent.disabled_toolsets" to ConfigField("list", "Agent → Disabled Toolsets", "agent"),
            "timezone" to ConfigField("select", "IANA timezone", "general", options("America/New_York", "Europe/Berlin"), clearable = true, searchable = true),
            "tts.provider" to ConfigField("select", "Text-to-speech provider", "tts", options("edge", "openai", "elevenlabs")),
            "tts.openai.voice" to ConfigField("string", "OpenAI voice", "tts"),
            "tts.openai.model" to ConfigField("string", "OpenAI model", "tts"),
            "tts.edge.voice" to ConfigField("string", "Edge voice", "tts"),
            "stt.local.model" to ConfigField("select", "Local model", "stt", options("base", "small")),
            "stt.enabled" to ConfigField("boolean", "", "stt"),
            "display.personality" to ConfigField("string", "", "display"),
            "logging.level" to ConfigField("select", "Log level for agent.log", "logging", options("DEBUG", "INFO")),
        ),
        categoryOrder = listOf("general", "agent", "security"),
    )

    private fun options(vararg values: String) = values.map(::JsonPrimitive)

    private fun config(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun section(id: String) = ConfigCatalog.sections.first { it.id == id }

    @Test
    fun aSectionShowsItsKeysWithDesktopsWordsAndControls() {
        val values = config("""{"approvals":{"mode":"smart","timeout":60},"checkpoints":{"enabled":false},"command_allowlist":["ls","git status"]}""")

        val entries = ConfigCatalog.entries(section("safety"), schema, values).associateBy { it.key }

        // In the schema, or at least in the config; the rest of the section is left out.
        assertEquals(setOf("approvals.mode", "approvals.timeout", "checkpoints.enabled", "command_allowlist"), entries.keys)
        assertEquals("Approval mode", entries.getValue("approvals.mode").label)
        assertEquals(ConfigControl.Choice(listOf("manual", "smart", "off")), entries.getValue("approvals.mode").control)
        assertEquals(ConfigControl.Toggle, entries.getValue("checkpoints.enabled").control)
        // Not in the schema: its control comes from the value.
        assertEquals(ConfigControl.Number, entries.getValue("approvals.timeout").control)
        assertEquals(ConfigControl.Lines, entries.getValue("command_allowlist").control)
    }

    @Test
    fun aDescriptionThatOnlyRepeatsTheKeyIsLeftOut() {
        val values = config("""{"agent":{"max_turns":90}}""")

        val turns = ConfigCatalog.entry("agent.max_turns", schema, values)!!
        val level = ConfigCatalog.entry("logging.level", schema, values)!!

        assertEquals("Max agent steps", turns.label)
        assertEquals("Most tool-calling steps before Hermes stops a run.", turns.description)
        assertEquals("Level", level.label)
        assertEquals("Log level for agent.log", level.description)
        assertNull(ConfigCatalog.entry("agent.disabled_toolsets", schema, values)!!.description)
    }

    @Test
    fun voiceShowsOnlyThePickedProvidersFields() {
        val values = config("""{"tts":{"provider":"openai","openai":{"voice":"nova","model":"gpt-4o-mini-tts"},"edge":{"voice":"en-US-AriaNeural"}},"stt":{"enabled":false,"local":{"model":"base"}}}""")

        val keys = ConfigCatalog.entries(section("voice"), schema, values).map { it.key }

        assertTrue("tts.openai.voice" in keys)
        assertFalse("tts.edge.voice" in keys)
        // Speech to text is off, so its provider's fields go too.
        assertFalse("stt.local.model" in keys)
        assertTrue("stt.enabled" in keys)
    }

    @Test
    fun anUnsetProviderShowsHermessDefault() {
        assertTrue(ConfigCatalog.voiceFieldVisible("tts.edge.voice", config("""{"tts":{"provider":""}}""")))
        assertTrue(ConfigCatalog.voiceFieldVisible("stt.local.model", config("""{"stt":{"enabled":true}}""")))
        assertFalse(ConfigCatalog.voiceFieldVisible("stt.groq.model", config("""{"stt":{"enabled":true}}""")))
    }

    @Test
    fun voicesAndModelsTakeNamesOffTheList() {
        val values = config("""{"tts":{"provider":"openai","openai":{"voice":"my-clone","model":"gpt-4o-mini-tts"}}}""")

        val voice = assertIs<ConfigControl.Choice>(ConfigCatalog.entry("tts.openai.voice", schema, values)!!.control)

        assertTrue(voice.other)
        // A value set by hand stays pickable.
        assertEquals("my-clone", voice.options.last())
        assertTrue("marin" in voice.options)
    }

    @Test
    fun tts1OnlyOffersTheVoicesItKnows() {
        val values = config("""{"tts":{"openai":{"model":"tts-1","voice":"nova"}}}""")

        val voice = assertIs<ConfigControl.Choice>(ConfigCatalog.entry("tts.openai.voice", schema, values)!!.control)

        assertFalse("marin" in voice.options)
        assertTrue("nova" in voice.options)
    }

    @Test
    fun commandProvidersFromTheConfigArePickable() {
        val values = config(
            """{"tts":{"provider":"edge","providers":{"my-piper":{"type":"command","command":"piper --out {out}"},"EDGE":{"command":"x"},"half":{"type":"command"}},
               "legacy":{"command":"say {text}"}}}""",
        )

        val provider = assertIs<ConfigControl.Choice>(ConfigCatalog.entry("tts.provider", schema, values)!!.control)

        assertEquals(listOf("edge", "openai", "elevenlabs", "my-piper", "legacy"), provider.options)
    }

    @Test
    fun personalitiesIncludeTheConfigsOwnFoldedAsHermesFoldsThem() {
        val values = config("""{"display":{"personality":"rin"},"agent":{"personalities":{" Rin ":"…","default":"…"}},"personalities":{"Coach":"…"}}""")

        val personality = assertIs<ConfigControl.Choice>(ConfigCatalog.entry("display.personality", schema, values)!!.control)

        assertEquals("", personality.options.first())
        assertTrue("helpful" in personality.options)
        assertEquals(listOf("coach", "rin"), personality.options.takeLast(2))
        assertFalse("default" in personality.options)
    }

    @Test
    fun aLongOrClearableChoiceGetsASearchAndANotSet() {
        val zone = assertIs<ConfigControl.Choice>(ConfigCatalog.entry("timezone", schema, config("""{"timezone":""}"""))!!.control)

        assertTrue(zone.searchable)
        assertEquals(listOf("", "America/New_York", "Europe/Berlin"), zone.options)
    }

    @Test
    fun searchMatchesEveryWordAcrossKeyLabelAndDescription() {
        val values = config("""{"approvals":{"mode":"manual"},"logging":{"level":"INFO"},"checkpoints":{"enabled":true}}""")

        assertEquals(listOf("approvals.mode"), ConfigCatalog.search("approval mode", schema, values).map { it.key })
        assertEquals(listOf("logging.level"), ConfigCatalog.search("agent.log", schema, values).map { it.key })
        // Curated first: the checkpoint switch lives in Safety.
        assertEquals("checkpoints.enabled", ConfigCatalog.search("checkpoint", schema, values).first().key)
        assertTrue(ConfigCatalog.search("   ", schema, values).isEmpty())
    }

    @Test
    fun categoriesFollowTheGatewaysOrderThenTheAlphabet() {
        val ids = ConfigCatalog.categories(schema).map { it.category }

        assertEquals(listOf("general", "agent", "security", "display", "logging", "stt", "tts"), ids)
        assertEquals("Logging", ConfigCatalog.categories(schema).first { it.category == "logging" }.label)
    }

    @Test
    fun aCategoryPageListsAllItsFields() {
        val page = ConfigCatalog.categories(schema).first { it.category == "agent" }

        val keys = ConfigCatalog.entries(page, schema, config("{}")).map { it.key }

        assertEquals(listOf("checkpoints.enabled", "agent.max_turns", "agent.disabled_toolsets"), keys)
    }

    @Test
    fun aCategoryReadsInRunsUnderTheirParents() {
        val entries = listOf("toolsets", "auxiliary.vision.model", "auxiliary.vision.timeout", "auxiliary.compression.timeout")
            .map { ConfigEntry(it, it, null, null, ConfigControl.Text) }

        val groups = ConfigCatalog.grouped(entries)

        assertEquals(listOf("", "auxiliary.vision", "auxiliary.compression"), groups.map { it.first })
        assertEquals(2, groups[1].second.size)
        assertEquals("Auxiliary › Vision", ConfigCatalog.groupLabel("auxiliary.vision"))
        assertEquals("TTS › ElevenLabs", ConfigCatalog.groupLabel("tts.elevenlabs"))
        assertEquals("API max retries", ConfigCatalog.prettyName("api_max_retries"))
    }

    @Test
    fun aNumberTheSchemaCallsTextIsStillEditedAsANumber() {
        val schema = ConfigSchema(mapOf("agent.max_turns" to ConfigField("string", "Agent → Max Turns", "agent")))

        assertEquals(ConfigControl.Number, ConfigCatalog.entry("agent.max_turns", schema, config("""{"agent":{"max_turns":150}}"""))!!.control)
        assertEquals(ConfigControl.Text, ConfigCatalog.entry("agent.max_turns", schema, config("""{"agent":{"max_turns":"150"}}"""))!!.control)
    }

    @Test
    fun anUnsetValueIsNoNumber() {
        // The gateway sends null for a field with no value (x_search.reasoning_effort): it's typed as words.
        val schema = ConfigSchema(mapOf("x_search.reasoning_effort" to ConfigField("string", "", "x_search")))

        assertEquals(ConfigControl.Text, ConfigCatalog.entry("x_search.reasoning_effort", schema, config("""{"x_search":{"reasoning_effort":null}}"""))!!.control)
    }

    @Test
    fun aChoiceOfNumbersSavesANumber() {
        val schema = ConfigSchema(mapOf("tts.xai.optimize_streaming_latency" to ConfigField("select", "", "tts", listOf(0, 1, 2).map(::JsonPrimitive))))

        val latency = assertIs<ConfigControl.Choice>(ConfigCatalog.entry("tts.xai.optimize_streaming_latency", schema, config("""{"tts":{"xai":{"optimize_streaming_latency":2}}}"""))!!.control)

        assertEquals(listOf("0", "1", "2"), latency.options)
        assertEquals(JsonPrimitive(1), latency.valueOf("1"))
        assertTrue(sameConfigValue(JsonPrimitive(2), latency.valueOf("2")))
    }

    @Test
    fun aChoiceListsEachOptionOnce() {
        // The picker keys its rows by option; one twice would crash it.
        val field = ConfigField("select", "", "x", listOf(JsonNull, JsonPrimitive(""), JsonPrimitive("a"), JsonPrimitive("a")), clearable = true)

        val choice = assertIs<ConfigControl.Choice>(ConfigCatalog.entry("x.mode", ConfigSchema(mapOf("x.mode" to field)), config("{}"))!!.control)

        assertEquals(listOf("", "a"), choice.options)
    }

    @Test
    fun notSetSavesTheSchemasOwnEmpty() {
        val field = ConfigField("select", "", "x", listOf(JsonNull, JsonPrimitive(true), JsonPrimitive(false)))

        val choice = assertIs<ConfigControl.Choice>(ConfigCatalog.entry("x.flag", ConfigSchema(mapOf("x.flag" to field)), config("""{"x":{"flag":true}}"""))!!.control)

        assertEquals(JsonNull, choice.valueOf(""))
        assertEquals(JsonPrimitive(false), choice.valueOf("false"))
        // With "" among the options, "" it is.
        val both = ConfigField("select", "", "x", listOf(JsonNull, JsonPrimitive(""), JsonPrimitive("a")))
        assertEquals(JsonPrimitive(""), assertIs<ConfigControl.Choice>(ConfigCatalog.entry("x.mode", ConfigSchema(mapOf("x.mode" to both)), config("{}"))!!.control).valueOf(""))
    }

    @Test
    fun withoutDropsOneKey() {
        val values = config("""{"tts":{"provider":"edge","openai":{"voice":"nova"}}}""")

        assertEquals(config("""{"tts":{"provider":"edge","openai":{}}}"""), values.without("tts.openai.voice"))
        assertEquals(values, values.without("stt.provider"))
    }

    @Test
    fun withAndAtWalkDottedPaths() {
        val values = config("""{"tts":{"provider":"edge","openai":{"voice":"nova"}}}""")

        val changed = values.with("tts.openai.voice", JsonPrimitive("alloy"))

        assertEquals(JsonPrimitive("alloy"), changed.at("tts.openai.voice"))
        assertEquals(JsonPrimitive("edge"), changed.at("tts.provider"))
        assertNull(changed.at("tts.openai.voice.deeper"))
        assertEquals(config("""{"a":{"b":{"c":[]}}}"""), configPatch("a.b.c", JsonArray(emptyList())))
    }
}
