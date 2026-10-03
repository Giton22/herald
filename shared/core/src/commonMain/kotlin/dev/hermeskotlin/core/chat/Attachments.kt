package dev.hermeskotlin.core.chat

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

enum class AttachmentKind { Image, Pdf, File }

/**
 * A file picked on this device, waiting to go out with the next prompt. Images are queued on the
 * session (`image.attach_bytes`), PDFs are rendered to page images (`pdf.attach`), and anything else
 * lands in the session workspace as an `@file:` reference in the prompt text (`file.attach`).
 */
class OutgoingAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
    /** A small JPEG to show while composing and in the sent bubble; images only. */
    val thumbnail: ByteArray? = null,
) {
    val kind: AttachmentKind
        get() = when {
            mimeType.startsWith("image/") -> AttachmentKind.Image
            mimeType == "application/pdf" || name.endsWith(".pdf", ignoreCase = true) -> AttachmentKind.Pdf
            else -> AttachmentKind.File
        }

    fun toShown() = ShownAttachment(id, name, kind, thumbnail)

    @OptIn(ExperimentalEncodingApi::class)
    internal fun base64(): String = Base64.encode(bytes)

    companion object {
        /** `image.attach_bytes` refuses more; other uploads are kept to the same size. */
        const val MAX_BYTES = 25 * 1024 * 1024

        /** Per prompt, like a sensible composer tray. */
        const val MAX_COUNT = 10
    }
}

/** An attachment as a message shows it; history only knows names and kinds, not the bytes. */
class ShownAttachment(
    val key: String,
    val name: String,
    val kind: AttachmentKind,
    val thumbnail: ByteArray? = null,
)

/** Desktop's prompt when only images go out (`use-prompt-actions/submit.ts`). */
internal const val IMAGE_ONLY_PROMPT = "What do you see in this image?"

private val FILE_REF = Regex("""^@file:(?:"([^"]+)"|(\S+))\s*$""")

/**
 * Splits the `@file:` reference lines Hermes clients put in front of a prompt back into files, so a
 * stored prompt shows its attachments as chips instead of raw references.
 */
internal fun splitFileRefs(text: String): Pair<List<String>, String> {
    val lines = text.lines()
    val refs = mutableListOf<String>()
    var index = 0
    while (index < lines.size) {
        val match = FILE_REF.matchEntire(lines[index].trim()) ?: break
        refs += (match.groupValues[1].ifEmpty { match.groupValues[2] }).substringAfterLast('/')
        index++
    }
    if (refs.isEmpty()) return emptyList<String>() to text
    return refs to lines.drop(index).joinToString("\n").trim()
}
