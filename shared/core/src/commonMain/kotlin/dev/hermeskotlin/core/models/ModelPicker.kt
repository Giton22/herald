package dev.hermeskotlin.core.models

/** What a starred or recent model is kept under: provider and id, since two providers can offer the same id. */
val ModelOption.starKey: String get() = "$provider/$id"

/**
 * One model as the picker lists it. The same model from several providers is one row with one [variants] entry
 * per provider, in catalog order: `anthropic/claude-opus-5.5` on Nous Portal and on OpenRouter and `claude-opus-5-5`
 * on Anthropic are all one "Opus 5.5" row.
 */
data class PickerModel(val name: String, val variants: List<ModelOption>) {
    fun isStarred(starred: Collection<String>): Boolean = variants.any { it.starKey in starred }

    /**
     * The provider a tap on the row picks: the one in use when this is the current model, else a starred one,
     * else the most recently used one, else the first.
     */
    fun preferred(provider: String?, id: String?, starred: Collection<String>, recent: List<String>): ModelOption =
        variants.firstOrNull { it.id == id && (provider == null || it.provider == provider) }
            ?: variants.firstOrNull { it.starKey in starred }
            ?: recent.firstNotNullOfOrNull { key -> variants.firstOrNull { it.starKey == key } }
            ?: variants.first()
}

/** Which models the picker lists: the ones you use, every model, or one provider's. */
sealed interface PickerScope {
    data object Yours : PickerScope
    data object All : PickerScope
    data class Provider(val slug: String) : PickerScope
}

/**
 * The scope the picker shows: the [chosen] one while it still has something to show, else yours when there are
 * any ([hasYours]), else all. A search looks through every model, so it is never shown as a search of yours.
 */
fun pickerScope(chosen: PickerScope?, hasYours: Boolean, searching: Boolean): PickerScope {
    val scope = chosen?.takeIf { it != PickerScope.Yours || hasYours } ?: if (hasYours) PickerScope.Yours else PickerScope.All
    return if (searching && scope == PickerScope.Yours) PickerScope.All else scope
}

/** A run of picker rows under an optional heading. */
data class PickerSection(val title: String?, val models: List<PickerModel>)

/**
 * Every model of the catalog (or of [provider] only) as picker rows, the same model from different providers
 * merged into one row. Two models of one provider stay apart even when their names match.
 */
fun ModelCatalog.pickerModels(provider: String? = null): List<PickerModel> {
    val rows = LinkedHashMap<String, MutableList<MutableList<ModelOption>>>()
    providers.filter { provider == null || it.slug == provider }.forEach { p ->
        p.models.forEach { model ->
            val sameModel = rows.getOrPut(sameModelKey(model.id)) { mutableListOf() }
            val row = sameModel.firstOrNull { row -> row.none { it.provider == model.provider } }
            if (row != null) row += model else sameModel += mutableListOf(model)
        }
    }
    return rows.values.flatten().map { PickerModel(displayModelName(it.first().id), it) }
}

/** The catalog offers at least one of the models kept under [keys] ([starKey]s). */
fun ModelCatalog.offersAny(keys: Collection<String>): Boolean =
    keys.isNotEmpty() && providers.any { p -> p.models.any { it.starKey in keys } }

/**
 * What the model picker shows for [query] in [scope].
 *
 * Without a query, [PickerScope.Yours] lists the [starred] models (in the order they were starred), then the
 * [current] model (a [starKey]) and the [recent] ones; the other scopes list every model they cover.
 *
 * A query ranks models by how well their name or id matches: a name that starts with it first, then one with a
 * word that does, then one that has it anywhere. A query with digits also matches letters in order, so "op55"
 * finds Opus 5.5. Outside a provider's scope, typing the start of a provider's name also lists that provider's
 * other models, in a section of their own after the models that matched by name.
 */
fun ModelCatalog.pickerSections(
    query: String,
    scope: PickerScope,
    starred: List<String> = emptyList(),
    recent: List<String> = emptyList(),
    current: String? = null,
): List<PickerSection> {
    val needle = query.trim()
    val slug = (scope as? PickerScope.Provider)?.slug
    val models = pickerModels(slug)
    val sections = when {
        needle.isNotEmpty() -> search(needle, models, acrossProviders = slug == null)
        scope == PickerScope.Yours -> {
            fun row(key: String) = models.firstOrNull { m -> m.variants.any { it.starKey == key } }
            val starredRows = starred.mapNotNull(::row).distinct()
            val recentRows = (listOfNotNull(current) + recent).mapNotNull(::row).distinct() - starredRows.toSet()
            listOf(PickerSection("Starred", starredRows), PickerSection("Recent", recentRows.take(RECENT_SHOWN)))
        }
        else -> listOf(PickerSection(null, models))
    }
    return sections.filter { it.models.isNotEmpty() }
}

/** This starred list with [row] starred ([pick] is the provider starred), or unstarred from every provider if it was. */
fun List<String>.toggleStar(row: PickerModel, pick: ModelOption): List<String> =
    if (row.isStarred(this)) this - row.variants.map { it.starKey }.toSet() else this + pick.starKey

/** This recent list with [pick] moved to the front, keeping the last few. */
fun List<String>.withRecent(pick: ModelOption): List<String> = (listOf(pick.starKey) + (this - pick.starKey)).take(RECENT_KEPT)

private fun ModelCatalog.search(needle: String, models: List<PickerModel>, acrossProviders: Boolean): List<PickerSection> {
    val digits = needle.any { it.isDigit() }
    val namedProviders = if (acrossProviders && needle.length >= 2) providers.filter { it.nameStartsWith(needle) } else emptyList()
    var scored = models.map { it to it.score(needle, fuzzy = digits) }.filter { it.second > 0 }
    // Letters in order are a last resort for words: "flash" shouldn't drag in every model with an f, l, a, s and h,
    // and "open" names providers rather than misspelling a model.
    if (scored.isEmpty() && !digits && namedProviders.isEmpty()) {
        scored = models.map { it to it.score(needle, fuzzy = true) }.filter { it.second > 0 }
    }
    val hits = scored.sortedByDescending { it.second }.map { it.first }
    val fromProviders = mutableListOf<PickerSection>()
    if (namedProviders.isNotEmpty()) {
        val shown = hits.toMutableSet()
        namedProviders.forEach { provider ->
            val rows = models.filter { m -> m !in shown && m.variants.any { it.provider == provider.slug } }
            shown += rows
            fromProviders += PickerSection("From ${displayProviderName(provider.slug, provider.name)}", rows)
        }
    }
    val titled = hits.isNotEmpty() && fromProviders.any { it.models.isNotEmpty() }
    return listOf(PickerSection(if (titled) "Models" else null, hits)) + fromProviders
}

/** 4: the name or id starts with [needle]; 3: a word in it does; 2: it's in there; 1 ([fuzzy]): its letters are, in order. */
private fun PickerModel.score(needle: String, fuzzy: Boolean): Int {
    val n = needle.lowercase()
    val texts = (listOf(name) + variants.flatMap { listOf(it.id, it.id.substringAfterLast('/')) }).map { it.lowercase() }
    val best = texts.maxOf { t ->
        when {
            t.startsWith(n) -> 4
            t.hasWordStartingWith(n) -> 3
            n in t -> 2
            else -> 0
        }
    }
    if (best > 0) return best
    val compactNeedle = n.compact()
    if (compactNeedle.isEmpty()) return 0
    val compactTexts = texts.map { it.compact() }
    // Letters in order only within the model's own name: across "anthropic/claude-sonnet", "open" would match.
    val names = (listOf(name) + variants.map { it.id.substringAfterLast('/') }).map { it.lowercase().compact() }
    return when {
        compactTexts.any { compactNeedle in it } -> 2
        fuzzy && compactNeedle.length >= 2 && names.any { compactNeedle.isSubsequenceOf(it) } -> 1
        else -> 0
    }
}

private fun ModelProvider.nameStartsWith(needle: String): Boolean {
    val n = needle.lowercase()
    return listOf(displayProviderName(slug, name), slug, name).any { it.lowercase().hasWordStartingWith(n) }
}

private fun String.hasWordStartingWith(needle: String): Boolean {
    var from = 0
    while (true) {
        val at = indexOf(needle, from)
        if (at < 0) return false
        if (at == 0 || !this[at - 1].isLetterOrDigit()) return true
        from = at + 1
    }
}

private fun String.compact(): String = filter { it.isLetterOrDigit() }

private fun String.isSubsequenceOf(text: String): Boolean {
    var i = 0
    for (c in text) if (i < length && this[i] == c) i++
    return i == length
}

/** The same model whatever a provider calls it: the part after the vendor prefix, dots read as dashes. */
private fun sameModelKey(id: String): String = id.trim().substringAfterLast('/').lowercase().replace('.', '-')

private const val RECENT_SHOWN = 5
private const val RECENT_KEPT = 8
