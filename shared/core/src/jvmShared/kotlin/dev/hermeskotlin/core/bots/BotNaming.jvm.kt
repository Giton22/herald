package dev.hermeskotlin.core.bots

import java.text.Normalizer

internal actual fun stripAccents(ch: String): String =
    Normalizer.normalize(ch, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")

internal actual fun composeNfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
