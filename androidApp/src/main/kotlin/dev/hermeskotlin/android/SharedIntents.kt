package dev.hermeskotlin.android

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import dev.hermeskotlin.core.chat.SharedContent

/**
 * What another app shared with Herald through the share sheet, or null when [intent] isn't a share.
 * Files come as `content://` URIs from other apps only: a `file://` path, or a URI into Herald's own
 * storage, could make Herald attach (and send) one of its own private files.
 */
fun sharedContent(context: Context, intent: Intent): SharedContent? {
    val action = intent.action
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
    val streams = buildList {
        if (action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
        } else {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::addAll)
        }
        // Some apps only put their files in the clip.
        if (isEmpty()) intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNullTo(this) { clip.getItemAt(it).uri } }
    }
    return SharedContent(
        text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
        subject = intent.getStringExtra(Intent.EXTRA_SUBJECT),
        files = streams.filter { isForeignContent(context, it) }.map(Uri::toString),
    )
}

private fun isForeignContent(context: Context, uri: Uri): Boolean {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
    val authority = uri.authority ?: return false
    val provider = context.packageManager.resolveContentProvider(authority, 0)
    return provider?.packageName != context.packageName
}
