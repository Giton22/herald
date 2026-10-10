package dev.hermeskotlin.ui.platform

import dev.hermeskotlin.core.chat.AppLink
import dev.hermeskotlin.core.chat.ChatHost
import dev.hermeskotlin.core.chat.ChatMessage
import dev.hermeskotlin.core.chat.ChatSession
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.chat.TurnOutcome
import dev.hermeskotlin.core.settings.AppSettings
import dev.hermeskotlin.core.settings.SettingsStore
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionNone
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject

/**
 * The open chat's iOS side, as ChatNotifier is on Android. iOS suspends an app shortly after it leaves the
 * screen, so a running turn asks for a background task: the reply keeps streaming for the half minute or so
 * iOS allows. While Herald is out of sight, a question or an approval the agent waits on and a finished reply
 * become local notifications; tapping one opens the chat. Longer turns need push, which iOS doesn't have yet.
 */
internal class IosChatNotifier(
    private val host: ChatHost,
    private val settings: SettingsStore,
    private val links: (String) -> Boolean,
    private val scope: CoroutineScope,
) {
    private val center = UNUserNotificationCenter.currentNotificationCenter()
    private val visible = MutableStateFlow(UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive)
    private var task: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid
    private var asked = false

    /** Kept here: the notification center holds its delegate weakly. */
    private val delegate = object : NSObject(), UNUserNotificationCenterDelegateProtocol {
        // Herald in sight shows the chat itself: no banner over it.
        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            willPresentNotification: UNNotification,
            withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
        ) = withCompletionHandler(UNNotificationPresentationOptionNone)

        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            didReceiveNotificationResponse: UNNotificationResponse,
            withCompletionHandler: () -> Unit,
        ) {
            (didReceiveNotificationResponse.notification.request.content.userInfo[LINK] as? String)?.let(links)
            withCompletionHandler()
        }
    }

    init {
        center.delegate = delegate
        val main = NSOperationQueue.mainQueue
        NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, main) { _ -> visible.value = false }
        NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationDidBecomeActiveNotification, null, main) { _ ->
            visible.value = true
            // Back in Herald: what the notifications said is on screen now.
            center.removeAllDeliveredNotifications()
        }
        scope.launch(Dispatchers.Main) {
            host.session.collectLatest { session ->
                if (session == null) endTask() else follow(session)
            }
        }
    }

    private suspend fun follow(session: ChatSession) {
        val notified = mutableSetOf<String>()
        var previous: ChatState? = null
        try {
            combine(session.state, visible, settings.settings) { state, visible, stored -> Triple(state, visible, stored ?: AppSettings()) }
                .collect { (state, visible, prefs) ->
                    if (state.running) beginTask() else endTask()
                    // Asked in sight, on the first turn: a prompt can't come up from the background.
                    if (state.running && visible && (prefs.notifyReplies || prefs.notifyRequests)) askOnce()

                    if (visible) {
                        notified.clear()
                    } else if (prefs.notifyRequests) {
                        for (request in state.inputRequests) {
                            if (notified.add(request.id)) post(request.id, state, requestTitle(request), requestBody(request))
                        }
                    }
                    val open = state.inputRequests.mapTo(HashSet()) { it.id }
                    notified.filter { it !in open }.forEach {
                        notified.remove(it)
                        center.removeDeliveredNotificationsWithIdentifiers(listOf(it))
                    }

                    // Only a stored chat: the notification opens it, as on Android.
                    val storedId = state.storedSessionId
                    if (storedId != null && previous?.running == true && !state.running && !visible && prefs.notifyReplies) {
                        val reply = state.messages.lastOrNull() as? ChatMessage.Assistant
                        if (reply != null && reply.outcome != TurnOutcome.Interrupted) {
                            val failed = reply.outcome == TurnOutcome.Error
                            val text = (if (failed) reply.error ?: reply.text else reply.text).toPlainText()
                                .ifBlank { if (failed) "The turn failed." else "Done." }
                            post(REPLY_PREFIX + storedId, state, state.title ?: "Hermes", text)
                        }
                    }
                    previous = state
                }
        } finally {
            endTask()
        }
    }

    private fun post(id: String, state: ChatState, title: String, body: String) {
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(body.take(MAX_BODY))
            setSound(UNNotificationSound.defaultSound)
            state.storedSessionId?.let {
                setThreadIdentifier(it)
                setUserInfo(mapOf<Any?, Any?>(LINK to "${AppLink.SCHEME}://session/${it.encodeURLPathPart()}"))
            }
        }
        center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(id, content, trigger = null), withCompletionHandler = null)
    }

    private fun askOnce() {
        if (asked) return
        asked = true
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge) { _, _ -> }
    }

    private fun beginTask() {
        if (task != UIBackgroundTaskInvalid) return
        task = UIApplication.sharedApplication.beginBackgroundTaskWithName("Finish the reply") { endTask() }
    }

    private fun endTask() {
        if (task == UIBackgroundTaskInvalid) return
        UIApplication.sharedApplication.endBackgroundTask(task)
        task = UIBackgroundTaskInvalid
    }

    private fun requestTitle(request: InputRequest): String = when (request) {
        is InputRequest.Approval -> "Approve this command?"
        is InputRequest.Clarify -> "Hermes has a question"
        is InputRequest.Secret -> when (request.kind) {
            InputRequest.Secret.Kind.Sudo -> "Hermes needs the sudo password"
            InputRequest.Secret.Kind.VaultUnlock -> "Hermes needs your vault unlocked"
            InputRequest.Secret.Kind.VaultCode -> "Hermes needs a sign-in code"
            InputRequest.Secret.Kind.Secret -> "Hermes needs a value"
        }
        is InputRequest.VaultSaveLogin -> request.title
    }

    // Never a secret itself: those are only ever typed in the app.
    private fun requestBody(request: InputRequest): String = when (request) {
        is InputRequest.Approval -> request.command.ifBlank { request.description }
        is InputRequest.Clarify -> request.questions.singleOrNull()?.question ?: "${request.questions.size} questions"
        is InputRequest.Secret -> request.prompt
        is InputRequest.VaultSaveLogin -> "Open Herald to save it."
    }

    /** Markdown reads badly in a notification; keep the words, drop the markup (as Android does). */
    private fun String.toPlainText(): String = this
        .replace(CODE_FENCE, "")
        .replace(HEADING, "")
        .replace(BOLD, "$2")
        .replace(INLINE_CODE, "$1")
        .replace(LINK_OR_IMAGE, "$1")
        .replace(BLANK_LINES, "\n\n")
        .trim()

    private companion object {
        val CODE_FENCE = Regex("```[^\\n]*\\n?")
        val HEADING = Regex("(?m)^#{1,6}\\s+")
        val BOLD = Regex("(\\*\\*|__)(.+?)\\1")
        val INLINE_CODE = Regex("`([^`]+)`")
        val LINK_OR_IMAGE = Regex("!?\\[([^]]*)]\\([^)]*\\)")
        val BLANK_LINES = Regex("\\n{3,}")
        const val LINK = "link"
        const val REPLY_PREFIX = "reply-"
        const val MAX_BODY = 1_000
    }
}
