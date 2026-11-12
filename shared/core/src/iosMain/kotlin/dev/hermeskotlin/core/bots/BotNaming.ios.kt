package dev.hermeskotlin.core.bots

import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCompatibilityMapping
import platform.Foundation.precomposedStringWithCanonicalMapping

// Kotlin strings bridge to NSString, so Foundation's normalization is the platform's Normalizer.

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun stripAccents(ch: String): String =
    (ch as NSString).decomposedStringWithCompatibilityMapping.replace(COMBINING_MARKS, "")

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun composeNfc(text: String): String = (text as NSString).precomposedStringWithCanonicalMapping

private val COMBINING_MARKS = Regex("\\p{M}+")
