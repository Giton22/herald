package dev.hermeskotlin.core.media

/**
 * A file on the gateway's disk that a reply links to: `[report.md](/opt/data/report.md)`,
 * `<file:///home/me/notes.txt>` or a Codex-style `/src/App.kt:42` / `#L42`. [line] is the line the
 * link points at, when it names one.
 */
data class GatewayFile(val path: String, val line: Int? = null) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

    /** The folder it's in, `~/` kept as written; empty for a file at the root. */
    val folder: String get() = path.trimEnd('/').substringBeforeLast('/', "")

    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    val kind: GatewayFileKind get() = GatewayFileKind.of(name)

    /**
     * The gateway file a link in this file points at. An absolute path or `file://` URI reads as [fromLink]
     * reads it in a reply. A relative one (`part2.md`, `./img/chart.png`, `../data/out.csv`, `App.kt:42`) is
     * joined to this file's [folder], `.` and `..` worked out: a `..` past `/` stays at `/`, one past `~/`
     * is kept for the gateway to resolve. A bare `#L12` is this file at that line. Null for a web link,
     * another scheme, a bare `#anchor`, or a link to a folder.
     */
    fun resolve(link: String): GatewayFile? {
        // A destination written `<my chart.png>` may come with its brackets.
        val trimmed = link.trim().removeSurrounding("<", ">").trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("/") || trimmed.startsWith("~/") || SCHEME.containsMatchIn(trimmed) && !isNameAndLine(trimmed)) {
            val absolute = fromLink(trimmed) ?: return null
            // In a file on a Windows drive, `/b.md` is on that drive.
            val drive = DRIVE_ROOT.find(path)?.value
            return if (drive != null && absolute.path.startsWith("/")) absolute.copy(path = drive.dropLast(1) + absolute.path) else absolute
        }
        if (trimmed.startsWith("#")) {
            val line = LINE_FRAGMENT.matchEntire(trimmed.substring(1))?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
            return line?.let { copy(line = it) }
        }
        // Read as if it hung off the root, so escapes, anchors and `:12` come off the same way they do in a reply.
        // A file written on Windows may separate its folders with `\`.
        val relative = fromLink("/" + trimmed.replace('\\', '/')) ?: return null
        if (relative.path.endsWith("/") || relative.path.substringAfterLast('/') in setOf(".", "..")) return null
        val joined = normalize(folder + relative.path)
        if (joined == "/" || joined == "~/" || joined.endsWith(":/")) return null
        return GatewayFile(joined, relative.line)
    }

    companion object {
        /**
         * The gateway file a link points at, or null for anything else (a web page, `mailto:`, another
         * app's scheme, a relative path whose folder isn't known). Percent escapes are decoded, a
         * `#L12` fragment or `:12` / `:12:5` suffix becomes [line], and a `file://` URI's host
         * (`localhost` or none) is dropped.
         */
        fun fromLink(link: String): GatewayFile? {
            var rest = link.trim()
            if (rest.isEmpty()) return null
            val scheme = SCHEME.find(rest)?.groupValues?.get(1)?.lowercase()
            if (scheme != null && scheme != "file") return null
            if (scheme == "file") {
                rest = rest.substring(5)
                if (rest.startsWith("//")) {
                    val host = rest.substring(2).substringBefore('/')
                    if (host.isNotEmpty() && !host.equals("localhost", ignoreCase = true)) return null
                    rest = rest.substring(2 + host.length)
                }
                rest = rest.substringBefore('?')
                if (!rest.startsWith("/")) return null
            } else if (!(rest.startsWith("/") && !rest.startsWith("//")) && !rest.startsWith("~/")) {
                return null
            }
            var line: Int? = null
            // In a URI a literal `#` is escaped, so the first one starts the fragment. A path written plainly can
            // hold one in a name (`issue#12.md`, `C#/App.cs`): only a last `#L12` is cut, or an anchor after a
            // name that already has its extension (`guide.md#v1.2-notes`), or one with no dot at all (`#setup`).
            val hash = if (scheme == "file") rest.indexOf('#') else rest.lastIndexOf('#')
            val fragment = if (hash < 0) "" else rest.substring(hash + 1)
            val named = hash >= 0 && '.' in rest.substring(0, hash).substringAfterLast('/')
            val isFragment = hash >= 0 &&
                (scheme == "file" || LINE_FRAGMENT.matches(fragment) || ('/' !in fragment && ('.' !in fragment || named)))
            if (isFragment) {
                line = LINE_FRAGMENT.matchEntire(fragment)?.groupValues?.get(1)?.toIntOrNull()
                rest = rest.substring(0, hash)
            }
            var path = percentDecode(rest)
            // Windows drive paths come through file:// as /C:/...
            if (WINDOWS_DRIVE.containsMatchIn(path)) path = path.substring(1)
            LINE_SUFFIX.find(path)?.let { match ->
                line = line ?: match.groupValues[1].toIntOrNull()
                path = path.substring(0, match.range.first)
            }
            if (path.isEmpty() || path == "/" || path == "~/") return null
            return GatewayFile(path, line?.takeIf { it > 0 })
        }

        private val SCHEME = Regex("""^([A-Za-z][A-Za-z0-9+.-]*):""")
        private val LINE_FRAGMENT = Regex("""L(\d+)(?:-L?\d+)?(?:C\d+)?""")
        private val LINE_SUFFIX = Regex(""":(\d+)(?::\d+)?$""")
        private val WINDOWS_DRIVE = Regex("""^/[A-Za-z]:/""")

        /**
         * `App.kt:42` or `Makefile:12`: a name and a line, not a scheme. The name has a dot or is a file
         * known without one, so `tel:12345` stays a scheme.
         */
        private fun isNameAndLine(link: String): Boolean {
            val name = NAME_AND_LINE.matchEntire(link)?.groupValues?.get(1) ?: return false
            return '.' in name || name.lowercase() in GatewayFileKind.CODE_NAMES
        }

        private val NAME_AND_LINE = Regex("""^([^/:#]+):\d+(?::\d+)?$""")

        /**
         * [path] with empty and `.` segments dropped and each `..` taking off the one before it. Past `/` (or a
         * drive's `C:/`) there's nothing to take off; past `~/` the `..` stays, as the gateway knows where home is.
         */
        internal fun normalize(path: String): String {
            val root = when {
                path.startsWith("~/") -> "~/"
                path.startsWith("/") -> "/"
                DRIVE_ROOT.containsMatchIn(path) -> path.substring(0, 3)
                else -> ""
            }
            val segments = ArrayList<String>()
            for (segment in path.substring(root.length).split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> when {
                        segments.isNotEmpty() && segments.last() != ".." -> segments.removeAt(segments.lastIndex)
                        root == "~/" || root.isEmpty() -> segments += segment
                    }
                    else -> segments += segment
                }
            }
            return root + segments.joinToString("/")
        }

        private val DRIVE_ROOT = Regex("""^[A-Za-z]:/""")

        /**
         * `%XX` escapes as UTF-8; a `%` that starts no escape stays as it is. Literal text between escapes is
         * encoded a run at a time, so a character outside the BMP (an emoji) keeps both halves of its pair.
         */
        private fun percentDecode(text: String): String {
            if ('%' !in text) return text
            val out = ArrayList<Byte>(text.length)
            var literal = 0
            var i = 0
            fun flush(end: Int) {
                if (end > literal) text.substring(literal, end).encodeToByteArray().forEach { out += it }
            }
            while (i < text.length) {
                val escape = text[i] == '%' && i + 2 <= text.lastIndex && text[i + 1].isHexDigit() && text[i + 2].isHexDigit()
                if (escape) {
                    flush(i)
                    out += text.substring(i + 1, i + 3).toInt(16).toByte()
                    i += 3
                    literal = i
                } else {
                    i++
                }
            }
            flush(text.length)
            return out.toByteArray().decodeToString()
        }

        private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}

/** How a gateway file is shown: rendered, as text, as a picture, or only as a card to save or share. */
enum class GatewayFileKind(val label: String) {
    Markdown("Markdown"),
    Json("JSON"),
    Table("Table"),
    Code("Code"),
    Text("Text"),
    Image("Picture"),

    /** Pages drawn from the whole file, not the gateway's text preview. */
    Pdf("PDF"),
    Other("File"),
    ;

    /** Shown in the file viewer at all; the rest go straight to a card with Save and Share. */
    val previewable: Boolean get() = this != Image && this != Other

    companion object {
        fun of(name: String): GatewayFileKind {
            val lower = name.lowercase()
            val ext = lower.substringAfterLast('.', "")
            return when {
                ext in MARKDOWN -> Markdown
                ext in JSON -> Json
                ext in TABLE -> Table
                ext in IMAGE -> Image
                ext == "pdf" -> Pdf
                ext in CODE || lower in CODE_NAMES -> Code
                ext in TEXT || ext.isEmpty() || lower.startsWith(".") -> Text
                ext in BINARY -> Other
                // An unknown extension is tried as text: the gateway says when it's binary.
                else -> Text
            }
        }

        private val MARKDOWN = setOf("md", "markdown", "mdx", "mdown", "mkd")
        private val JSON = setOf("json", "jsonc", "json5", "geojson", "ipynb")
        private val TABLE = setOf("csv", "tsv")
        // What Android decodes: HEIC/HEIF from Android 9, AVIF from 12; on an older phone the viewer says it
        // can't show the picture and still saves or shares it. TIFF has a small decoder of Herald's own (baseline
        // strips only; anything else gets the same message), and SVG is shown as code.
        private val IMAGE = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif", "ico", "tif", "tiff")
        private val TEXT = setOf("txt", "log", "text", "out", "err", "rst", "adoc", "org", "env", "lock", "jsonl", "ndjson")
        private val CODE = setOf(
            "kt", "kts", "java", "py", "js", "mjs", "cjs", "jsx", "ts", "tsx", "go", "rs", "rb", "php", "c", "h", "cc", "cpp",
            "hpp", "cs", "swift", "m", "scala", "lua", "r", "pl", "sh", "bash", "zsh", "fish", "ps1", "sql", "html", "htm",
            "css", "scss", "less", "vue", "svelte", "xml", "svg", "yaml", "yml", "toml", "ini", "cfg", "conf", "properties",
            "gradle", "dart", "ex", "exs", "hs", "zig", "nim", "tf", "proto", "graphql", "diff", "patch",
        )
        internal val CODE_NAMES = setOf("dockerfile", "makefile", "justfile", "procfile", "gemfile", "rakefile")
        private val BINARY = setOf(
            "zip", "gz", "tgz", "bz2", "xz", "7z", "rar", "tar", "apk", "aab", "jar", "exe", "dll", "so", "dylib", "bin",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "epub", "mp3", "wav", "ogg", "opus", "flac", "m4a",
            "aac", "mp4", "mov", "mkv", "webm", "avi", "psd", "ttf", "otf", "woff", "woff2", "sqlite", "db",
        )
    }
}

/**
 * The start of a gateway file as text (`GET /api/fs/read-text`, up to the gateway's 512 KB preview
 * cap). [byteSize] is the whole file's size; [truncated] says the text stops short of it.
 */
data class FileText(
    val text: String,
    val byteSize: Long,
    val truncated: Boolean,
    val binary: Boolean,
    val language: String? = null,
)
