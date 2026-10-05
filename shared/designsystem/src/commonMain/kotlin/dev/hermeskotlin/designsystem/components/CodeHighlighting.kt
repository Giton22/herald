package dev.hermeskotlin.designsystem.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme

/** What a stretch of code is, for coloring. */
enum class CodeToken { Keyword, Text, Literal, Comment, Annotation, Punctuation }

/** One colored stretch of a code block: [start] inclusive, [end] exclusive. */
data class CodeSpan(val start: Int, val end: Int, val token: CodeToken)

/** Colors per [CodeToken]; [CodeToken.Punctuation] keeps the text color. */
data class SyntaxColors(
    val keyword: Color,
    val string: Color,
    val literal: Color,
    val comment: Color,
    val annotation: Color,
) {
    fun of(token: CodeToken): Color? = when (token) {
        CodeToken.Keyword -> keyword
        CodeToken.Text -> string
        CodeToken.Literal -> literal
        CodeToken.Comment -> comment
        CodeToken.Annotation -> annotation
        CodeToken.Punctuation -> null
    }

    companion object {
        /** GitHub's light syntax colors, on the light theme's near-white code surface. */
        val Light = SyntaxColors(
            keyword = Color(0xFFCF222E),
            string = Color(0xFF0A3069),
            literal = Color(0xFF0550AE),
            comment = Color(0xFF6E7781),
            annotation = Color(0xFF8250DF),
        )

        /** GitHub's dark syntax colors; they read on both the dark and the pure black surface. */
        val Dark = SyntaxColors(
            keyword = Color(0xFFFF7B72),
            string = Color(0xFFA5D6FF),
            literal = Color(0xFF79C0FF),
            comment = Color(0xFF8B949E),
            annotation = Color(0xFFD2A8FF),
        )
    }
}

/**
 * Splits code into [CodeSpan]s with the Highlights library. Its themes hand out colors, not token
 * kinds, so it is given a theme whose "colors" are token ids and they are read back: the result
 * doesn't depend on light or dark, and one cached copy serves both.
 */
object CodeHighlighter {

    /** The language a fence names, or null when it names none Highlights knows (then nothing is colored). */
    fun language(name: String?): SyntaxLanguage? {
        val key = name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return ALIASES[key] ?: SyntaxLanguage.entries.firstOrNull { it.name.lowercase() == key && it != SyntaxLanguage.DEFAULT }
            ?: if (key in GENERIC) SyntaxLanguage.DEFAULT else null
    }

    fun spans(code: String, language: SyntaxLanguage): List<CodeSpan> {
        if (code.isEmpty()) return emptyList()
        val highlights = Highlights.Builder().code(code).language(language).theme(TOKEN_THEME).build().getHighlights()
        return highlights.mapNotNull { highlight ->
            val color = highlight as? ColorHighlight ?: return@mapNotNull null
            val token = TOKEN_IDS[color.rgb] ?: return@mapNotNull null
            val start = color.location.start.coerceIn(0, code.length)
            val end = color.location.end.coerceIn(start, code.length)
            if (end > start) CodeSpan(start, end, token) else null
        }
    }

    private val TOKEN_IDS = mapOf(
        1 to CodeToken.Keyword,
        2 to CodeToken.Text,
        3 to CodeToken.Literal,
        4 to CodeToken.Comment,
        5 to CodeToken.Annotation,
        6 to CodeToken.Comment,
        7 to CodeToken.Punctuation,
    )

    private val TOKEN_THEME = SyntaxTheme(
        key = "herald-tokens",
        code = 0,
        keyword = 1,
        string = 2,
        literal = 3,
        comment = 4,
        metadata = 5,
        multilineComment = 6,
        punctuation = 7,
        mark = 7,
    )

    private val ALIASES = mapOf(
        "kt" to SyntaxLanguage.KOTLIN, "kts" to SyntaxLanguage.KOTLIN,
        "py" to SyntaxLanguage.PYTHON, "python3" to SyntaxLanguage.PYTHON,
        "js" to SyntaxLanguage.JAVASCRIPT, "jsx" to SyntaxLanguage.JAVASCRIPT, "mjs" to SyntaxLanguage.JAVASCRIPT, "node" to SyntaxLanguage.JAVASCRIPT,
        "ts" to SyntaxLanguage.TYPESCRIPT, "tsx" to SyntaxLanguage.TYPESCRIPT,
        "sh" to SyntaxLanguage.SHELL, "bash" to SyntaxLanguage.SHELL, "zsh" to SyntaxLanguage.SHELL, "console" to SyntaxLanguage.SHELL,
        "shell-session" to SyntaxLanguage.SHELL, "fish" to SyntaxLanguage.SHELL,
        "rs" to SyntaxLanguage.RUST,
        "c++" to SyntaxLanguage.CPP, "cc" to SyntaxLanguage.CPP, "hpp" to SyntaxLanguage.CPP, "h" to SyntaxLanguage.C,
        "cs" to SyntaxLanguage.CSHARP, "c#" to SyntaxLanguage.CSHARP,
        "golang" to SyntaxLanguage.GO,
        "rb" to SyntaxLanguage.RUBY,
        "pl" to SyntaxLanguage.PERL,
        "coffee" to SyntaxLanguage.COFFEESCRIPT,
    )

    /** Named languages Highlights has no grammar for: its generic one still finds strings, numbers and comments. */
    private val GENERIC = setOf(
        "json", "jsonc", "yaml", "yml", "toml", "ini", "sql", "html", "xml", "css", "scss", "lua", "r", "scala",
        "groovy", "gradle", "dockerfile", "makefile", "powershell", "ps1", "elixir", "haskell", "zig", "nim", "objc",
    )
}

/**
 * Highlighted code by content, so a finished block isn't worked out again each time the reply around it
 * changes (a streaming reply re-renders on every delta). Least recently used entries go first.
 */
class HighlightCache(
    private val maxEntries: Int = 64,
    private val highlighter: (String, SyntaxLanguage) -> List<CodeSpan> = CodeHighlighter::spans,
) {
    private data class Key(val code: String, val language: SyntaxLanguage)

    private val entries = LinkedHashMap<Key, List<CodeSpan>>()

    /** Already worked out, or null. */
    fun cached(code: String, language: SyntaxLanguage): List<CodeSpan>? {
        val key = Key(code, language)
        val spans = entries.remove(key) ?: return null
        entries[key] = spans
        return spans
    }

    /** The spans for [code], working them out if they aren't cached. */
    fun spans(code: String, language: SyntaxLanguage): List<CodeSpan> =
        cached(code, language) ?: highlighter(code, language).also { put(code, language, it) }

    fun put(code: String, language: SyntaxLanguage, spans: List<CodeSpan>) {
        val key = Key(code, language)
        entries.remove(key)
        entries[key] = spans
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
    }

    val size: Int get() = entries.size
}

/** [code] with [spans] colored; spans past its end (left from a shorter, earlier text) are dropped. */
fun highlightedCode(code: String, spans: List<CodeSpan>, colors: SyntaxColors): AnnotatedString =
    AnnotatedString.Builder(code).apply {
        for (span in spans) {
            if (span.end > code.length) continue
            val color = colors.of(span.token) ?: continue
            addStyle(SpanStyle(color = color), span.start, span.end)
        }
    }.toAnnotatedString()
