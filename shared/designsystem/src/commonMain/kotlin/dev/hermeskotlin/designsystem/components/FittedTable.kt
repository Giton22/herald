package dev.hermeskotlin.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL

private val CELL_PADDING_X = 12.dp
private val CELL_PADDING_Y = 8.dp

/**
 * A Markdown table whose columns are as wide as their cells need, not all the same. The library gives
 * every column an equal share of a fixed width, so a column holding one emoji took as much room as one
 * holding a sentence, and the table ran off the side of a phone. Every cell shows all of its text on as
 * many lines as it needs. See [tableColumnWidths] for how the room is shared; a table that still doesn't
 * fit scrolls sideways.
 */
@Composable
internal fun FittedTable(content: String, node: ASTNode, style: TextStyle) {
    val rows = remember(node) {
        node.children.filter { it.type == HEADER || it.type == ROW }.map { row -> row.children.filter { it.type == CELL } }
    }
    if (rows.isEmpty()) return
    val dimens = LocalMarkdownDimens.current
    val shape = RoundedCornerShape(dimens.tableCornerSize)
    val faint = LocalMarkdownColors.current.dividerColor.fainter()
    BoxWithConstraints(
        Modifier
            .background(LocalMarkdownColors.current.tableBackground, shape)
            .border(dimens.dividerThickness, faint, shape)
            .clip(shape),
    ) {
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            TableGrid(rows, content, style, available, LocalMarkdownColors.current.dividerColor, dimens.dividerThickness)
        }
    }
}

@Composable
private fun TableGrid(
    rows: List<List<ASTNode>>,
    content: String,
    style: TextStyle,
    availableWidth: Int,
    dividerColor: Color,
    dividerThickness: Dp,
) {
    val columnCount = rows.maxOf { it.size }
    val grid = remember { GridOffsets() }
    Layout(
        content = {
            rows.forEachIndexed { rowIndex, cells ->
                cells.forEachIndexed { columnIndex, cell ->
                    val header = rowIndex == 0
                    Column(
                        Modifier
                            .padding(horizontal = CELL_PADDING_X, vertical = CELL_PADDING_Y)
                            // The row and column are enough for TalkBack; a heading per header cell would crowd
                            // out the reply's real headings when moving by heading.
                            .semantics { collectionItemInfo = CollectionItemInfo(rowIndex, 1, columnIndex, 1) },
                    ) {
                        MarkdownTableBasicText(
                            content = content,
                            cell = cell,
                            style = if (header) style.copy(fontWeight = FontWeight.Bold) else style,
                            maxLines = Int.MAX_VALUE,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
            }
        },
        modifier = Modifier
            .semantics { collectionInfo = CollectionInfo(rowCount = rows.size, columnCount = columnCount) }
            // Drawn rather than laid out as boxes: a box as tall as a table of thousands of lines asks for
            // Constraints Compose can't represent.
            .drawBehind { drawGrid(grid, dividerColor, dividerThickness.roundToPx().coerceAtLeast(1).toFloat()) },
    ) { measurables, _ ->
        val narrowest = IntArray(columnCount)
        val widest = IntArray(columnCount)
        var next = 0
        rows.forEach { cells ->
            for (column in cells.indices) {
                val cell = measurables[next++]
                narrowest[column] = maxOf(narrowest[column], cell.minIntrinsicWidth(Constraints.Infinity))
                widest[column] = maxOf(widest[column], cell.maxIntrinsicWidth(Constraints.Infinity))
            }
        }
        val widths = tableColumnWidths(narrowest, widest, availableWidth)

        next = 0
        val placeables = ArrayList<Placeable>(measurables.size)
        val rowHeights = IntArray(rows.size)
        rows.forEachIndexed { rowIndex, cells ->
            for (column in cells.indices) {
                val placeable = measurables[next++].measure(Constraints.fixedWidth(widths[column]))
                placeables += placeable
                rowHeights[rowIndex] = maxOf(rowHeights[rowIndex], placeable.height)
            }
        }
        val rowTops = tableOffsets(rowHeights)
        val columnLefts = tableOffsets(widths)
        grid.rowTops = rowTops
        grid.columnLefts = columnLefts

        layout(widths.sum(), rowHeights.sum()) {
            var index = 0
            rows.forEachIndexed { rowIndex, cells ->
                for (column in cells.indices) placeables[index++].placeRelative(columnLefts[column], rowTops[rowIndex])
            }
        }
    }
}

/** Where the last layout put each row and column, for drawing the grid behind the cells. */
private class GridOffsets {
    var rowTops by mutableStateOf(IntArray(0))
    var columnLefts by mutableStateOf(IntArray(0))
}

/**
 * A faint tint behind every second body row, so a long row can be followed across, then the lines between
 * rows (the one under the header a little stronger) and between columns.
 */
private fun DrawScope.drawGrid(grid: GridOffsets, dividerColor: Color, line: Float) {
    val rowTops = grid.rowTops
    val columnLefts = grid.columnLefts
    val bottomOf = { row: Int -> if (row + 1 < rowTops.size) rowTops[row + 1].toFloat() else size.height }
    for (row in 2 until rowTops.size step 2) {
        drawRect(dividerColor.fainter(0.3f), Offset(0f, rowTops[row].toFloat()), Size(size.width, bottomOf(row) - rowTops[row]))
    }
    for (row in 1 until rowTops.size) {
        val color = if (row == 1) dividerColor else dividerColor.fainter()
        drawRect(color, Offset(0f, rowTops[row] - line), Size(size.width, line))
    }
    for (column in 1 until columnLefts.size) {
        val start = columnLefts[column] - line
        val x = if (layoutDirection == LayoutDirection.Rtl) size.width - start - line else start
        drawRect(dividerColor.fainter(), Offset(x, 0f), Size(line, size.height))
    }
}

/** The same colour, fainter: the grid between cells should read as a hint, not as a frame. */
private fun Color.fainter(strength: Float = 0.55f): Color = copy(alpha = alpha * strength)
