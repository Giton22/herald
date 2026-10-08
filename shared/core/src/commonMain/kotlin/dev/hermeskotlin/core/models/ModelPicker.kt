package dev.hermeskotlin.core.models

/** What a starred model is kept under: provider and id, since two providers can offer the same id. */
val ModelOption.starKey: String get() = "$provider/$id"

/** One heading of the model picker and the models under it; [starred] is the group of starred models on top. */
data class ModelGroup(val title: String, val models: List<ModelOption>, val starred: Boolean = false)

/**
 * The model picker's groups for [query]. A provider whose name or slug matches shows all of its models, so
 * typing the name written above a group finds that group; otherwise a model shows when its id or display name
 * matches. Models in [starred] (by [starKey]) come first in a "Starred" group of their own, kept in the order
 * they were starred, and also stay in their provider's group.
 */
fun ModelCatalog.pickerGroups(query: String, starred: List<String> = emptyList()): List<ModelGroup> {
    val needle = query.trim()
    val byProvider = providers.mapNotNull { provider ->
        val title = displayProviderName(provider.slug, provider.name)
        val providerMatches = needle.isEmpty() ||
            title.contains(needle, ignoreCase = true) ||
            provider.slug.contains(needle, ignoreCase = true) ||
            provider.name.contains(needle, ignoreCase = true)
        val models = if (providerMatches) provider.models else provider.models.filter { it.matches(needle) }
        if (models.isEmpty()) null else ModelGroup(title, models)
    }
    val shown = byProvider.flatMap { it.models }.associateBy { it.starKey }
    val starredModels = starred.mapNotNull { shown[it] }
    return if (starredModels.isEmpty()) byProvider else listOf(ModelGroup("Starred", starredModels, starred = true)) + byProvider
}

private fun ModelOption.matches(needle: String) =
    id.contains(needle, ignoreCase = true) || displayModelName(id).contains(needle, ignoreCase = true)
