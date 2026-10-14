package dev.hermeskotlin.core.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val PrettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

/**
 * [text] indented two spaces a level when it's one JSON document, for a file written on a single line;
 * null when it isn't JSON (JSON Lines, a cut-off preview, comments in a `.jsonc`).
 */
fun prettyJson(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || (trimmed[0] != '{' && trimmed[0] != '[')) return null
    return runCatching { PrettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(trimmed)) }.getOrNull()
}

/** A CSV or TSV file read into rows; [more] says rows past [rows] were left out. */
data class DelimitedTable(val rows: List<List<String>>, val more: Boolean) {
    val columns: Int get() = rows.maxOfOrNull { it.size } ?: 0
}

/**
 * Reads [text] as rows of [delimiter]-separated cells, as RFC 4180 has it: a cell in double quotes may
 * hold the delimiter, line breaks and `""` for a quote. Stops after [maxRows]; a last line cut off by a
 * truncated preview is dropped rather than shown half.
 */
fun parseDelimited(text: String, delimiter: Char, maxRows: Int = 1_000, truncated: Boolean = false): DelimitedTable {
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0
    var more = false
    fun endRow() {
        row += cell.toString()
        cell.clear()
        if (!(row.size == 1 && row[0].isEmpty())) rows += row
        row = ArrayList()
    }
    while (i < text.length) {
        val c = text[i]
        if (quoted) {
            if (c == '"') {
                if (i + 1 < text.length && text[i + 1] == '"') {
                    cell.append('"')
                    i++
                } else {
                    quoted = false
                }
            } else {
                cell.append(c)
            }
        } else {
            when (c) {
                '"' -> if (cell.isEmpty()) quoted = true else cell.append(c)
                delimiter -> {
                    row += cell.toString()
                    cell.clear()
                }
                '\r' -> Unit
                '\n' -> {
                    endRow()
                    if (rows.size >= maxRows) {
                        more = i + 1 < text.length
                        break
                    }
                }
                else -> cell.append(c)
            }
        }
        i++
    }
    if (i >= text.length && rows.size < maxRows) {
        if (truncated) {
            more = true
        } else if (cell.isNotEmpty() || row.isNotEmpty()) {
            endRow()
        }
    }
    return DelimitedTable(rows, more)
}
