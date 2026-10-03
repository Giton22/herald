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

/**
 * An attachment as a message shows it. One sent from here carries its [thumbnail]; one from history
 * knows only its name and, for an image the gateway stored, the [gatewayPath] to fetch it from.
 */
class ShownAttachment(
    val key: String,
    val name: String,
    val kind: AttachmentKind,
    val thumbnail: ByteArray? = null,
    val gatewayPath: String? = null,
)

/** Desktop's prompt when only images go out (`use-prompt-actions/submit.ts`). */
internal const val IMAGE_ONLY_PROMPT = "What do you see in this image?"

/** A whole line `@file:<path>` or `@image:<path>`; paths with spaces are quoted. */
private val REF_LINE = Regex("""^@(file|image):(?:"([^"]+)"|(\S+))\s*$""")

/**
 * Pulls the attachment reference lines out of a stored prompt: `@file:` lines that clients put in
 * front of it, and the `@image:` lines the gateway adds for the images that went with it. Returns
 * the attachments and the prompt without them, so history shows chips and thumbnails, not raw refs.
 */
internal fun splitAttachmentRefs(text: String, keyPrefix: String): Pair<List<ShownAttachment>, String> {
    val attachments = mutableListOf<ShownAttachment>()
    val kept = text.lines().filter { line ->
        val match = REF_LINE.matchEntire(line.trim()) ?: return@filter true
        val path = match.groupValues[2].ifEmpty { match.groupValues[3] }
        val name = path.substringAfterLast('/')
        attachments += when {
            match.groupValues[1] == "image" -> ShownAttachment("$keyPrefix-r${attachments.size}", name, AttachmentKind.Image, gatewayPath = path)
            name.endsWith(".pdf", ignoreCase = true) -> ShownAttachment("$keyPrefix-r${attachments.size}", name, AttachmentKind.Pdf)
            else -> ShownAttachment("$keyPrefix-r${attachments.size}", name, AttachmentKind.File)
        }
        false
    }
    if (attachments.isEmpty()) return emptyList<ShownAttachment>() to text
    return attachments to kept.joinToString("\n").trim()
}
