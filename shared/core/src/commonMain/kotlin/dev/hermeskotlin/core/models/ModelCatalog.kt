package dev.hermeskotlin.core.models

import dev.hermeskotlin.core.chat.asObjectList
import dev.hermeskotlin.core.chat.boolean
import dev.hermeskotlin.core.chat.string
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.rpc.RpcException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** One pickable model of a [ModelProvider], with what the gateway knows it can do. */
data class ModelOption(
    val id: String,
    val provider: String,
    /** Takes a reasoning effort level. */
    val reasoning: Boolean = false,
    /** Reasoning can be switched off entirely ("Off"). */
    val canDisableReasoning: Boolean = false,
    /** Has a priority tier ("Fast"). */
    val fast: Boolean = false,
    /** Formatted price per million tokens, e.g. "$1.25 / $10", "Free", or null when unknown. */
    val price: String? = null,
)

data class ModelProvider(
    val slug: String,
    val name: String,
    val models: List<ModelOption>,
    /** The provider's own short list, shown first when it has many models. */
    val featured: List<String> = emptyList(),
    val warning: String? = null,
)

/** `model.options`: the configured providers and what the session (or the profile default) runs now. */
data class ModelCatalog(
    val providers: List<ModelProvider>,
    val currentModel: String? = null,
    val currentProvider: String? = null,
) {
    fun find(provider: String?, model: String?): ModelOption? {
        if (model.isNullOrBlank()) return null
        val candidates = providers.filter { provider.isNullOrBlank() || it.slug == provider }
        return candidates.firstNotNullOfOrNull { p -> p.models.firstOrNull { it.id == model } }
    }

    companion object {
        /** Lenient: providers without credentials or without models are dropped, unknown fields ignored. */
        fun parse(result: JsonObject?): ModelCatalog {
            val providers = result?.get("providers").asObjectList().mapNotNull { row ->
                val slug = row.string("slug") ?: return@mapNotNull null
                if (row.boolean("authenticated") == false) return@mapNotNull null
                val capabilities = row["capabilities"] as? JsonObject
                val pricing = row["pricing"] as? JsonObject
                val unavailable = row.strings("unavailable_models").toSet()
                val models = row.strings("models").filter { it !in unavailable }.distinct().map { id ->
                    val caps = capabilities?.get(id) as? JsonObject
                    ModelOption(
                        id = id,
                        provider = slug,
                        reasoning = caps.boolean("reasoning") == true,
                        canDisableReasoning = caps.boolean("can_disable_reasoning") == true,
                        fast = caps.boolean("fast") == true,
                        price = (pricing?.get(id) as? JsonObject).formatPrice(),
                    )
                }
                if (models.isEmpty()) return@mapNotNull null
                ModelProvider(
                    slug = slug,
                    name = row.string("name")?.takeIf { it.isNotBlank() } ?: slug,
                    models = models,
                    featured = row.strings("featured_models").filter { f -> models.any { it.id == f } },
                    warning = row.string("warning")?.takeIf { it.isNotBlank() },
                )
            }
            return ModelCatalog(
                providers = providers,
                currentModel = result.string("model")?.takeIf { it.isNotBlank() },
                currentProvider = result.string("provider")?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/** Reasoning effort levels, weakest first, as `config.set reasoning` and `session.create` take them. */
enum class ReasoningEffort(val wire: String, val label: String) {
    Off("none", "Off"),
    Minimal("minimal", "Minimal"),
    Low("low", "Low"),
    Medium("medium", "Medium"),
    High("high", "High"),
    XHigh("xhigh", "Extra high"),
    Max("max", "Max"),
    Ultra("ultra", "Ultra"),
    ;

    companion object {
        /** The level the gateway uses when a session doesn't set one. */
        val Default = Medium

        fun fromWire(value: String?): ReasoningEffort? = entries.firstOrNull { it.wire == value?.trim()?.lowercase() }

        /** What [model] can be set to; Off only where reasoning can be disabled. */
        fun choicesFor(model: ModelOption?): List<ReasoningEffort> = when {
            model == null || !model.reasoning -> emptyList()
            model.canDisableReasoning -> entries
            else -> entries - Off
        }
    }
}

/** Reads the model catalog over the live socket. */
class ModelsApi(private val connection: GatewayConnection) {

    /** The catalog for [runtimeSessionId] (its current model marked) or, without one, the profile default. */
    suspend fun options(runtimeSessionId: String? = null, profile: String? = null): ModelCatalog {
        val client = (connection.state.value as? ConnectionState.Connected)?.client
            ?: throw RpcException(0, "Not connected to the gateway.")
        val result = client.request(
            "model.options",
            buildJsonObject {
                runtimeSessionId?.let { put("session_id", it) }
                profile?.let { put("profile", it) }
            },
        )
        return ModelCatalog.parse(result as? JsonObject)
    }
}

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }

private fun JsonObject?.formatPrice(): String? {
    if (this == null) return null
    if (boolean("free") == true) return "Free"
    val input = string("input")?.takeIf { it.isNotBlank() } ?: return null
    val output = string("output")?.takeIf { it.isNotBlank() } ?: return input
    return "$input / $output"
}
