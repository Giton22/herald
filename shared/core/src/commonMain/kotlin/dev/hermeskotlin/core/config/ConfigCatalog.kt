package dev.hermeskotlin.core.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** A page of settings: one of Desktop's sections ([keys]), or every field of one schema [category]. */
data class ConfigSection(val id: String, val label: String, val keys: List<String> = emptyList(), val category: String? = null)

/** How a setting is changed. */
sealed interface ConfigControl {
    /** On or off. */
    data object Toggle : ConfigControl

    /** One of [options]; `""` among them is "not set". With [other], a name off the list may be typed too (voices, models). */
    data class Choice(
        val options: List<String>,
        val searchable: Boolean = false,
        val other: Boolean = false,
        /** Options stored as numbers, booleans or null, by their text, so a pick saves `1` and not `"1"`. */
        val typed: Map<String, JsonPrimitive> = emptyMap(),
    ) : ConfigControl {
        /** What picking [option] saves. */
        fun valueOf(option: String): JsonPrimitive = typed[option] ?: JsonPrimitive(option)
    }

    data object Number : ConfigControl
    data object Text : ConfigControl

    /** A list of words or paths, one per line. */
    data object Lines : ConfigControl

    /** `fallback_providers`: one `provider:model` per line. */
    data object Fallbacks : ConfigControl

    /** Anything structured, written as JSON. */
    data object Json : ConfigControl
}

/** One setting ready to show: its words, its value and how it's changed. */
data class ConfigEntry(
    val key: String,
    val label: String,
    val description: String?,
    val value: JsonElement?,
    val control: ConfigControl,
)

/**
 * Desktop's Settings, for the phone: its sections, the words it uses for each field, the choices it offers
 * where the schema has none, and the rule that shows only the picked speech provider's fields. Everything
 * else comes from the gateway's schema.
 */
object ConfigCatalog {

    val sections: List<ConfigSection> = listOf(
        ConfigSection("model", "Model", listOf("model", "model_context_length", "fallback_providers")),
        ConfigSection("chat", "Chat", listOf("display.personality", "timezone", "display.show_reasoning", "agent.image_input_mode")),
        ConfigSection(
            "workspace",
            "Workspace",
            listOf(
                "terminal.cwd",
                "desktop.repo_scan_enabled",
                "desktop.repo_scan_roots",
                "desktop.repo_scan_exclude_paths",
                "code_execution.mode",
                "terminal.persistent_shell",
                "terminal.env_passthrough",
                "file_read_max_chars",
            ),
        ),
        ConfigSection(
            "safety",
            "Safety",
            listOf(
                "approvals.mode",
                "approvals.timeout",
                "approvals.mcp_reload_confirm",
                "command_allowlist",
                "security.redact_secrets",
                "security.allow_private_urls",
                "checkpoints.enabled",
            ),
        ),
        ConfigSection("browser", "Browser", listOf("browser.use_real_profile", "browser.allow_private_urls", "browser.auto_local_for_private_urls")),
        ConfigSection(
            "memory",
            "Memory and context",
            listOf(
                "memory.memory_enabled",
                "memory.user_profile_enabled",
                "memory.memory_char_limit",
                "memory.user_char_limit",
                "memory.provider",
                "context.engine",
                "compression.enabled",
                "compression.threshold",
                "compression.codex_gpt55_autoraise",
                "compression.target_ratio",
                "compression.protect_last_n",
                "auxiliary.compression.timeout",
            ),
        ),
        ConfigSection(
            "voice",
            "Voice",
            listOf(
                "voice.voice_chat_mode",
                "voice.gpt_live.voice",
                "voice.gpt_live.instructions",
                "tts.provider",
                "stt.enabled",
                "stt.echo_transcripts",
                "stt.provider",
                "stt.streaming",
                "voice.auto_tts",
                "tts.edge.voice",
                "tts.openai.model",
                "tts.openai.voice",
                "tts.elevenlabs.voice_id",
                "tts.elevenlabs.model_id",
                "tts.xai.voice_id",
                "tts.xai.language",
                "tts.xai.speed",
                "tts.xai.auto_speech_tags",
                "tts.xai.optimize_streaming_latency",
                "tts.xai.sample_rate",
                "tts.xai.bit_rate",
                "tts.minimax.model",
                "tts.minimax.voice_id",
                "tts.mistral.model",
                "tts.mistral.voice_id",
                "tts.gemini.model",
                "tts.gemini.voice",
                "tts.neutts.model",
                "tts.neutts.device",
                "tts.kittentts.model",
                "tts.kittentts.voice",
                "tts.piper.voice",
                "tts.deepinfra.model",
                "tts.deepinfra.voice",
                "stt.local.model",
                "stt.local.language",
                "stt.openai.model",
                "stt.openai.streaming_model",
                "stt.groq.model",
                "stt.mistral.model",
                "stt.xai.model",
                "stt.deepinfra.model",
                "stt.elevenlabs.model_id",
                "stt.elevenlabs.language_code",
                "stt.elevenlabs.tag_audio_events",
                "stt.elevenlabs.diarize",
                "voice.max_recording_seconds",
                "voice.client_direct",
            ),
        ),
        ConfigSection(
            "advanced",
            "Advanced",
            listOf(
                "toolsets",
                "terminal.backend",
                "terminal.timeout",
                "terminal.docker_image",
                "terminal.singularity_image",
                "terminal.modal_image",
                "terminal.daytona_image",
                "tool_output.max_bytes",
                "tool_output.max_lines",
                "tool_output.max_line_length",
                "checkpoints.max_snapshots",
                "agent.max_turns",
                "agent.api_max_retries",
                "agent.service_tier",
                "agent.tool_use_enforcement",
                "delegation.model",
                "delegation.provider",
                "delegation.max_iterations",
                "delegation.max_concurrent_children",
                "delegation.child_timeout_seconds",
                "delegation.reasoning_effort",
                "updates.non_interactive_local_changes",
            ),
        ),
    )

    /** Every schema category as a page of its own, in the gateway's order, then the rest alphabetically. */
    fun categories(schema: ConfigSchema): List<ConfigSection> {
        val present = schema.fields.values.map { it.category }.toSet()
        val ordered = schema.categoryOrder.filter { it in present } + (present - schema.categoryOrder.toSet()).sorted()
        return ordered.map { ConfigSection("category:$it", prettyName(it), category = it) }
    }

    /**
     * The settings [section] shows, with their current values. A curated key the schema doesn't describe still
     * shows when the config has it, its control guessed from the value, as Desktop does; Voice keeps only the
     * picked provider's fields.
     */
    fun entries(section: ConfigSection, schema: ConfigSchema, values: JsonObject): List<ConfigEntry> {
        val keys = section.category?.let { category -> schema.fields.filterValues { it.category == category }.keys.toList() } ?: section.keys
        return keys
            .filter { section.id != "voice" || voiceFieldVisible(it, values) }
            .mapNotNull { entry(it, schema, values) }
    }

    /**
     * Settings whose key, label or description holds every word of [query], from the whole schema plus the
     * curated keys it leaves out. Curated ones come first, in their sections' order. [shown] is each key's name as
     * the screen shows it, in the app's language, matched too.
     */
    fun search(query: String, schema: ConfigSchema, values: JsonObject, shown: Map<String, String> = emptyMap()): List<ConfigEntry> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
        if (words.isEmpty()) return emptyList()
        val keys = (sections.flatMap { it.keys } + schema.fields.keys).distinct()
        // Matched on the words alone first: building an entry works out its choices, and there are hundreds.
        return keys.mapNotNull { key ->
            val haystack = listOfNotNull(key, label(key), shown[key], DESCRIPTIONS[key], schema.fields[key]?.description).joinToString(" ").lowercase()
            if (words.all { it in haystack }) entry(key, schema, values) else null
        }
    }

    /**
     * [entries] in runs that share a parent (`auxiliary.vision.*`, `auxiliary.compression.*`), each named by
     * that parent, so a category of a hundred fields reads as the file does. Top-level keys come under "".
     */
    fun grouped(entries: List<ConfigEntry>): List<Pair<String, List<ConfigEntry>>> =
        entries.groupBy { it.key.substringBeforeLast('.', "") }.toList()

    /** A parent path as a heading: `auxiliary.vision` reads "Auxiliary › Vision". */
    fun groupLabel(parent: String): String = parent.split('.').joinToString(" › ") { prettyName(it) }

    fun entry(key: String, schema: ConfigSchema, values: JsonObject): ConfigEntry? {
        val value = values.at(key)
        val field = schema.fields[key] ?: value?.takeIf { it !is JsonNull }?.let(::inferField) ?: return null
        return ConfigEntry(key, label(key), description(key, field), value, control(key, field, value, values))
    }

    /** Desktop's name for the field, or its last part made readable. */
    fun label(key: String): String = LABELS[key] ?: prettyName(key.substringAfterLast('.'))

    /**
     * Desktop's words for the field, else the schema's, left out when they only repeat the label or the key
     * (the schema writes "Agent → Max Turns" for fields nobody described).
     */
    fun description(key: String, field: ConfigField): String? {
        DESCRIPTIONS[key]?.let { return it }
        val text = field.description.trim()
        val bare = normalize(text)
        return text.takeIf { it.isNotEmpty() && bare != normalize(label(key)) && bare != normalize(key) }
    }

    private fun control(key: String, field: ConfigField, value: JsonElement?, values: JsonObject): ConfigControl {
        if (key == "fallback_providers") return ConfigControl.Fallbacks
        if (field.type == "boolean") return ConfigControl.Toggle
        val options = choices(key, field, values)
        if (options != null) {
            val current = (value as? JsonPrimitive)?.contentOrNull.orEmpty()
            // A value set by hand that the list doesn't know stays pickable.
            val withCurrent = if (current.isNotEmpty() && current !in options) options + current else options
            val typed = (field.options + listOfNotNull(value))
                .filterIsInstance<JsonPrimitive>()
                .filter { it !is JsonNull && !it.isString }
                .associateBy { it.content } +
                // The schema's own "not set" is null: "Not set" saves that, not "".
                if (JsonNull in field.options && field.options.none { it is JsonPrimitive && it.isString && it.content.isEmpty() }) mapOf("" to JsonNull) else emptyMap()
            return ConfigControl.Choice(
                options = if (field.clearable && "" !in withCurrent) listOf("") + withCurrent else withCurrent,
                searchable = field.searchable || withCurrent.size > 20,
                other = key in FREE_INPUT,
                typed = typed,
            )
        }
        return when {
            // The schema calls a few numbers strings (agent.max_turns); a number stays a number. Null is no number: it's unset.
            field.type == "number" || (value is JsonPrimitive && value !is JsonNull && !value.isString && value.booleanOrNull == null) -> ConfigControl.Number
            field.type == "list" && (value == null || value.isListOfText()) -> ConfigControl.Lines
            value is JsonObject || value is JsonArray -> ConfigControl.Json
            else -> ConfigControl.Text
        }
    }

    /** The schema's choices, Desktop's where the schema has none, and the config's own personalities and command providers. */
    private fun choices(key: String, field: ConfigField, values: JsonObject): List<String>? {
        if (key == "display.personality") return personalities(values)
        var options = SUGGESTIONS[key] ?: field.choices.takeIf { field.type == "select" && it.isNotEmpty() } ?: return null
        if (key == "tts.provider" || key == "stt.provider") {
            options = options + commandProviders(values, key.substringBefore('.')).filter { it !in options }
        }
        // tts-1 and tts-1-hd don't know the newer voices; offering them would fail at OpenAI.
        if (key == "tts.openai.voice" && values.text("tts.openai.model") in setOf("tts-1", "tts-1-hd")) {
            options = options.filter { it in OPENAI_TTS1_VOICES }
        }
        return options
    }

    /**
     * Voice shows only the picked text-to-speech and speech-to-text provider's own fields, and no
     * speech-to-text ones while it's off. Unset providers are Hermes's defaults: Edge and local.
     */
    fun voiceFieldVisible(key: String, values: JsonObject): Boolean {
        val match = Regex("^(tts|stt)\\.([^.]+)\\.").find(key) ?: return true
        val (domain, provider) = match.destructured
        if (domain == "stt" && values.at("stt.enabled")?.let { (it as? JsonPrimitive)?.booleanOrNull } != true) return false
        val selected = values.text("$domain.provider").orEmpty().ifEmpty { if (domain == "tts") "edge" else "local" }
        return provider == selected
    }

    /** No personality, the built-in ones, then the config's own (root and `agent.`), folded as Hermes folds them. */
    private fun personalities(values: JsonObject): List<String> {
        val custom = listOf("personalities", "agent.personalities")
            .flatMap { (values.at(it) as? JsonObject)?.keys.orEmpty() }
            .map { it.trim().lowercase() }
            .filter { it !in NEUTRAL_PERSONALITIES }
        return (listOf("") + BUILTIN_PERSONALITIES + custom).distinct()
    }

    /**
     * Providers defined in the config as a command (`tts.providers.<name>` with a `command`, or the older
     * `tts.<name>`), which Hermes accepts alongside the built-in ones.
     */
    private fun commandProviders(values: JsonObject, domain: String): List<String> {
        val builtins = if (domain == "tts") BUILTIN_TTS else BUILTIN_STT
        return listOf("$domain.providers", domain).flatMap { path ->
            (values.at(path) as? JsonObject).orEmpty().filter { (name, block) ->
                val obj = block as? JsonObject ?: return@filter false
                val type = (obj["type"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase().orEmpty()
                val command = (obj["command"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                (type.isEmpty() || type == "command") && command.isNotEmpty() && name.trim().lowercase() !in builtins
            }.keys
        }.distinct()
    }

    private fun inferField(value: JsonElement): ConfigField = when {
        value is JsonArray -> ConfigField(type = "list")
        value is JsonPrimitive && value.booleanOrNull != null -> ConfigField(type = "boolean")
        value is JsonPrimitive && !value.isString -> ConfigField(type = "number")
        else -> ConfigField(type = "string")
    }

    private fun JsonElement.isListOfText(): Boolean = this is JsonArray && all { it is JsonPrimitive && it.isString }

    private fun JsonObject.text(path: String): String? = (at(path) as? JsonPrimitive)?.contentOrNull

    /** `api_max_retries` reads "API max retries": words apart, the names and initialisms written as they are. */
    internal fun prettyName(name: String): String =
        name.split('_').joinToString(" ") { WORDS[it.lowercase()] ?: it }.replaceFirstChar { it.uppercase() }

    private val WORDS = mapOf(
        "tts" to "TTS", "stt" to "STT", "lsp" to "LSP", "moa" to "MoA", "mcp" to "MCP", "api" to "API", "url" to "URL",
        "urls" to "URLs", "id" to "ID", "ids" to "IDs", "gpt" to "GPT", "llm" to "LLM", "ssh" to "SSH", "tls" to "TLS",
        "openai" to "OpenAI", "openrouter" to "OpenRouter", "elevenlabs" to "ElevenLabs", "xai" to "xAI", "deepinfra" to "DeepInfra",
        "minimax" to "MiniMax", "neutts" to "NeuTTS", "kittentts" to "KittenTTS", "github" to "GitHub", "whatsapp" to "WhatsApp",
    )

    private fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    private val BUILTIN_PERSONALITIES = listOf(
        "helpful", "concise", "technical", "creative", "teacher", "kawaii", "catgirl",
        "pirate", "shakespeare", "surfer", "noir", "uwu", "philosopher", "hype",
    )
    private val NEUTRAL_PERSONALITIES = setOf("", "none", "default", "neutral")
    private val BUILTIN_TTS = setOf("edge", "elevenlabs", "openai", "minimax", "xai", "mistral", "gemini", "neutts", "kittentts", "piper", "deepinfra")
    private val BUILTIN_STT = setOf("local", "local_command", "groq", "openai", "mistral", "xai", "elevenlabs", "deepinfra")
    private val OPENAI_TTS1_VOICES = setOf("alloy", "ash", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer")

    /** Fields that take names off the list too: providers add voices and models faster than any list. */
    private val FREE_INPUT = setOf(
        "tts.edge.voice", "voice.gpt_live.voice", "tts.openai.model", "tts.openai.voice", "tts.elevenlabs.voice_id",
        "tts.elevenlabs.model_id", "stt.openai.model", "stt.groq.model", "stt.mistral.model", "stt.xai.model",
        "stt.deepinfra.model", "tts.gemini.model", "tts.gemini.voice", "tts.xai.voice_id", "tts.minimax.model",
        "tts.minimax.voice_id", "tts.mistral.model", "tts.mistral.voice_id", "tts.neutts.model", "tts.kittentts.model",
        "tts.kittentts.voice", "tts.piper.voice", "tts.deepinfra.model", "tts.deepinfra.voice",
    )

    /** Desktop's choices for fields the schema leaves as plain text. */
    private val SUGGESTIONS: Map<String, List<String>> = mapOf(
        "agent.image_input_mode" to listOf("auto", "native", "text"),
        "code_execution.mode" to listOf("project", "strict"),
        "stt.provider" to listOf("local", "groq", "openai", "mistral", "xai", "elevenlabs", "deepinfra"),
        "voice.voice_chat_mode" to listOf("chained", "gpt-live"),
        "voice.gpt_live.voice" to listOf(
            "marin", "cedar", "quartz", "ripple", "vesper", "willow", "stone", "gleam", "meridian", "bossa", "tempo", "beacon", "delta", "cinder",
        ),
        "tts.openai.voice" to listOf("alloy", "ash", "ballad", "cedar", "coral", "echo", "fable", "marin", "nova", "onyx", "sage", "shimmer", "verse"),
        "tts.openai.model" to listOf("gpt-4o-mini-tts", "tts-1", "tts-1-hd"),
        "tts.edge.voice" to listOf(
            "en-US-AriaNeural", "en-US-JennyNeural", "en-US-AndrewNeural", "en-US-BrianNeural", "en-US-GuyNeural", "en-GB-SoniaNeural",
        ),
        "tts.elevenlabs.model_id" to listOf(
            "eleven_v3", "eleven_ttv_v3", "eleven_multilingual_v2", "eleven_turbo_v2", "eleven_turbo_v2_5", "eleven_flash_v2", "eleven_flash_v2_5",
        ),
        "tts.gemini.model" to listOf("gemini-2.5-flash-preview-tts", "gemini-2.5-pro-preview-tts"),
        "tts.gemini.voice" to listOf(
            "Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede", "Callirrhoe", "Autonoe", "Enceladus", "Iapetus",
            "Umbriel", "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar", "Alnilam", "Schedar",
            "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat",
        ),
        "tts.xai.voice_id" to listOf("eve"),
        "tts.minimax.model" to listOf("speech-02-hd", "speech-02-turbo"),
        "tts.mistral.model" to listOf("voxtral-mini-tts-2603"),
        "tts.kittentts.model" to listOf(
            "KittenML/kitten-tts-nano-0.8-int8", "KittenML/kitten-tts-micro-0.8-int8", "KittenML/kitten-tts-mini-0.8-int8",
        ),
        "tts.kittentts.voice" to listOf("Jasper"),
        "tts.piper.voice" to listOf("en_US-lessac-medium", "en_US-amy-medium", "en_US-ryan-high", "en_GB-alan-medium"),
        "tts.neutts.model" to listOf("neuphonic/neutts-air-q4-gguf", "neuphonic/neutts-air-q8-gguf", "neuphonic/neutts-air"),
        "tts.neutts.device" to listOf("cpu", "cuda", "mps"),
    )

    private val LABELS: Map<String, String> = mapOf(
        "model" to "Default model",
        "model_context_length" to "Context window override",
        "fallback_providers" to "Fallback models",
        "toolsets" to "Enabled toolsets",
        "timezone" to "Time zone",
        "display.personality" to "Personality",
        "display.show_reasoning" to "Reasoning blocks",
        "desktop.repo_scan_enabled" to "Find repositories",
        "desktop.repo_scan_roots" to "Folders to search for repositories",
        "desktop.repo_scan_exclude_paths" to "Folders to skip",
        "agent.max_turns" to "Max agent steps",
        "agent.image_input_mode" to "Image attachments",
        "agent.api_max_retries" to "API retries",
        "agent.service_tier" to "Service tier",
        "agent.tool_use_enforcement" to "Tool-use enforcement",
        "terminal.cwd" to "Working folder",
        "terminal.backend" to "Execution backend",
        "terminal.timeout" to "Command timeout",
        "terminal.persistent_shell" to "Persistent shell",
        "terminal.env_passthrough" to "Environment passthrough",
        "terminal.docker_image" to "Docker image",
        "terminal.singularity_image" to "Singularity image",
        "terminal.modal_image" to "Modal image",
        "terminal.daytona_image" to "Daytona image",
        "file_read_max_chars" to "File read limit",
        "tool_output.max_bytes" to "Terminal output limit",
        "tool_output.max_lines" to "File page limit",
        "tool_output.max_line_length" to "Line length limit",
        "code_execution.mode" to "Code execution mode",
        "approvals.mode" to "Approval mode",
        "approvals.timeout" to "Approval timeout",
        "approvals.mcp_reload_confirm" to "Confirm MCP reloads",
        "command_allowlist" to "Command allowlist",
        "security.redact_secrets" to "Redact secrets",
        "security.allow_private_urls" to "Allow private URLs",
        "browser.allow_private_urls" to "Browser private URLs",
        "browser.auto_local_for_private_urls" to "Local browser for private URLs",
        "browser.use_real_profile" to "Use my real browser profile",
        "checkpoints.enabled" to "File checkpoints",
        "checkpoints.max_snapshots" to "Checkpoint limit",
        "voice.max_recording_seconds" to "Max recording length",
        "voice.auto_tts" to "Read replies aloud",
        "voice.voice_chat_mode" to "Voice chat mode",
        "voice.gpt_live.voice" to "GPT-Live voice",
        "voice.gpt_live.instructions" to "GPT-Live persona",
        "voice.client_direct" to "Direct voice connection",
        "stt.enabled" to "Speech to text",
        "stt.echo_transcripts" to "Echo transcripts",
        "stt.provider" to "Speech-to-text provider",
        "stt.streaming" to "Live transcription",
        "stt.local.model" to "Local transcription model",
        "stt.local.language" to "Transcription language",
        "stt.openai.model" to "OpenAI transcription model",
        "stt.openai.streaming_model" to "OpenAI live transcription model",
        "stt.groq.model" to "Groq transcription model",
        "stt.mistral.model" to "Mistral transcription model",
        "stt.xai.model" to "xAI transcription model",
        "stt.deepinfra.model" to "DeepInfra transcription model",
        "stt.elevenlabs.model_id" to "ElevenLabs transcription model",
        "stt.elevenlabs.language_code" to "ElevenLabs language",
        "stt.elevenlabs.tag_audio_events" to "Tag audio events",
        "stt.elevenlabs.diarize" to "Speaker diarization",
        "tts.provider" to "Text-to-speech provider",
        "tts.edge.voice" to "Edge voice",
        "tts.openai.model" to "OpenAI speech model",
        "tts.openai.voice" to "OpenAI voice",
        "tts.elevenlabs.voice_id" to "ElevenLabs voice",
        "tts.elevenlabs.model_id" to "ElevenLabs model",
        "tts.xai.voice_id" to "xAI (Grok) voice",
        "tts.xai.language" to "xAI language",
        "tts.xai.speed" to "xAI playback speed",
        "tts.xai.auto_speech_tags" to "xAI auto speech tags",
        "tts.xai.optimize_streaming_latency" to "xAI streaming latency",
        "tts.xai.sample_rate" to "xAI sample rate",
        "tts.xai.bit_rate" to "xAI bit rate",
        "tts.minimax.model" to "MiniMax speech model",
        "tts.minimax.voice_id" to "MiniMax voice",
        "tts.mistral.model" to "Mistral speech model",
        "tts.mistral.voice_id" to "Mistral voice",
        "tts.gemini.model" to "Gemini speech model",
        "tts.gemini.voice" to "Gemini voice",
        "tts.neutts.model" to "NeuTTS model",
        "tts.neutts.device" to "NeuTTS device",
        "tts.kittentts.model" to "KittenTTS model",
        "tts.kittentts.voice" to "KittenTTS voice",
        "tts.piper.voice" to "Piper voice",
        "tts.deepinfra.model" to "DeepInfra speech model",
        "tts.deepinfra.voice" to "DeepInfra voice",
        "memory.memory_enabled" to "Persistent memory",
        "memory.user_profile_enabled" to "User profile",
        "memory.memory_char_limit" to "Memory budget",
        "memory.user_char_limit" to "Profile budget",
        "memory.provider" to "Memory provider",
        "context.engine" to "Context engine",
        "compression.enabled" to "Auto-compression",
        "compression.threshold" to "Compression threshold",
        "compression.codex_gpt55_autoraise" to "Codex compression auto-raise",
        "compression.target_ratio" to "Compression target",
        "compression.protect_last_n" to "Protected recent messages",
        "auxiliary.compression.timeout" to "Compression model timeout (s)",
        "delegation.model" to "Subagent model",
        "delegation.provider" to "Subagent provider",
        "delegation.max_iterations" to "Subagent turn limit",
        "delegation.max_concurrent_children" to "Parallel subagents",
        "delegation.child_timeout_seconds" to "Subagent timeout",
        "delegation.reasoning_effort" to "Subagent reasoning effort",
        "updates.non_interactive_local_changes" to "In-app update local changes",
    )

    private val DESCRIPTIONS: Map<String, String> = mapOf(
        "model" to "Used for new chats unless a chat picks another model.",
        "model_context_length" to "Overrides the detected context window of the main chat model, in tokens. 0 uses the model's own.",
        "fallback_providers" to "Tried in order when the default model fails. One provider:model per line.",
        "display.personality" to "Default assistant style for new chats.",
        "display.show_reasoning" to "Show reasoning when the model provides it.",
        "desktop.repo_scan_enabled" to "Look for Git repositories on the gateway to show as projects.",
        "desktop.repo_scan_roots" to "Folders to search. Empty searches the home folder.",
        "desktop.repo_scan_exclude_paths" to "Folders, and everything in them, left out of the search.",
        "timezone" to "Empty uses the gateway's own time zone.",
        "browser.use_real_profile" to "Local browsing uses a copy of the gateway computer's default Chromium profile, with its logins. Off by default.",
        "agent.image_input_mode" to "How pictures you attach are sent to the model.",
        "agent.max_turns" to "Most tool-calling steps before Hermes stops a run.",
        "terminal.cwd" to "Default folder for tool and terminal work.",
        "terminal.persistent_shell" to "SSH backend: keep exported variables between commands.",
        "terminal.env_passthrough" to "Environment variables passed into tool runs.",
        "terminal.docker_image" to "Container image when the execution backend is Docker.",
        "terminal.singularity_image" to "Image when the execution backend is Singularity.",
        "terminal.modal_image" to "Image when the execution backend is Modal.",
        "terminal.daytona_image" to "Image when the execution backend is Daytona.",
        "code_execution.mode" to "How strictly code runs are kept to the current project.",
        "file_read_max_chars" to "Most characters Hermes reads from a file at once.",
        "approvals.mode" to "How Hermes handles commands that need your OK.",
        "approvals.timeout" to "How long an approval waits for an answer.",
        "security.redact_secrets" to "Hide secrets it finds from what the model sees, where it can.",
        "checkpoints.enabled" to "Take a snapshot before the agent changes files, so it can be undone.",
        "memory.memory_enabled" to "Save lasting memories that help future chats.",
        "memory.user_profile_enabled" to "Keep a short profile of your preferences.",
        "context.engine" to "How long conversations are handled near the context limit.",
        "compression.enabled" to "Summarize older messages when a conversation gets long.",
        "compression.codex_gpt55_autoraise" to "Raise compression to 85% for supported ChatGPT Codex models.",
        "auxiliary.compression.timeout" to "Seconds to wait for the compression model per call. Raise it for slow local models.",
        "voice.auto_tts" to "Speak every reply.",
        "voice.voice_chat_mode" to "chained: speech to text, Hermes, then text to speech with the providers below. gpt-live: one OpenAI voice model listens and talks, and hands each real request to Hermes. gpt-live needs an OpenAI key.",
        "voice.gpt_live.voice" to "Voice for GPT-Live. Custom voice IDs work too.",
        "voice.gpt_live.instructions" to "Extra sentences for the live voice (tone, pace, language). Hermes keeps its own system prompt.",
        "tts.xai.voice_id" to "xAI voice ID (like eve) or a custom one.",
        "tts.xai.language" to "Spoken language code (en, pt-BR…), or auto.",
        "tts.xai.speed" to "0.7 is slower, 1.0 normal, 1.5 faster.",
        "tts.xai.auto_speech_tags" to "Let a model add expressive tags like [laughing] before speaking.",
        "tts.xai.optimize_streaming_latency" to "0 is best quality, 2 lowest latency.",
        "tts.xai.sample_rate" to "Audio sample rate in Hz.",
        "tts.xai.bit_rate" to "MP3 bit rate in bps.",
        "tts.neutts.device" to "Where NeuTTS runs.",
        "stt.enabled" to "Transcribe voice messages, locally or with a provider.",
        "stt.echo_transcripts" to "Post the transcript of a voice message back to the chat.",
        "stt.streaming" to "Show text while you speak (OpenAI, xAI, ElevenLabs).",
        "stt.elevenlabs.language_code" to "Optional ISO 639-3 code. Empty lets ElevenLabs detect it.",
        "updates.non_interactive_local_changes" to "When Hermes updates itself from an app, keep local source edits (stash) or throw them away (discard).",
    )
}

/** The value at a dotted [path], or null when any part of it is missing. */
fun JsonObject.at(path: String): JsonElement? {
    var current: JsonElement = this
    for (part in path.split('.')) {
        current = (current as? JsonObject)?.get(part) ?: return null
    }
    return current
}

/** This config with [value] at [path], making the objects on the way as needed. */
fun JsonObject.with(path: String, value: JsonElement): JsonObject {
    val head = path.substringBefore('.')
    if (head == path) return JsonObject(this + (head to value))
    val child = this[head] as? JsonObject ?: JsonObject(emptyMap())
    return JsonObject(this + (head to child.with(path.substringAfter('.'), value)))
}

/** This config without the key at [path]; the objects on the way stay. */
fun JsonObject.without(path: String): JsonObject {
    val head = path.substringBefore('.')
    if (head == path) return JsonObject(this - head)
    val child = this[head] as? JsonObject ?: return this
    return JsonObject(this + (head to child.without(path.substringAfter('.'))))
}

/** The smallest config holding just [value] at [path]: what a save sends. */
fun configPatch(path: String, value: JsonElement): JsonObject = JsonObject(emptyMap()).with(path, value)
