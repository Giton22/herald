package dev.hermeskotlin.android.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import androidx.core.os.HandlerCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import dev.hermeskotlin.core.bots.Bot
import androidx.core.content.ContextCompat
import dev.hermeskotlin.android.HeraldActivity
import dev.hermeskotlin.android.R
import dev.hermeskotlin.core.chat.ApprovalChoice
import dev.hermeskotlin.core.chat.InputRequest
import dev.hermeskotlin.core.connection.ConnectionState
import dev.hermeskotlin.core.phone.AgentCall
import dev.hermeskotlin.core.phone.CallOpeners
import dev.hermeskotlin.core.phone.CallOutcome
import dev.hermeskotlin.core.phone.CallReason
import dev.hermeskotlin.ui.calls.agentCallReason
import dev.hermeskotlin.ui.calls.callReason
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Builds and posts every notification the app shows; the actions land in [NotificationActionReceiver]. */
/** One bot's line in a room's notification, with when it was written. */
data class RoomNotice(val bot: Bot, val picture: ByteArray?, val text: String, val at: Long)

class ChatNotifications(
    private val context: Context,
    /** Why the agent called, for a call picked up on the watch to open with. */
    private val openers: CallOpeners = CallOpeners(),
) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        // Running turns once had a louder channel of their own (a Live Update); everything ongoing is quiet now.
        manager.deleteNotificationChannel(CHANNEL_WORKING_OLD)
        manager.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_CONNECTION, NotificationManagerCompat.IMPORTANCE_MIN)
                    .setName("Background connection")
                    .setDescription("Shown while Kyllene stays connected in the background, or works on a turn.")
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
                NotificationChannelCompat.Builder(CHANNEL_INBOX, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Inbox")
                    .setDescription("A scheduled job you follow reported, such as a morning briefing.")
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_INBOX_FAILED, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName("Failed jobs")
                    .setDescription("A scheduled job's run failed.")
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_VOICE, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Voice chat")
                    .setDescription("Shown while a voice chat goes on with the screen off or another app open.")
                    .setShowBadge(false)
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_CALLS, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName("Calls for an answer")
                    .setDescription("An approval or a question rings like a call (Settings → Phone tools → Calls).")
                    .setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setVibrationEnabled(true)
                    .setVibrationPattern(RING_VIBRATION)
                    .build(),
            ),
        )
    }

    /**
     * A call ringing, by [id]: for an answer ([request]), so Decline or ringing out can post the usual notification;
     * or one the agent placed ([agent]), whose end goes back to it. [talk]: answering puts Hermes on the line, rather
     * than opening the chat.
     */
    class Ringing(
        val id: String,
        val storedSessionId: String?,
        val title: String?,
        val request: InputRequest?,
        val talk: Boolean = false,
        val agent: AgentCall? = null,
    ) {
        /** The ring-out's token: Android matches it by identity, and request ids come back as copies from intents. */
        internal val timer = Any()
    }

    private val _ringing = MutableStateFlow<Map<String, Ringing>>(emptyMap())

    /** A voice chat or a call is on: nothing rings over it. */
    var voiceOn: () -> Boolean = { false }

    /**
     * When quiet hours end, in minutes into the day, while they're on now (Settings → Phone tools → Calls); else null.
     * Nothing rings in them, here or on the watch.
     */
    var quietUntil: () -> Int? = { null }

    private fun quietNow() = quietUntil() != null

    /** Somewhere else that rings along with this phone, Herald on the watch. */
    interface AlsoRings {
        /**
         * Rings for [call] there too; true when it tries, so this phone's call isn't shown there as well. Should it
         * not ring after all, it calls [showOnWatch].
         */
        fun ring(call: Ringing): Boolean

        /** The call for [requestId] stopped ringing here. */
        fun stop(requestId: String)
    }

    var alsoRings: AlsoRings? = null

    /** The calls ringing now, by request id: the call screen closes once its own is gone. */
    val ringing: StateFlow<Map<String, Ringing>> = _ringing.asStateFlow()

    /** Requests that already rang: one that comes back (Herald was opened and left again) is a plain notification. */
    private val rang = LinkedHashSet<String>()

    private val main = Handler(Looper.getMainLooper())

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
            .setContentTitle("Kyllene")
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

    /** The ongoing notification of a voice chat kept up like a call ([VoiceCallService]): back to it, or [end] it. */
    fun voiceCall(end: PendingIntent): Notification = base(CHANNEL_VOICE)
        .setContentTitle("Voice chat")
        .setContentText("Talking with Hermes")
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setOngoing(true)
        .setAutoCancel(false)
        .setSilent(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .addAction(0, "End", end)
        .build()

    fun postOngoing(connection: ConnectionState, pushAnywhere: Boolean = false) =
        post(null, WORKING_ID, ongoing(connection, pushAnywhere))

    /**
     * A question the agent is blocked on, answerable in place when it fits a notification. Every answer
     * asks for an unlock first: from a locked phone, Approve would run a command for whoever holds it.
     * With [ring] an approval or a question rings like a call instead, once ([postCall]); passwords and codes never
     * ring, they're typed in the app. With [talk] too, answering puts Hermes on the line instead of opening the chat.
     * False when Herald may not post notifications.
     */
    fun postRequest(
        storedSessionId: String?,
        title: String?,
        request: InputRequest,
        ring: Boolean = false,
        talk: Boolean = false,
        quiet: Boolean = false,
    ): Boolean {
        val rings = ring && !voiceOn() && !quietNow() && (request is InputRequest.Approval || request is InputRequest.Clarify) && synchronized(rang) { request.id !in rang }
        if (rings) return postCall(Ringing(request.id, storedSessionId, title, request, talk))
        val builder = base(CHANNEL_REQUESTS, storedSessionId, title)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSubText(title)
        // [quiet]: asked on a call from the watch. It waits here in case that call doesn't get it answered,
        // without a sound, and without showing on the watch over the call.
        if (quiet) builder.setSilent(true).setLocalOnly(true)
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

    fun cancelRequest(id: String) {
        stopRinging(id)
        manager.cancel(id, REQUEST_ID)
    }

    /**
     * Rings for [request] like an incoming call: Answer opens its chat (unlocking first), Decline turns it into the
     * usual notification, and so does no answer within [RING_MS]. Over the lock screen or with the screen off it
     * takes the whole screen ([IncomingCallActivity]), which says only who's calling and why, never the question.
     */
    @SuppressLint("MissingPermission") // canPost checks it.
    private fun postCall(call: Ringing): Boolean {
        if (!canPost) return false
        if (call.request != null) {
            synchronized(rang) {
                rang += call.id
                if (rang.size > MAX_RANG) rang.remove(rang.first())
            }
        }
        _ringing.update { it + (call.id to call) }
        // Herald on the watch rings itself, with an Answer that talks there: this phone's call isn't shown on it too,
        // unless the watch doesn't say it rings ([showOnWatch]).
        val watchRings = alsoRings?.ring(call) == true
        manager.notify(call.id, CALL_ID, callNotification(call, localOnly = watchRings))
        HandlerCompat.postDelayed(main, { rangOut(call.id) }, call.timer, RING_MS)
        return true
    }

    /**
     * Rings for a call the agent placed ([dev.hermeskotlin.core.phone.PhoneTool.CallMe]) and waits for how it ends:
     * Answer puts Hermes on the line in [storedSessionId]'s chat (a new one when null), which opens with [reason].
     * Never over a call or a voice chat already on: that's [CallOutcome.Busy]. Never in quiet hours either:
     * [CallOutcome.Quiet]. Null when Herald may not ring.
     */
    suspend fun ringForAgent(storedSessionId: String?, title: String?, reason: CallReason): CallOutcome? {
        if (quietNow()) return CallOutcome.Quiet
        val call = AgentCall(reason)
        val id = AGENT_CALL_PREFIX + UUID.randomUUID()
        val rings = withContext(Dispatchers.Main) {
            if (voiceOn()) null else postCall(Ringing(id, storedSessionId, title, null, talk = true, agent = call))
        } ?: return CallOutcome.Busy
        if (!rings) return null
        try {
            return call.outcome()
        } finally {
            // The tool gave up (switched off, out of time): it stops ringing.
            withContext(NonCancellable + Dispatchers.Main) { stopRinging(id) }
        }
    }

    /**
     * Whether this phone's call for [requestId] shows on the watch too: it does once Herald there didn't say it
     * rings, and stops again should it say so late. Ringing on as it is, from where it got to.
     */
    @SuppressLint("MissingPermission") // It's only ever posted when canPost.
    fun showOnWatch(requestId: String, show: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { showOnWatch(requestId, show) }
            return
        }
        val call = _ringing.value[requestId] ?: return
        manager.notify(requestId, CALL_ID, callNotification(call, localOnly = !show, again = true))
        // Answered meanwhile, off this thread: it's not put back.
        if (requestId !in _ringing.value) manager.cancel(requestId, CALL_ID)
    }

    /** [again]: an update of the call already ringing, which goes on as it was instead of starting over. */
    private fun callNotification(call: Ringing, localOnly: Boolean, again: Boolean = false): Notification {
        val why = call.agent?.reason?.reason?.let { it.take(MAX_CALL_REASON) } ?: callReason(call.request is InputRequest.Approval)
        // Over the lock screen, when it hides what notifications say, a call from the agent says only that it calls.
        val public = if (call.agent != null) callBuilder(call, localOnly, again, agentCallReason()).build() else null
        return callBuilder(call, localOnly, again, why)
            .apply { public?.let(::setPublicVersion) }
            .build()
            // Rings on, as a call does, until it's answered, declined or rung out; else the ringtone plays once.
            .apply { flags = flags or Notification.FLAG_INSISTENT }
    }

    private fun callBuilder(call: Ringing, localOnly: Boolean, again: Boolean, why: String): NotificationCompat.Builder {
        val caller = Person.Builder().setName(call.title?.takeIf { it.isNotBlank() } ?: "Hermes").setImportant(true).build()
        val answer = PendingIntent.getActivity(
            context,
            (ACTION_ANSWER + call.id).hashCode(),
            answerIntent(call),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val decline = action(NotificationActionReceiver.ACTION_DECLINE_CALL, call.id) {
            putExtra(NotificationActionReceiver.EXTRA_REQUEST_ID, call.id)
        }
        val screen = PendingIntent.getActivity(
            context,
            (ACTION_RING + call.id).hashCode(),
            Intent(context, IncomingCallActivity::class.java)
                .putExtra(NotificationActionReceiver.EXTRA_REQUEST_ID, call.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer))
            .setContentText(why)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
            .setOngoing(true)
            .setAutoCancel(false)
            // Should Herald be gone before it rings out, Android takes it down itself.
            .setTimeoutAfter(RING_MS + RING_GRACE_MS)
            .setLocalOnly(localOnly)
            .setOnlyAlertOnce(again)
    }

    /** No answer within [RING_MS]: a question becomes the usual notification, and the agent hears no one picked up. */
    private fun rangOut(id: String) {
        val agent = _ringing.value[id]?.agent ?: return decline(id)
        stopRinging(id)
        agent.end(CallOutcome.NoAnswer)
    }

    /**
     * Declined, or rung out: the call goes and the usual notification takes its place; a call from the agent is
     * declined. On the main thread, as the notifiers that cancel requests are: an answer elsewhere can't slip in
     * between, leaving one for nothing.
     */
    fun decline(requestId: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { decline(requestId) }
            return
        }
        val call = stopRinging(requestId) ?: return
        call.request?.let { postRequest(call.storedSessionId, call.title, it) }
        call.agent?.end(CallOutcome.Declined)
    }

    /**
     * Picked up on the watch: the call goes here, and the question waits quietly in case that call doesn't answer it.
     * A call from the agent is on the line there, and the call the watch starts in its chat opens with why.
     */
    fun answeredOnWatch(requestId: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { answeredOnWatch(requestId) }
            return
        }
        val call = stopRinging(requestId, toldWatch = true) ?: return
        call.request?.let { postRequest(call.storedSessionId, call.title, it, quiet = true) }
        call.agent?.let { agent ->
            call.storedSessionId?.let { openers.put(it, agent.reason) }
            agent.end(CallOutcome.Answered)
        }
    }

    /** Declined on the watch, which already stopped ringing. */
    fun declinedOnWatch(requestId: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { declinedOnWatch(requestId) }
            return
        }
        val call = stopRinging(requestId, toldWatch = true) ?: return
        call.request?.let { postRequest(call.storedSessionId, call.title, it) }
        call.agent?.end(CallOutcome.Declined)
    }

    /**
     * Answered: the call goes, and the chat it opens shows the question. A call from the agent is settled by the call
     * screen once it's on the line ([dev.hermeskotlin.ui.calls.PhoneCallModel]), or not picked up after all.
     */
    fun answered(requestId: String) {
        stopRinging(requestId)
    }

    /** [toldWatch]: the watch ended it, so it isn't told to stop. */
    private fun stopRinging(requestId: String, toldWatch: Boolean = false): Ringing? {
        manager.cancel(requestId, CALL_ID)
        var call: Ringing? = null
        _ringing.update {
            call = it[requestId]
            it - requestId
        }
        call?.let {
            main.removeCallbacksAndMessages(it.timer)
            if (!toldWatch) alsoRings?.stop(requestId)
        }
        return call
    }

    /**
     * What [call]'s Answer opens: Hermes on the line ([PhoneCallActivity]) when it talks, else its chat. A locked
     * phone asks for its unlock on the way either way.
     */
    private fun answerIntent(call: Ringing): Intent {
        // A call from the agent always talks: there's no question to open the chat on.
        if (call.agent != null || (call.talk && call.storedSessionId != null)) {
            return Intent(context, PhoneCallActivity::class.java)
                .putExtra(NotificationActionReceiver.EXTRA_REQUEST_ID, call.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            .putExtra(EXTRA_ANSWERED_CALL, call.id)
            .apply {
                if (call.storedSessionId != null) {
                    putExtra(EXTRA_OPEN_SESSION, call.storedSessionId).putExtra(EXTRA_OPEN_TITLE, call.title)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            }
    }

    /** The call screen's Answer: the same as the notification's. */
    fun answer(requestId: String): Intent? = _ringing.value[requestId]?.let(::answerIntent)

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

    /**
     * New lines in the hosted room [roomId], as a group conversation titled with the room's [name]: each
     * line from its bot, with that bot's face. Stacks like a bot's messages until opened or cleared; a tap
     * opens the room.
     */
    fun postRoomMessages(roomId: String, name: String, lines: List<RoomNotice>) {
        val fresh = lines.mapNotNull { line -> line.text.toPlainText().take(MAX_PREVIEW).ifBlank { null }?.let { line.copy(text = it) } }
        if (fresh.isEmpty()) return
        val history = (unreadRoomLines[roomId].orEmpty() + fresh).takeLast(MAX_STACKED)
        unreadRoomLines[roomId] = history
        val me = Person.Builder().setName("You").build()
        val notification = base(CHANNEL_BOTS)
            .setContentIntent(openRoom(roomId, name))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setStyle(
                NotificationCompat.MessagingStyle(me).setConversationTitle(name).setGroupConversation(true).also { style ->
                    history.forEach { style.addMessage(it.text, it.at, BotShortcuts.person(it.bot, it.picture)) }
                },
            )
            .setNumber(history.size)
            .setDeleteIntent(action(NotificationActionReceiver.ACTION_ROOM_CLEARED, roomId) { putExtra(EXTRA_OPEN_ROOM, roomId) })
            .build()
        post(roomId, ROOM_ID, notification)
    }

    /** The room's notification was swiped away: its stack starts over. */
    fun forgetRoom(roomId: String) {
        unreadRoomLines.remove(roomId)
    }

    /** Each room's lines shown in its notification, oldest first. */
    private val unreadRoomLines = mutableMapOf<String, List<RoomNotice>>()

    /**
     * Opens Herald like its launcher icon does. Named by the activity, not the launch intent, which names the
     * app icon's alias: picking another icon turns that alias off, and a notification posted before then would
     * open nothing.
     */
    private fun appIntent(): Intent = Intent(context, HeraldActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)

    private fun openRoom(roomId: String, name: String): PendingIntent {
        val intent = appIntent()
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_ROOM, roomId)
            .putExtra(EXTRA_OPEN_TITLE, name)
        return PendingIntent.getActivity(context, "room:$roomId".hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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

    /**
     * A finished run of a cron job the user follows: the job as the title, the start of what it said, and a tap
     * that opens it in the Inbox. One notification per job (a job id is only unique in its profile, [bot]), so a
     * job's next run replaces its last.
     */
    fun postCronRun(key: String, bot: String, job: String, title: String, text: String, failed: Boolean) {
        val body = text.toPlainText().take(MAX_PREVIEW).ifBlank { if (failed) "The run failed." else "Finished." }
        val notification = base(if (failed) CHANNEL_INBOX_FAILED else CHANNEL_INBOX)
            .setContentTitle(if (failed) "$title failed" else title)
            .setContentText(body.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(if (failed) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openInbox(key))
            .build()
        post("cron:$bot/$job", INBOX_ID, notification)
    }

    private fun openInbox(key: String): PendingIntent {
        val intent = appIntent()
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_INBOX, key)
        return PendingIntent.getActivity(context, "inbox:$key".hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

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
        val intent = appIntent()
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
        unreadRoomLines.clear()
        // A call stays: Herald starting behind its full screen isn't the user looking ([hangUp] is).
        manager.activeNotifications.filter { it.id != WORKING_ID && it.id != VOICE_ID && it.id != CALL_ID }.forEach { manager.cancel(it.tag, it.id) }
    }

    /** Herald is in front: every call ringing is answered by looking, and posts no notification later. */
    fun hangUp() {
        // A call from the agent isn't about a chat on screen: it rings on, to be answered or declined.
        _ringing.value.filterValues { it.agent == null }.keys.forEach(::stopRinging)
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
        val intent = appIntent()
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
        const val ROOM_ID = 6
        const val VOICE_ID = 7
        const val INBOX_ID = 8

        /** On the launch intent of an Inbox notification: the run's key ([dev.hermeskotlin.core.inbox.InboxRun.key]). */
        const val EXTRA_OPEN_INBOX = "open_inbox"
        const val CALL_ID = 9

        /** On an Answer's launch intent: the request whose call was answered. */
        const val EXTRA_ANSWERED_CALL = "answered_call"

        /** How long a call rings before it becomes the usual notification. */
        const val RING_MS = 45_000L

        /** On the launch intent of a room's notification: the hosted room's id (its name in [EXTRA_OPEN_TITLE]). */
        const val EXTRA_OPEN_ROOM = "open_room"

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
        private const val CHANNEL_VOICE = "voice"
        private const val CHANNEL_INBOX = "inbox"
        private const val CHANNEL_INBOX_FAILED = "inbox_failed"

        /** Read by PhoneToolAccess too, to tell whether calls can ring. */
        private const val CHANNEL_CALLS = "calls"
        private const val RING_GRACE_MS = 5_000L

        /** A call from the agent's id, apart from the gateway's request ids. */
        private const val AGENT_CALL_PREFIX = "agent-call-"

        /** As much of the agent's reason as the call shows; Hermes says all of it on the line. */
        private const val MAX_CALL_REASON = 160
        private const val MAX_RANG = 200
        private const val ACTION_ANSWER = "answer"
        private const val ACTION_RING = "ring"
        private val RING_VIBRATION = longArrayOf(0, 800, 600, 800, 600, 800, 1_600)
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
