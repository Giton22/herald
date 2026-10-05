package dev.hermeskotlin.android.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import dev.hermeskotlin.core.bots.Bot
import androidx.core.content.ContextCompat
import dev.hermeskotlin.android.R
import dev.hermeskotlin.core.chat.ApprovalChoice
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.connection.ConnectionState

/** Builds and posts every notification the app shows; the actions land in [NotificationActionReceiver]. */
class ChatNotifications(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        // Running turns once had a louder channel of their own (a Live Update); everything ongoing is quiet now.
        manager.deleteNotificationChannel(CHANNEL_WORKING_OLD)
        manager.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_CONNECTION, NotificationManagerCompat.IMPORTANCE_MIN)
                    .setName("Background connection")
                    .setDescription("Shown while Herald stays connected in the background, or works on a turn.")
                    .setShowBadge(false)
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_REQUESTS, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName("Approvals and questions")
                    .setDescription("The agent is waiting on you.")
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_REPLIES, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Finished replies")
                    .setDescription("A turn ended while the app was in the background.")
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_BOTS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Bot messages")
                    .setDescription("A bot wrote in its chat while the app was in the background.")
                    .build(),
            ),
        )
    }

    val canPost: Boolean
        get() = manager.areNotificationsEnabled() && (
            android.os.Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )

    /**
     * The ongoing notification of [ChatService], which Android requires while it keeps Herald up: one
     * constant, quiet line in the shade's silent section, no status-bar icon. It says only whether Herald
     * is connected, never what a turn is doing (that's the app's to show), so there's nothing in it to
     * go stale. "Turn off" turns off Notifications anywhere, which keeps it up between turns.
     */
    fun ongoing(connection: ConnectionState, pushAnywhere: Boolean = false): Notification {
        val text = when {
            connection is ConnectionState.Connected -> "Connected to Hermes"
            connection is ConnectionState.Connecting || connection is ConnectionState.Reconnecting -> "Reconnecting to Hermes…"
            // Notifications anywhere holds the ntfy stream instead, so no connection isn't something wrong.
            pushAnywhere -> "Bot messages reach you through Notifications anywhere"
            else -> "Not connected to Hermes"
        }
        return base(CHANNEL_CONNECTION)
            .setContentTitle("Herald")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            // Shown at once: Android holds a deferred one back, and updates to it with it.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Turn off", action(NotificationActionReceiver.ACTION_DISCONNECT, "disconnect"))
            .build()
    }

    fun postOngoing(connection: ConnectionState, pushAnywhere: Boolean = false) =
        post(null, WORKING_ID, ongoing(connection, pushAnywhere))

    /**
     * A question the agent is blocked on, answerable in place when it fits a notification. Every answer
     * asks for an unlock first: from a locked phone, Approve would run a command for whoever holds it.
     * False when Herald may not post notifications.
     */
    fun postRequest(storedSessionId: String?, title: String?, request: InputRequest): Boolean {
        val builder = base(CHANNEL_REQUESTS, storedSessionId, title)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSubText(title)
        when (request) {
            is InputRequest.Approval -> {
                val body = listOf(request.description, request.command).filter { it.isNotBlank() }.joinToString("\n\n")
                builder.setContentTitle("Approve this command?")
                    .setContentText(request.command.ifBlank { request.description })
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                // "Always" is too big a decision for a notification; it stays in the app.
                request.choices.filter { it != ApprovalChoice.Always }.forEach { choice ->
                    builder.addAction(
                        NotificationCompat.Action.Builder(
                            0,
                            choice.label,
                            action(NotificationActionReceiver.ACTION_APPROVE, request.id + choice.wire) {
                                putExtra(NotificationActionReceiver.EXTRA_REQUEST_ID, request.id)
                                putExtra(NotificationActionReceiver.EXTRA_CHOICE, choice.wire)
                            },
                        ).setAuthenticationRequired(true).build(),
                    )
                }
            }
            is InputRequest.Clarify -> {
                val question = request.questions.singleOrNull()
                builder.setContentTitle("Hermes has a question")
                    .setContentText(question?.question ?: "${request.questions.size} questions")
                    .setStyle(NotificationCompat.BigTextStyle().bigText(request.questions.joinToString("\n") { it.question }))
                if (question != null && !request.batch && !question.multiSelect) {
                    val input = RemoteInput.Builder(NotificationActionReceiver.KEY_TEXT)
                        .setLabel("Your answer")
                        .setChoices(question.choices.toTypedArray())
                        .build()
                    builder.addAction(
                        NotificationCompat.Action.Builder(
                            0,
                            "Answer",
                            action(NotificationActionReceiver.ACTION_CLARIFY, request.id, mutable = true) {
                                putExtra(NotificationActionReceiver.EXTRA_REQUEST_ID, request.id)
                            },
                        ).addRemoteInput(input).setAllowGeneratedReplies(false).setAuthenticationRequired(true).build(),
                    )
                }
            }
            // Passwords and codes are typed in the app only; a tap opens the chat.
            is InputRequest.Secret -> builder
                .setContentTitle(
                    when (request.kind) {
                        InputRequest.Secret.Kind.Sudo -> "Sudo password needed"
                        InputRequest.Secret.Kind.Secret -> "Secret needed"
                        InputRequest.Secret.Kind.VaultUnlock -> "Unlock your password manager"
                        InputRequest.Secret.Kind.VaultCode -> "Sign-in code needed"
                    },
                )
                .setContentText(request.command ?: request.prompt)
            is InputRequest.VaultSaveLogin -> builder
                .setContentTitle(request.title)
                .setContentText("Hermes has no login for ${request.site.ifBlank { "this site" }}. Open the chat to save one.")
        }
        return post(request.id, REQUEST_ID, builder.build())
    }

    fun cancelRequest(id: String) = manager.cancel(id, REQUEST_ID)

    /** A finished turn, with an inline Reply that sends the next prompt to the same chat. */
    fun postReply(storedSessionId: String, title: String?, text: String, failed: Boolean) {
        val preview = text.toPlainText().take(MAX_PREVIEW).ifBlank { if (failed) "The turn failed." else "Done." }
        val input = RemoteInput.Builder(NotificationActionReceiver.KEY_TEXT).setLabel("Message Hermes").build()
        val notification = base(CHANNEL_REPLIES, storedSessionId, title)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentTitle(if (failed) "Turn failed" else title?.takeIf { it.isNotBlank() } ?: "Hermes replied")
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .addAction(
                NotificationCompat.Action.Builder(
                    0,
                    "Reply",
                    action(NotificationActionReceiver.ACTION_REPLY, storedSessionId, mutable = true) {
                        putExtra(NotificationActionReceiver.EXTRA_SESSION_ID, storedSessionId)
                    },
                ).addRemoteInput(input).setAllowGeneratedReplies(true).setAuthenticationRequired(true).build(),
            )
            .build()
        post(storedSessionId, REPLY_ID, notification)
    }

    fun cancelReply(storedSessionId: String) = manager.cancel(storedSessionId, REPLY_ID)

    /**
     * A message from [bot], as Android shows a conversation: the bot's face and name, its words, and an
     * inline Reply that goes straight into its chat. Backed by the bot's shortcut, so it sits with
     * conversations and can be prioritised or bubbled like a person's.
     */
    fun postBotMessage(bot: Bot, picture: ByteArray?, storedSessionId: String, text: String, viaPush: Boolean = false) {
        val preview = text.toPlainText().take(MAX_PREVIEW).ifBlank { return }
        // One message can arrive both over the gateway socket and through push: the second copy is dropped.
        // The same words twice from one path are two messages (a bot repeating itself) and both notify.
        val now = System.currentTimeMillis()
        val key = bot.name + "\u0000" + preview.take(DEDUPE_PREFIX)
        synchronized(recentBotMessages) {
            recentBotMessages.values.removeAll { now - it.second > DEDUPE_WINDOW_MS }
            val earlier = recentBotMessages[key]
            if (earlier != null && earlier.first != viaPush) {
                recentBotMessages.remove(key)
                return
            }
            recentBotMessages[key] = viaPush to now
        }
        val shortcut = BotShortcuts.push(context, bot, picture)
        val person = BotShortcuts.person(bot, picture)
        val me = Person.Builder().setName("You").build()
        // Messages stack like a messaging app's until the bot's notification is opened or cleared.
        val history = (unreadBotMessages[bot.name].orEmpty() + (preview to System.currentTimeMillis())).takeLast(MAX_STACKED)
        unreadBotMessages[bot.name] = history
        val input = RemoteInput.Builder(NotificationActionReceiver.KEY_TEXT).setLabel("Message ${bot.label}").build()
        val notification = NotificationCompat.Builder(context, CHANNEL_BOTS)
            // The bot's own silhouette in the status bar, not Herald's.
            .setSmallIcon(BotIcons.statusIcon(bot))
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentIntent(openBot(bot, storedSessionId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setShortcutId(shortcut)
            .setLargeIcon(BotIcons.icon(bot, picture).toIcon(context))
            // One-to-one: the conversation is the bot, its face and name the notification's own.
            .setStyle(
                NotificationCompat.MessagingStyle(me).setGroupConversation(false).also { style ->
                    history.forEach { (text, at) -> style.addMessage(text, at, person) }
                },
            )
            .setNumber(history.size)
            .setDeleteIntent(action(NotificationActionReceiver.ACTION_BOT_CLEARED, bot.name) { putExtra(NotificationActionReceiver.EXTRA_BOT, bot.name) })
            .addAction(
                NotificationCompat.Action.Builder(
                    0,
                    "Reply",
                    action(NotificationActionReceiver.ACTION_BOT_REPLY, bot.name, mutable = true) {
                        putExtra(NotificationActionReceiver.EXTRA_BOT, bot.name)
                        putExtra(NotificationActionReceiver.EXTRA_SESSION_ID, storedSessionId)
                    },
                ).addRemoteInput(input).setAllowGeneratedReplies(true).setAuthenticationRequired(true)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).build(),
            )
            .build()
        post(bot.name, BOT_ID, notification)
    }

    fun cancelBot(name: String) {
        unreadBotMessages.remove(name)
        manager.cancel(name, BOT_ID)
    }

    /** The bot's notification was swiped away: its stack starts over. */
    fun forgetBot(name: String) {
        unreadBotMessages.remove(name)
    }

    /** Each bot's messages shown in its notification, oldest first, with when they came. */
    private val unreadBotMessages = mutableMapOf<String, List<Pair<String, Long>>>()

    /** Bot messages posted lately: bot and opening words → (came through push, when). */
    private val recentBotMessages = LinkedHashMap<String, Pair<Boolean, Long>>()

    /** "Send a test" from the push setup: proof that a push got through ntfy and decrypted. */
    fun postPushTest() = post(
        null,
        PUSH_TEST_ID,
        base(CHANNEL_BOTS)
            .setContentTitle("Notifications anywhere work")
            .setContentText("This came end-to-end encrypted through ntfy, not over your gateway connection.")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build(),
    )

    private fun openBot(bot: Bot, storedSessionId: String): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_BOT, bot.name)
            .putExtra(EXTRA_OPEN_SESSION, storedSessionId)
            .putExtra(EXTRA_OPEN_TITLE, bot.label)
        return PendingIntent.getActivity(context, "bot:${bot.name}".hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Replaces an answered-from-the-shade notification when the answer couldn't be delivered. */
    fun postFailure(tag: String?, id: Int, message: String) = post(
        tag,
        id,
        base(CHANNEL_REQUESTS).setContentTitle("Couldn't send").setContentText(message).build(),
    )

    /** Everything except the ongoing notification, once the user is looking at the app. */
    fun cancelAttention() {
        unreadBotMessages.clear()
        manager.activeNotifications.filter { it.id != WORKING_ID }.forEach { manager.cancel(it.tag, it.id) }
    }

    /** Tapping it opens Herald on [storedSessionId]'s chat, when the notification is about one. */
    private fun base(channel: String, storedSessionId: String? = null, title: String? = null) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setColor(ContextCompat.getColor(context, R.color.notification_accent))
        .setContentIntent(openApp(storedSessionId, title))
        .setAutoCancel(true)

    @SuppressLint("MissingPermission") // canPost checks it.
    private fun post(tag: String?, id: Int, notification: Notification): Boolean {
        if (canPost) manager.notify(tag, id, notification)
        return canPost
    }

    private fun openApp(storedSessionId: String?, title: String?): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (storedSessionId != null) {
            intent.putExtra(EXTRA_OPEN_SESSION, storedSessionId).putExtra(EXTRA_OPEN_TITLE, title)
            // Delivered to the running activity (onNewIntent) rather than just bringing its task forward.
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        // One per chat: a shared request code would let the latest chat's extras overwrite the others'.
        val requestCode = storedSessionId?.hashCode() ?: 0
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(name: String, key: String, mutable: Boolean = false, extras: Intent.() -> Unit = {}): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).setAction(name).apply(extras)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, (name + key).hashCode(), intent, flags)
    }

    companion object {
        const val WORKING_ID = 1
        const val REPLY_ID = 2
        const val REQUEST_ID = 3
        const val BOT_ID = 4
        const val PUSH_TEST_ID = 5

        /** On the launch intent of a notification about a chat: that chat's stored session id, and its title. */
        const val EXTRA_OPEN_SESSION = "open_session_id"
        const val EXTRA_OPEN_TITLE = "open_session_title"

        /** On the launch intent of a bot's notification or shortcut: the bot's profile. */
        const val EXTRA_OPEN_BOT = "open_bot"

        private const val CHANNEL_WORKING_OLD = "working"
        private const val CHANNEL_CONNECTION = "connection"
        private const val CHANNEL_REQUESTS = "requests"
        private const val CHANNEL_REPLIES = "replies"
        private const val CHANNEL_BOTS = "bots"
        private const val MAX_PREVIEW = 2_000
        private const val MAX_STACKED = 6
        private const val DEDUPE_PREFIX = 200
        private const val DEDUPE_WINDOW_MS = 10 * 60_000L
    }
}

private val ApprovalChoice.label: String
    get() = when (this) {
        ApprovalChoice.Once -> "Allow once"
        ApprovalChoice.Session -> "Allow for chat"
        ApprovalChoice.Always -> "Always allow"
        ApprovalChoice.Deny -> "Deny"
    }

/** Markdown reads badly in the shade; keep the words, drop the markup. */
internal fun String.toPlainText(): String = this
    .replace(Regex("```[^\\n]*\\n?"), "")
    .replace(Regex("(?m)^#{1,6}\\s+"), "")
    .replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
    .replace(Regex("`([^`]+)`"), "$1")
    .replace(Regex("!?\\[([^]]*)]\\([^)]*\\)"), "$1")
    .replace(Regex("\\n{3,}"), "\n\n")
    .trim()
