package dev.hermeskotlin.ui.settings

import kotlinx.serialization.json.*

/** The gateway's plugin-management list, not proof that Screen's routes are running. */
enum class ScreenPluginStatus { Checking, Unknown, Missing, Disabled, Enabled }

internal fun screenPluginStatus(result: JsonElement): ScreenPluginStatus {
    val rows = (result as? JsonObject)?.get("plugins") as? JsonArray ?: return ScreenPluginStatus.Unknown
    val plugins = rows.map {
        val row = it as? JsonObject ?: return ScreenPluginStatus.Unknown
        val name = (row["name"] as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
            ?.takeIf { value -> value.isNotBlank() } ?: return ScreenPluginStatus.Unknown
        name to row
    }
    val matching = plugins.filter { it.first == "herald-screen" }
    if (matching.isEmpty()) return ScreenPluginStatus.Missing
    if (matching.map { it.first }.distinct().size != matching.size) return ScreenPluginStatus.Unknown
    val enabled = matching.map {
        when ((it.second["status"] as? JsonPrimitive)?.takeIf { value -> value.isString }?.content) {
            "enabled" -> true
            "disabled", "not enabled" -> false
            else -> return ScreenPluginStatus.Unknown
        }
    }
    return if (enabled.any { it }) ScreenPluginStatus.Enabled else ScreenPluginStatus.Disabled
}

internal fun canChangeScreenClipboard(checked: Boolean, status: ScreenPluginStatus) =
    checked || status == ScreenPluginStatus.Enabled

internal fun screenStatusText(status: ScreenPluginStatus): String = when (status) {
    ScreenPluginStatus.Checking -> "Checking gateway plugin list…"
    ScreenPluginStatus.Unknown -> "Unverified — gateway plugin status is unavailable or unrecognized."
    ScreenPluginStatus.Missing -> "Last check: not listed on the current gateway connection."
    ScreenPluginStatus.Disabled -> "Last check: listed, but not enabled on the current gateway connection."
    ScreenPluginStatus.Enabled -> "Last check: Screen is listed and enabled on the current gateway connection."
}
