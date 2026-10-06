package dev.hermeskotlin.lab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** 4×4 ordered-dither thresholds. */
private val Bayer = arrayOf(
    intArrayOf(0, 8, 2, 10),
    intArrayOf(12, 4, 14, 6),
    intArrayOf(3, 11, 1, 9),
    intArrayOf(15, 7, 13, 5),
)

private fun threshold(x: Int, y: Int) = (Bayer[y and 3][x and 3] + 0.5f) / 16f

/**
 * A glow made of ordered-dither pixels instead of a blur: crisp squares, denser at [center] (fractions of the box),
 * thinning out to nothing at [radius] (a fraction of the box's longer side).
 */
@Composable
fun DitherGlow(
    color: Color,
    modifier: Modifier = Modifier,
    center: Offset = Offset(0.5f, 0.5f),
    radius: Float = 0.6f,
    strength: Float = 1f,
    cell: Dp = 2.5.dp,
    squash: Float = 1f,
    falloff: Float = 1.7f,
) {
    Canvas(modifier) {
        val c = cell.toPx()
        val cols = (size.width / c).toInt() + 1
        val rows = (size.height / c).toInt() + 1
        val cx = size.width * center.x
        val cy = size.height * center.y
        val r = max(size.width, size.height) * radius
        val px = Size(c * 0.78f, c * 0.78f)
        for (y in 0 until rows) for (x in 0 until cols) {
            val dx = x * c + c / 2 - cx
            val dy = (y * c + c / 2 - cy) * squash
            val d = hypot(dx, dy) / r
            if (d >= 1f) continue
            val v = (1f - d).pow(falloff) * strength
            if (v > threshold(x, y)) drawRect(color, Offset(x * c, y * c), px, alpha = (0.35f + v * 0.65f).coerceAtMost(1f))
        }
    }
}

/** Fills under a line with dither that thins out toward the bottom, for charts. [points] are 0..1 values, left to right. */
fun DrawScope.ditherArea(points: List<Float>, color: Color, cell: Float, top: Float = 0f) {
    val cols = (size.width / cell).toInt() + 1
    val rows = (size.height / cell).toInt() + 1
    val h = size.height - top
    for (x in 0 until cols) {
        val t = (x * cell / size.width) * (points.size - 1)
        val i = t.toInt().coerceIn(0, points.size - 2)
        val v = points[i] + (points[i + 1] - points[i]) * (t - i)
        val lineY = top + h * (1f - v)
        for (y in 0 until rows) {
            val py = y * cell
            if (py < lineY) continue
            val depth = (py - lineY) / (size.height - lineY + 1f)
            val k = (1f - depth).pow(1.4f) * 0.85f
            if (k > threshold(x, y)) drawRect(color, Offset(x * cell, py), Size(cell * 0.8f, cell * 0.8f), alpha = 0.3f + k * 0.5f)
        }
    }
}

/** Herald's agents, each a hand-drawn 8×8 sprite in its own color. */
enum class Agent(val handle: String, val color: Color, val sprite: List<String>) {
    Hermes(
        "hermes", Color(0xFF4A84FE),
        listOf(
            "#......#",
            "##.##.##",
            ".######.",
            "##.##.##",
            "########",
            ".######.",
            "..#..#..",
            ".##..##.",
        ),
    ),
    Scout(
        "scout", Color(0xFFB794FF),
        listOf(
            "..#..#..",
            "...##...",
            ".######.",
            "##.##.##",
            "########",
            "#.####.#",
            "#.#..#.#",
            "...##...",
        ),
    ),
    Ledger(
        "ledger", Color(0xFF41D6A4),
        listOf(
            "..####..",
            ".#....#.",
            "########",
            "#.#..#.#",
            "########",
            "#..##..#",
            "########",
            ".#....#.",
        ),
    ),
    Warden(
        "warden", Color(0xFFFF8A5B),
        listOf(
            "########",
            "#.####.#",
            "########",
            "##.##.##",
            "########",
            ".######.",
            "..####..",
            "...##...",
        ),
    ),
}

/** An agent's face: its sprite on a tile tinted with its color. */
@Composable
fun AgentFace(agent: Agent, size: Dp = 32.dp, modifier: Modifier = Modifier, glow: Boolean = false) {
    val tile = agent.color.copy(alpha = if (S.dark) 0.16f else 0.14f).compositeOver(S.s2)
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(tile)) {
        if (glow) DitherGlow(agent.color.copy(alpha = 0.5f), Modifier.matchParentSize(), radius = 0.7f, strength = 0.55f, cell = 2.dp)
        Canvas(Modifier.matchParentSize().padding(size * 0.2f)) { drawSprite(agent.sprite, agent.color) }
    }
}

fun DrawScope.drawSprite(sprite: List<String>, color: Color) {
    val n = sprite.size
    val c = min(size.width, size.height) / n
    for (y in 0 until n) for (x in sprite[y].indices) {
        if (sprite[y][x] == '#') drawRect(color, Offset(x * c, y * c), Size(c + 0.5f, c + 0.5f))
    }
}

/** The 3×3 thinking mark: the outer cells light up in turn; [phase] picks the frame. */
@Composable
fun PixelSpinner(color: Color, size: Dp = 12.dp, phase: Int = 2, modifier: Modifier = Modifier) {
    val ring = listOf(0 to 0, 1 to 0, 2 to 0, 2 to 1, 2 to 2, 1 to 2, 0 to 2, 0 to 1)
    Canvas(modifier.size(size)) {
        val c = this.size.width / 3
        val gap = c * 0.18f
        ring.forEachIndexed { i, (x, y) ->
            val age = (phase - i + 8) % 8
            val a = when (age) { 0 -> 1f; 1 -> 0.6f; 2 -> 0.35f; else -> 0.14f }
            drawRect(color, Offset(x * c + gap / 2, y * c + gap / 2), Size(c - gap, c - gap), alpha = a)
        }
        drawRect(color, Offset(c + gap / 2, c + gap / 2), Size(c - gap, c - gap), alpha = 0.5f)
    }
}

/** The checker mark carried over from Herald: corners and centre solid, the rest faint. */
@Composable
fun Checker(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val c = this.size.width / 3
        for (y in 0 until 3) for (x in 0 until 3) {
            drawRect(color, Offset(x * c, y * c), Size(c, c), alpha = if ((x + y) % 2 == 0) 1f else 0.3f)
        }
    }
}

/** Diagonal warning bands drawn as pixel staircases, reserved for actions that can't be undone. */
@Composable
fun HazardStripes(color: Color, modifier: Modifier = Modifier, cell: Dp = 2.dp) {
    Canvas(modifier) {
        val c = cell.toPx()
        val cols = (size.width / c).toInt() + 1
        val rows = (size.height / c).toInt() + 1
        for (y in 0 until rows) for (x in 0 until cols) {
            if (((x + y) / 4) % 2 == 0) drawRect(color, Offset(x * c, y * c), Size(c, c))
        }
    }
}

/** "Step N of M" as blocks: [done] solid, the next one [live] brighter, the rest empty. */
@Composable
fun Segments(total: Int, done: Int, color: Color, modifier: Modifier = Modifier, live: Boolean = true, height: Dp = 4.dp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(total) { i ->
            val fill = when {
                i < done -> color
                i == done && live -> color.copy(alpha = 0.55f)
                else -> S.line2
            }
            Box(Modifier.weight(1f).height(height).clip(RoundedCornerShape(1.dp)).background(fill))
        }
    }
}
