package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.gateway.GatewayUrl
import kotlinx.coroutines.CancellationException

/** Length prefixes keep gateway, nullable profile and the exact source path unambiguous. */
internal fun downloadedAttachmentKey(identity: Any?, source: String): String? {
    val owner = identity as? Pair<*, *> ?: return null
    val gateway = when (val value = owner.first) {
        is GatewayUrl -> value.value
        is String -> value
        else -> return null
    }
    if (owner.second != null && owner.second !is String) return null
    fun field(value: String?): String = if (value == null) "-1:" else "${value.length}:$value"
    return "attachment-v1:${field(gateway)}${field(owner.second as String?)}${field(source)}"
}

internal data class AttachmentUseResult(val saved: Boolean, val notice: String? = null)

/** The card's click flow: try the durable local URI before touching the gateway. */
internal suspend fun useDownloadedAttachment(
    actions: MediaActions,
    sourceKey: String?,
    name: String,
    share: Boolean,
    download: suspend () -> FileDownload,
): AttachmentUseResult {
    try {
        if (sourceKey != null) {
            val usedLocal = if (share) actions.shareSaved(sourceKey) else actions.openSaved(sourceKey)
            if (usedLocal) return AttachmentUseResult(saved = true)
        }
        // The HTTP client's own request timeout bounds this; a shorter one here cut off slow but working downloads.
        val bytes = when (val fetched = download()) {
            is FileDownload.Ready -> fetched.bytes
            is FileDownload.Failed -> return AttachmentUseResult(false, fetched.message)
        }
        if (share) {
            actions.share(bytes, name)
            return AttachmentUseResult(false)
        }
        if (sourceKey == null) return AttachmentUseResult(false, actions.save(bytes, name))
        val notice = actions.saveDownloaded(bytes, name, sourceKey)
        // A message isn't proof of success: unsupported platforms and failed writes have no URI.
        if (!actions.hasSaved(sourceKey)) return AttachmentUseResult(false, notice)
        return if (actions.openSaved(sourceKey)) AttachmentUseResult(true, notice)
        else AttachmentUseResult(actions.hasSaved(sourceKey), "$notice Couldn't open the local copy.")
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // An opener/share-sheet failure must not be mistaken for a missing file and downloaded again.
        val saved = try {
            sourceKey != null && actions.hasSaved(sourceKey)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        return AttachmentUseResult(saved, "Couldn't ${if (share) "share" else "open or download"} $name. Tap to try again.")
    }
}
