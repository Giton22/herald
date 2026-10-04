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
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import dev.hermeskotlin.android.R
import dev.hermeskotlin.core.chat.ApprovalChoice
import dev.hermeskotlin.core.chat.ChatState
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.connection.ConnectionState

/** Builds and posts every notification the app shows; the actions land in [NotificationActionReceiver]. */
class ChatNotifications(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        manager.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_WORKING, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Running turns")
                    .setDescription("Shown while the agent works, so the connection stays up in the background.")
                    .setShowBadge(false)
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_CONNECTION, NotificationManagerCompat.IMPORTANCE_MIN)
                    .setName("Background connection")
                    .setDescription("Shown while Stay connected keeps the gateway connection up.")
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
            ),
        )
    }

    val canPost: Boolean
        get() = manager.areNotificationsEnabled() && (
            android.os.Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )

    /**
     * The ongoing notification of [ChatService]: what the agent is doing, with a Stop button, or a
     * quiet connection line between turns when Stay connected keeps the service up.
     */
    fun working(state: ChatState?, connection: ConnectionState): Notification {
        if (state?.running != true) return connected(state, connection)
        val waiting = state?.inputRequests?.isNotEmpty() == true
        val runningTool = state?.messages?.lastOrNull()
            ?.let { it as? dev.hermeskotlin.core.chat.ChatMessage.Assistant }
            ?.tools?.lastOrNull { it.running }
        val text = when {
            waiting -> "Waiting for your answer"
            !state?.status.isNullOrBlank() -> state.status
            runningTool != null -> runningTool.detail?.let { "${runningTool.name}: $it" } ?: "Using ${runningTool.name}"
            else -> "Working…"
        }
        // The status-bar chip of a Live Update has room for a word or two.
        val chip = when {
            waiting -> "Waiting"
            runningTool != null -> runningTool.name.take(CHIP_LENGTH)
            else -> "Working"
        }
        return base(CHANNEL_WORKING, state?.storedSessionId, state?.title)
            .setContentTitle(state?.title?.takeIf { it.isNotBlank() } ?: "Hermes is working")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // Android 16 Live Update: pinned to the top of the shade and the lock screen, with a chip
            // in the status bar. Older versions ignore it and show a plain ongoing notification.
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(chip)
            .addAction(0, "Stop", action(NotificationActionReceiver.ACTION_STOP, "stop"))
            .build()
    }

    fun postWorking(state: ChatState?, connection: ConnectionState) = post(null, WORKING_ID, working(state, connection))

    private fun connected(state: ChatState?, connection: ConnectionState): Notification {
        val title = when (connection) {
            is ConnectionState.Connected -> "Connected to Hermes"
            is ConnectionState.Connecting, is ConnectionState.Reconnecting -> "Reconnecting to Hermes…"
            else -> "Not connected to Hermes"
        }
        return base(CHANNEL_CONNECTION)
            .setContentTitle(title)
            .setContentText(state?.title?.takeIf { it.isNotBlank() }?.let { "Following $it" } ?: "Waiting for turns from any device")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .addAction(0, "Turn off", action(NotificationActionReceiver.ACTION_DISCONNECT, "disconnect"))
            .build()
    }

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
            is InputRequest.Secret -> builder
                .setContentTitle(if (request.kind == InputRequest.Secret.Kind.Sudo) "Sudo password needed" else "Secret needed")
                .setContentText(request.command ?: request.prompt)
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

    /** Replaces an answered-from-the-shade notification when the answer couldn't be delivered. */
    fun postFailure(tag: String?, id: Int, message: String) = post(
        tag,
        id,
        base(CHANNEL_REQUESTS).setContentTitle("Couldn't send").setContentText(message).build(),
    )

    /** Everything except the ongoing notification, once the user is looking at the app. */
    fun cancelAttention() {
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

        /** On the launch intent of a notification about a chat: that chat's stored session id, and its title. */
        const val EXTRA_OPEN_SESSION = "open_session_id"
        const val EXTRA_OPEN_TITLE = "open_session_title"

        private const val CHANNEL_WORKING = "working"
        private const val CHANNEL_CONNECTION = "connection"
        private const val CHANNEL_REQUESTS = "requests"
        private const val CHANNEL_REPLIES = "replies"
        private const val MAX_PREVIEW = 2_000
        private const val CHIP_LENGTH = 12
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
