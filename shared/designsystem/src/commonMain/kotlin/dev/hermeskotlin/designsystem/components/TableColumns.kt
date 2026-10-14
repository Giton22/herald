package dev.hermeskotlin.designsystem.components

/**
 * The width of each column of a table, in pixels, given the narrowest each can be ([min], its longest
 * word) and the widest it would like to be ([max], its longest cell on one line), within [available].
 *
 * - When every column fits at its wanted width, it gets that, so a table of short cells stays narrow.
 * - When they don't, each column keeps its narrowest width and the room left is shared in proportion to
 *   how much more each would like. A column of emoji or numbers stays slim and the wordy ones wrap.
 * - When even the narrowest widths don't fit, the columns take those, and the table scrolls sideways.
 *
 * No column is wider than [available] (or [MAX_COLUMN_WIDTH] when the room is unbounded): a cell holding
 * one very long word, like a hash or a blob, breaks inside it instead of asking for a width Compose can't
 * represent.
 */
internal fun tableColumnWidths(min: IntArray, max: IntArray, available: Int): IntArray {
    val cap = available.coerceAtMost(MAX_COLUMN_WIDTH)
    val narrowest = IntArray(min.size) { min[it].coerceAtMost(cap) }
    val wanted = IntArray(min.size) { maxOf(narrowest[it], max[it].coerceAtMost(cap)) }
    if (wanted.sum() <= available) return wanted
    val floor = narrowest.sum()
    if (floor >= available) return narrowest

    val extra = available - floor
    val excess = IntArray(min.size) { wanted[it] - narrowest[it] }
    val excessTotal = excess.sum()
    val widths = IntArray(min.size) { narrowest[it] + (extra.toLong() * excess[it] / excessTotal).toInt() }

    // Rounding down leaves a few pixels over; hand them to the columns that wanted more, last first.
    var left = available - widths.sum()
    var column = min.lastIndex
    while (left > 0) {
        if (excess[column] > 0) {
            widths[column]++
            left--
        }
        column = if (column == 0) min.lastIndex else column - 1
    }
    return widths
}

/** The widest a column gets when the table's room is unbounded, well inside what Compose's Constraints can hold. */
internal const val MAX_COLUMN_WIDTH = 16_000

/**
 * Where each row (or column) starts, from the top (or start) of the table, given the [sizes] of the rows
 * (or columns): one pass, kept for placing the cells and the lines between them.
 */
internal fun tableOffsets(sizes: IntArray): IntArray {
    val offsets = IntArray(sizes.size)
    for (i in 1 until sizes.size) offsets[i] = offsets[i - 1] + sizes[i - 1]
    return offsets
}
