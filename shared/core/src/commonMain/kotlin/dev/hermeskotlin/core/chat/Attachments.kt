package dev.hermeskotlin.core.chat

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

enum class AttachmentKind { Image, Pdf, File }

/**
 * A file picked on this device, waiting to go out with the next prompt. Images are queued on the
 * session (`image.attach_bytes`); PDFs and other files land in the session workspace as an `@file:`
 * reference in the prompt text (`file.attach`).
 *
 * A small one is held in [bytes] and goes inside the WebSocket message. One past [MAX_INLINE_BYTES]
 * goes over HTTP first ([AttachmentUploads]) and is attached by its path on the gateway; it can come as
 * a [source], read only as it's sent, so a large file is never held in memory whole.
 */
class OutgoingAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
    /** A small JPEG to show while composing and in the sent bubble; images only. */
    val thumbnail: ByteArray? = null,
    /** The file, read as it's sent, in place of [bytes]. */
    val source: AttachmentSource? = null,
) {
    val kind: AttachmentKind
        get() = when {
            mimeType.startsWith("image/") -> AttachmentKind.Image
            mimeType == "application/pdf" || name.endsWith(".pdf", ignoreCase = true) -> AttachmentKind.Pdf
            else -> AttachmentKind.File
        }

    /** How large the file is, held or read as it's sent. */
    val size: Long get() = source?.size ?: bytes.size.toLong()

    /** Whether it can go inside a WebSocket message, base64, rather than over HTTP first. */
    val inline: Boolean get() = source == null && bytes.size <= MAX_INLINE_BYTES

    fun toShown() = ShownAttachment(id, name, kind, thumbnail, original = bytes.takeIf { kind == AttachmentKind.Image })

    @OptIn(ExperimentalEncodingApi::class)
    internal fun base64(): String = Base64.encode(bytes)

    companion object {
        /**
         * The most a file can be to go inside one WebSocket message. It goes base64 (4/3 its size), and the
         * message can't pass [dev.hermeskotlin.core.rpc.JsonRpcClient.MAX_FRAME_BYTES]: 12 MB is 16 MB on the
         * wire. Also all a hosted room takes, as rooms have no other way in.
         */
        const val MAX_INLINE_BYTES = 12_000_000

        /**
         * The most one file can be: what the gateway's upload takes (`_MANAGED_FILE_MAX_BYTES`, 100 MiB) in
         * millions of bytes. On a gateway without the upload, [MAX_INLINE_BYTES].
         */
        const val MAX_BYTES = 100_000_000

        /** [MAX_BYTES] as the app says it, in megabytes of a million bytes like the system's file sizes. */
        val MAX_SIZE = megabytes(MAX_BYTES.toLong())

        /** [MAX_INLINE_BYTES] as the app says it. */
        val MAX_INLINE_SIZE = megabytes(MAX_INLINE_BYTES.toLong())

        /** Per prompt, like a sensible composer tray. */
        const val MAX_COUNT = 10

        private fun megabytes(bytes: Long) = "${bytes / 1_000_000} MB"
    }
}

/** A file read only as it goes out: [open] starts a fresh read each time, of exactly [size] bytes. */
class AttachmentSource(val size: Long, val open: suspend () -> AttachmentReader)

/** One read of an [AttachmentSource]. */
interface AttachmentReader : AutoCloseable {
    /** Fills [buffer] from the start: the count read, or -1 at the end. */
    suspend fun read(buffer: ByteArray): Int
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
    /** The uploaded image itself, for opening it full size; only for images sent from here. */
    val original: ByteArray? = null,
)

/** Desktop's prompt when only images go out (`use-prompt-actions/submit.ts`). */
internal const val IMAGE_ONLY_PROMPT = "What do you see in this image?"

/** Where the expanded context starts: warnings first when there are any, then the attached blocks. */
private val CONTEXT_FOOTER = Regex("""(?:^|\n)--- (?:Context Warnings|Attached Context) ---\s*\n""")

/** An `@file:` reference anywhere in a line, as the attached-context block lists them. */
private val INLINE_FILE_REF = Regex("""@file:(?:(["'`])([^\r\n]+?)\1|(\S+))""")

/** A whole line `@file:<path>` or `@image:<path>`; paths with spaces are quoted. */
private val REF_LINE = Regex("""^@(file|image):(?:(["'`])([^\r\n]+?)\2|(\S+))\s*$""")

/**
 * Pulls the attachment reference lines out of a stored prompt: `@file:` lines that clients put in
 * front of it, and the `@image:` lines the gateway adds for the images that went with it. Returns
 * the attachments and the prompt without them, so history shows chips and thumbnails, not raw refs.
 */
internal fun splitAttachmentRefs(text: String, keyPrefix: String): Pair<List<ShownAttachment>, String> {
    val attachments = mutableListOf<ShownAttachment>()
    // The gateway appends what each @file: expanded to (agent/context_references.py); like Desktop,
    // show the files as chips and drop the pasted contents and warnings.
    val footer = CONTEXT_FOOTER.find(text)
    val body = if (footer == null) text else text.substring(0, footer.range.first)
    footer?.let { match ->
        INLINE_FILE_REF.findAll(text.substring(match.range.first)).forEach { ref ->
            val path = ref.groupValues[2].ifEmpty { ref.groupValues[3] }
            val name = path.substringAfterLast('/')
            if (attachments.none { it.name == name }) {
                val kind = if (name.endsWith(".pdf", ignoreCase = true)) AttachmentKind.Pdf else AttachmentKind.File
                attachments += ShownAttachment("$keyPrefix-c${attachments.size}", name, kind)
            }
        }
    }
    val kept = body.lines().filter { line ->
        val match = REF_LINE.matchEntire(line.trim()) ?: return@filter true
        val path = match.groupValues[3].ifEmpty { match.groupValues[4] }
        val name = path.substringAfterLast('/')
        if (attachments.any { it.name == name && it.kind != AttachmentKind.Image }) return@filter false
        attachments += when {
            match.groupValues[1] == "image" -> ShownAttachment("$keyPrefix-r${attachments.size}", name, AttachmentKind.Image, gatewayPath = path)
            name.endsWith(".pdf", ignoreCase = true) -> ShownAttachment("$keyPrefix-r${attachments.size}", name, AttachmentKind.Pdf)
            else -> ShownAttachment("$keyPrefix-r${attachments.size}", name, AttachmentKind.File)
        }
        false
    }
    // With files and images together, the images' directives come last, after the pasted context
    // (session_history.py); only the closing run of lines, never one inside a pasted file.
    if (footer != null) {
        text.lines().asReversed().map { it.trim() }.takeWhile { it.isEmpty() || REF_LINE.matches(it) }.asReversed().forEach { line ->
            val match = REF_LINE.matchEntire(line)?.takeIf { it.groupValues[1] == "image" } ?: return@forEach
            val path = match.groupValues[3].ifEmpty { match.groupValues[4] }
            attachments += ShownAttachment("$keyPrefix-i${attachments.size}", path.substringAfterLast('/'), AttachmentKind.Image, gatewayPath = path)
        }
    }
    if (attachments.isEmpty() && footer == null) return emptyList<ShownAttachment>() to text
    return attachments to kept.joinToString("\n").trim()
}
