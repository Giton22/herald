@file:OptIn(ExperimentalForeignApi::class)

package dev.hermeskotlin.ui.platform

import dev.hermeskotlin.core.bots.BotsApi
import dev.hermeskotlin.core.bots.forRoster
import dev.hermeskotlin.core.chat.AppLink
import dev.hermeskotlin.core.chat.ChatLinks
import dev.hermeskotlin.core.chat.ComposeDraft
import dev.hermeskotlin.core.chat.SharedContent
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.connection.GatewayConnection
import dev.hermeskotlin.core.network.HermesJson
import dev.hermeskotlin.core.platform.toByteArray
import dev.hermeskotlin.ui.chat.readSharedFiles
import io.ktor.http.encodeURLPathPart
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.timeIntervalSinceDate
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationShortcutIcon
import platform.UIKit.UIApplicationShortcutItem
import platform.UIKit.setShortcutItems

/**
 * What the system hands the app from outside, as MainActivity's intents are on Android: `hermes://` links,
 * Home Screen quick actions, and shares from the share extension. While App lock is up, the latest one waits
 * for the unlock.
 */
internal class IosLinks(
    private val links: ChatLinks,
    private val lock: IosAppLock,
    private val connection: GatewayConnection,
    private val bots: BotsApi,
    private val scope: CoroutineScope,
) {
    private var held: (() -> Unit)? = null
    private val reading = mutableSetOf<String>()

    init {
        scope.launch(Dispatchers.Main) {
            lock.timer.locked.filter { !it }.collect {
                val waiting = held ?: return@collect
                held = null
                waiting()
            }
        }
        // The share extension can't open the app: coming back to Herald picks its share up.
        NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) { _ ->
            latestShare()?.let(::takeShare)
        }
        scope.launch(Dispatchers.Main) {
            var connected = false
            connection.state.collect { state ->
                when {
                    state is ConnectionState.Connected && !connected -> {
                        connected = true
                        try {
                            publishBotShortcuts()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Kept as they were; the next connection tries again.
                        }
                    }
                    // Signed out, or on the way to another gateway: its bots aren't this one's.
                    (state is ConnectionState.Idle || state is ConnectionState.SessionExpired) && connected -> {
                        connected = false
                        UIApplication.sharedApplication.setShortcutItems(emptyList<UIApplicationShortcutItem>())
                    }
                }
            }
        }
    }

    /** Opens [url]; false when it isn't a link Herald knows. */
    fun open(url: String): Boolean {
        shareId(url)?.let {
            takeShare(it)
            return true
        }
        val link = AppLink.parse(url) ?: return false
        whenUnlocked { links.follow(link) }
        return true
    }

    private fun whenUnlocked(action: () -> Unit) {
        if (lock.timer.locked.value) held = action else action()
    }

    /** `hermes://share/<id>`: a share the extension left in the App Group's inbox. */
    private fun shareId(url: String): String? {
        if (!url.startsWith("${AppLink.SCHEME}://share/", ignoreCase = true)) return null
        return url.substringAfter("://share/").substringBefore('?').takeIf { SHARE_ID.matches(it) }
    }

    /**
     * Reads share [id] into a new chat's draft, the way Android reads a share intent: text and files go
     * through the composer's own pipeline (photos scaled down, size limits), nothing is sent. The inbox
     * entry goes once read, also when it couldn't be.
     */
    private fun takeShare(id: String) {
        val folder = inbox()?.URLByAppendingPathComponent(id) ?: return
        if (!reading.add(id)) return
        scope.launch {
            try {
                val draft = withContext(Dispatchers.IO) { readShare(folder) } ?: return@launch
                withContext(Dispatchers.Main) { whenUnlocked { links.newChat(draft) } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // An unreadable share is dropped: nothing to show for it.
            } finally {
                NSFileManager.defaultManager.removeItemAtURL(folder, error = null)
                withContext(Dispatchers.Main) { reading.remove(id) }
            }
        }
    }

    private fun readShare(folder: NSURL): ComposeDraft? {
        val manifest = folder.URLByAppendingPathComponent(MANIFEST)?.let { NSData.dataWithContentsOfURL(it) }?.toByteArray() ?: return null
        val share = InboxShare.parse(manifest.decodeToString())
        // Only `<n>/<name>` inside this share's folder: never a path that leads out of it.
        val names = share.files.filter { SHARED_FILE.matches(it) && it.substringAfter('/') !in setOf(".", "..") }
        val content = SharedContent(text = share.text, subject = share.subject, files = names)
        val left = buildList {
            if (share.tooLarge > 0) add(plural(share.tooLarge, "was too large to share", "were too large to share"))
            if (share.tooMany > 0) add(plural(share.tooMany, "was over the limit of 10", "were over the limit of 10"))
            if (share.unreadable > 0) add(plural(share.unreadable, "couldn't be read", "couldn't be read"))
        }
        if (content.isEmpty && left.isEmpty()) return null
        val urls = content.filesToAttach.mapNotNull { name ->
            folder.URLByAppendingPathComponent(name.substringBefore('/'))?.URLByAppendingPathComponent(name.substringAfter('/'))
        }
        val (files, error) = readSharedFiles(urls)
        val notice = (listOfNotNull(error, content.leftOverNotice) + left).joinToString(" ").ifEmpty { null }
        return ComposeDraft(text = content.draftText, attachments = files, notice = notice)
    }

    private fun plural(count: Int, one: String, many: String) = if (count == 1) "1 file $one." else "$count files $many."

    /**
     * The newest share still waiting, from the last half hour. The others are cleared away: one share at a
     * time, as on Android, rather than a backlog that opens chats each time the app comes back.
     */
    private fun latestShare(): String? {
        val inbox = inbox() ?: return null
        val manager = NSFileManager.defaultManager
        val entries = manager.contentsOfDirectoryAtURL(inbox, includingPropertiesForKeys = null, options = 0u, error = null)
            ?.filterIsInstance<NSURL>()
            .orEmpty()
            .filter { it.lastPathComponent !in reading }
        val now = NSDate()
        val (fresh, stale) = entries.partition { url ->
            val modified = url.path?.let { manager.attributesOfItemAtPath(it, error = null)?.get(NSFileModificationDate) as? NSDate }
            modified != null && now.timeIntervalSinceDate(modified) < SHARE_MAX_AGE_SECONDS
        }
        // Fresh `.<id>` folders are shares the extension is still writing: left alone until they're moved in.
        val shares = fresh.filter { it.lastPathComponent?.let(SHARE_ID::matches) == true }
        val newest = shares.maxByOrNull { it.path?.let { p -> (manager.attributesOfItemAtPath(p, error = null)?.get(NSFileModificationDate) as? NSDate)?.timeIntervalSince1970 } ?: 0.0 }
        (stale + shares).filter { it != newest }.forEach { manager.removeItemAtURL(it, error = null) }
        return newest?.lastPathComponent
    }

    private fun inbox(): NSURL? =
        NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(APP_GROUP)?.URLByAppendingPathComponent(INBOX)

    /** The Home Screen quick actions: a new chat, a voice chat, and the roster's first few bots. */
    private suspend fun publishBotShortcuts() {
        val roster = bots.roster().bots.forRoster().filterNot { it.meta.hidden }.take(MAX_BOT_SHORTCUTS)
        val items = roster.map { bot ->
            UIApplicationShortcutItem(
                type = "$SHORTCUT_PREFIX.bot",
                localizedTitle = bot.label,
                localizedSubtitle = null,
                icon = UIApplicationShortcutIcon.iconWithSystemImageName("person.crop.circle"),
                userInfo = mapOf<Any?, Any?>(SHORTCUT_URL to "${AppLink.SCHEME}://bot/${bot.name.encodeURLPathPart()}"),
            )
        }
        withContext(Dispatchers.Main) { UIApplication.sharedApplication.setShortcutItems(items) }
    }

    /**
     * What the share extension writes beside the files: `share.json`. [files] are `<n>/<name>`, each file in a
     * numbered folder of its own so it keeps its name; the counts are the files it left behind and why.
     */
    private class InboxShare(
        val text: String?,
        val subject: String?,
        val files: List<String>,
        val tooLarge: Int,
        val tooMany: Int,
        val unreadable: Int,
    ) {
        companion object {
            fun parse(json: String): InboxShare {
                val root = HermesJson.parseToJsonElement(json).jsonObject
                fun string(key: String) = (root[key] as? JsonPrimitive)?.contentOrNull
                fun count(key: String) = string(key)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                return InboxShare(
                    text = string("text"),
                    subject = string("subject"),
                    files = (root["files"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                    tooLarge = count("tooLarge"),
                    tooMany = count("tooMany"),
                    unreadable = count("unreadable"),
                )
            }
        }
    }

    companion object {
        /** Shared with the share extension, which writes into this group's container. */
        const val APP_GROUP = "group.dev.herald"

        /** The key in a quick action's user info that holds the link it opens. */
        const val SHORTCUT_URL = "url"

        private const val SHORTCUT_PREFIX = "dev.herald.ios"
        private const val INBOX = "Inbox"
        private const val MANIFEST = "share.json"
        private const val MAX_BOT_SHORTCUTS = 2
        private const val SHARE_MAX_AGE_SECONDS = 30 * 60.0
        private val SHARED_FILE = Regex("^[0-9]{1,2}/[^/]+$")
        private val SHARE_ID = Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")
    }
}
