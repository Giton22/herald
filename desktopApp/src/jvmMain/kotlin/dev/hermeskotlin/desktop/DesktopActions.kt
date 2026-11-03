package dev.hermeskotlin.desktop

import dev.hermeskotlin.core.chat.AppLink

/** The desktop-only launcher action; other links retain their existing chat/notification routing. */
internal fun handleQuickEntryLink(link: String, onOpen: () -> Unit): Boolean {
    if (link.any { it.code > 0x7f }) return false
    if (link.lowercase() != "${AppLink.DESKTOP_SCHEME}://quick-entry") return false
    onOpen()
    return true
}
