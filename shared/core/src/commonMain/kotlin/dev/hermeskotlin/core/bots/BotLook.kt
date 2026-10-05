package dev.hermeskotlin.core.bots

import kotlin.math.abs
import kotlin.math.roundToInt

/** The outline a bot's face is drawn in when it has no picture. */
enum class BotShape { Circle, Squircle, Pill, Triangle, Hexagon, Cloud, Drop }

/** How a bot without a picture looks: a colored [shape] with eyes. [color] is `0xRRGGBB`. */
data class BotLook(val shape: BotShape, val color: Int)

/**
 * The look Desktop gives [bot] (avatar.tsx `botAppearance`): what the user picked, else a shape and hue
 * derived from the profile name, so the same bot looks the same on every client. The default bot wears a
 * violet squircle until someone changes it.
 */
fun botLook(bot: Bot): BotLook {
    val meta = bot.meta
    if (bot.name.equals(Bot.DEFAULT, ignoreCase = true) && !meta.custom) return BotLook(BotShape.Squircle, PRIMARY_COLOR)
    val shape = meta.shape?.let(::shapeNamed) ?: defaultShapeFor(bot.name)
    val color = meta.color?.let(::parseCssColor) ?: profileColor(bot.name)
    return BotLook(shape, color)
}

/**
 * Whether to show [picture], the bot's avatar asset, rather than draw its face. Desktop uploads a 160×160
 * PNG copy of every drawn face (for other bots' message notes), and that copy goes stale when the look
 * changes. So when the bot's metadata says how to draw it, the copy is passed over, as Desktop does
 * (avatar-image.ts `isBackfilledFacePng`); without that, the copy is the only record of Desktop's look.
 * A photo or a picture of any other size is always shown.
 */
fun showsPicture(bot: Bot, picture: ByteArray): Boolean {
    val meta = bot.meta
    return meta.imageKind == "photo" || meta.shape == null || !isFaceCopy(picture)
}

/** A 160×160 PNG: the size Desktop draws face copies at (pets are 96×104, uploads 256). */
internal fun isFaceCopy(png: ByteArray): Boolean {
    if (png.size < 24 || png[1] != 'P'.code.toByte() || png[2] != 'N'.code.toByte() || png[3] != 'G'.code.toByte()) return false
    fun int(at: Int) = (0 until 4).fold(0) { acc, i -> (acc shl 8) or (png[at + i].toInt() and 0xFF) }
    return int(16) == FACE_COPY_EDGE && int(20) == FACE_COPY_EDGE
}

private const val FACE_COPY_EDGE = 160

/** Desktop's stock shapes, picked by a hash of the name (avatar.tsx `defaultShapeFor`). */
fun defaultShapeFor(name: String): BotShape = DEFAULT_SHAPES[(nameHash(name) % DEFAULT_SHAPES.size.toUInt()).toInt()]

/** Desktop's per-profile hue (lib/profile-color.ts): `hsl(hash % 360, 68%, 58%)`. */
fun profileColor(name: String): Int {
    val key = name.trim()
    if (key.isEmpty() || key == Bot.DEFAULT) return PRIMARY_COLOR
    return hsl((nameHash(key) % 360u).toDouble(), 0.68, 0.58)
}

/** `#rgb`, `#rrggbb` or `hsl(h s% l%)` (commas allowed), as Desktop stores colors; null for anything else. */
fun parseCssColor(value: String): Int? {
    val text = value.trim().lowercase()
    if (text.startsWith("#")) {
        val hex = text.drop(1)
        val full = when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString("")
            6 -> hex
            else -> return null
        }
        return full.toIntOrNull(16)
    }
    val hsl = HSL.matchEntire(text) ?: return null
    val (h, s, l) = hsl.destructured
    return hsl(h.toDouble(), s.toDouble() / 100, l.toDouble() / 100)
}

/** Unknown names (Desktop's blobatars, sigils, solids) fall back to the plain stock shape for the name. */
private fun shapeNamed(name: String): BotShape? = when (name.lowercase()) {
    "circle", "blob" -> BotShape.Circle
    "squircle" -> BotShape.Squircle
    "pill" -> BotShape.Pill
    "triangle" -> BotShape.Triangle
    "hexagon" -> BotShape.Hexagon
    "cloud" -> BotShape.Cloud
    "drop" -> BotShape.Drop
    else -> null
}

/** `hash * 31 + code unit`, wrapping at 32 bits, as both Desktop helpers compute it. */
private fun nameHash(name: String): UInt = name.fold(0u) { hash, ch -> hash * 31u + ch.code.toUInt() }

private fun hsl(hue: Double, saturation: Double, lightness: Double): Int {
    val c = (1 - abs(2 * lightness - 1)) * saturation
    val h = ((hue % 360) + 360) % 360 / 60
    val x = c * (1 - abs(h % 2 - 1))
    val (r, g, b) = when {
        h < 1 -> Triple(c, x, 0.0)
        h < 2 -> Triple(x, c, 0.0)
        h < 3 -> Triple(0.0, c, x)
        h < 4 -> Triple(0.0, x, c)
        h < 5 -> Triple(x, 0.0, c)
        else -> Triple(c, 0.0, x)
    }
    val m = lightness - c / 2
    fun channel(v: Double) = ((v + m) * 255).roundToInt().coerceIn(0, 255)
    return (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}

private val DEFAULT_SHAPES = listOf(BotShape.Circle, BotShape.Squircle, BotShape.Pill, BotShape.Triangle, BotShape.Hexagon, BotShape.Cloud, BotShape.Drop)
private val HSL = Regex("""hsla?\(\s*(-?[\d.]+)(?:deg)?[\s,]+([\d.]+)%[\s,]+([\d.]+)%.*\)""")
private const val PRIMARY_COLOR = 0x8B5CF6
