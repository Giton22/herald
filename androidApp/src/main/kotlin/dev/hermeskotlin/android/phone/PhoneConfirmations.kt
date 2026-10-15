package dev.hermeskotlin.android.phone

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.hermeskotlin.android.HeraldActivity
import dev.hermeskotlin.android.R
import dev.hermeskotlin.core.phone.PhoneToolException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Asks before a phone tool changes anything: a dialog in Herald while it's open, else a notification, says what
 * the agent wants to do, with Allow and Deny. The notification's Allow opens [PhoneActionActivity], which does it
 * from the tap (Android lets an app start an alarm, a link or the share sheet only from something the user did)
 * and closes. Nothing happens unless allowed; swiping the question away is Deny, and it goes when the agent stops
 * waiting.
 */
class PhoneConfirmations(private val context: Context) {

    internal class Pending(val action: (Activity) -> Unit, val done: CompletableDeferred<Unit>)

    /** A question as Herald shows it: [key] answers it through [allow] or [deny]. */
    data class Asked(val key: String, val title: String, val what: String)

    private val manager = NotificationManagerCompat.from(context)
    private val ids = AtomicInteger(0)

    private val system = context.getSystemService(NotificationManager::class.java)

    init {
        createChannel()
        // No question outlives the process that asked it: one still showing is from before a restart, and dead.
        system?.activeNotifications
            ?.filter { it.tag == TAG }
            ?.forEach { manager.cancel(TAG, it.id) }
    }

    /**
     * The channel questions go on. It gets through Do Not Disturb (priority only) once Herald may manage Do Not
     * Disturb, which the Sound tool needs anyway: else the agent could never ask to turn it off. Created again
     * before each question, since that access can come after the channel was first made.
     */
    private fun createChannel() {
        system?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Phone tools", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When the agent wants to change something on this phone"
                if (system.isNotificationPolicyAccessGranted) setBypassDnd(true)
            },
        )
    }

    /** Why a question can't reach the user now, or null when it can. */
    private fun cantAsk(): String? {
        val channel = system?.getNotificationChannel(CHANNEL)
        return when {
            !manager.areNotificationsEnabled() || channel?.importance == NotificationManager.IMPORTANCE_NONE ->
                "Herald's notifications for phone tools are off, so the phone couldn't ask the user."
            system?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE ||
                system?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALARMS ->
                "Do Not Disturb hides every notification right now, so the phone couldn't ask the user."
            system?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY && channel?.canBypassDnd() != true ->
                "Do Not Disturb hides Herald's questions right now, so the phone couldn't ask the user."
            else -> null
        }
    }

    /**
     * Shows [what] (a sentence: "Set an alarm for 7:00") and waits. Runs [action] on the tap of Allow, from an
     * activity; throws [PhoneToolException] when the user denies, doesn't answer before the agent stops waiting,
     * or can't be asked. Asked in Herald while it's open ([inApp]), else in a notification; one that's answered
     * either way, and the notification comes and goes as the user leaves Herald and comes back.
     */
    suspend fun confirm(what: String, title: String = CHANGE, action: (Activity) -> Unit) {
        val key = "c" + ids.incrementAndGet()
        val pending = Pending(action, CompletableDeferred())
        waiting[key] = pending
        val id = key.hashCode()
        val question = Asked(key, title, what)
        try {
            createChannel()
            coroutineScope {
                val notice = launch {
                    var askedInApp = false
                    inApp.collect { open ->
                        if (open) {
                            askedInApp = true
                            manager.cancel(TAG, id)
                        } else if (!pending.done.isCompleted) {
                            val cant = cantAsk()
                                ?: if (post(id, key, title, what)) null else "Android didn't show the question, so the phone couldn't ask the user."
                            // Seen in Herald already: it's there again when the user comes back.
                            if (cant != null && !askedInApp) throw PhoneToolException(cant)
                        }
                    }
                }
                asking.update { it + question }
                try {
                    // Well inside the agent's wait: an Allow it would never hear of must not change the phone.
                    withTimeout(ANSWER_MS) { pending.done.await() }
                } finally {
                    // Done before the notification is taken down, so a post under way can't outlast it.
                    withContext(NonCancellable) { notice.cancelAndJoin() }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw PhoneToolException("The user didn't answer on the phone in time.")
        } finally {
            waiting.remove(key)
            asking.update { it - question }
            manager.cancel(TAG, id)
        }
    }

    /**
     * Tells the user something a tool did on its own (a watched place fired), on the same channel; a tap opens Herald.
     * Not a question: nothing waits for it.
     */
    @SuppressLint("MissingPermission") // Without the permission Android drops it, which is all that can be done.
    fun tell(id: String, title: String, text: String) {
        createChannel()
        // The activity, not the launch intent: that names the app icon's alias, which a new icon turns off.
        val launch = Intent(context, HeraldActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val open = PendingIntent.getActivity(context, id.hashCode(), launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTE_TAG, id.hashCode(), notification) }
    }

    @SuppressLint("MissingPermission") // cantAsk checks it.
    private fun post(id: Int, key: String, title: String, what: String): Boolean {
        val allow = PendingIntent.getActivity(
            context, id,
            Intent(context, PhoneActionActivity::class.java).putExtra(EXTRA_KEY, key).putExtra(EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val deny = PendingIntent.getBroadcast(
            context, id,
            Intent(context, PhoneDenyReceiver::class.java).putExtra(EXTRA_KEY, key).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(what)
            .setStyle(NotificationCompat.BigTextStyle().bigText(what))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDeleteIntent(deny)
            .setTimeoutAfter(ANSWER_MS)
            .addAction(0, "Deny", deny)
            .addAction(0, "Allow", allow)
            .build()
        return runCatching { manager.notify(TAG, id, notification) }.isSuccess
    }

    companion object {
        private const val CHANNEL = "phone_tools"
        private const val TAG = "phone_tool"
        private const val NOTE_TAG = "phone_tool_note"

        /** The question's title for a change to the phone's own settings. */
        const val CHANGE = "The agent wants to change your phone"
        internal const val EXTRA_KEY = "phone_tool_key"
        internal const val EXTRA_ID = "phone_tool_id"

        /** How long a question waits for the user: under the agent's 120 s for a tool that asks. */
        private const val ANSWER_MS = 110_000L

        /** Process-wide: the activity and receiver Android makes find the waiting question here. */
        internal val waiting = ConcurrentHashMap<String, Pending>()

        /** The questions waiting, oldest first, for Herald to ask while it's open. */
        val asking = MutableStateFlow<List<Asked>>(emptyList())

        /** Whether Herald is open and unlocked, so a question is asked there and not in a notification. */
        val inApp = MutableStateFlow(false)

        /** Allow on question [key], from [activity]; false when nothing waits on it any more. */
        fun allow(key: String, activity: Activity): Boolean {
            val pending = waiting.remove(key) ?: return false
            asking.update { list -> list.filterNot { it.key == key } }
            try {
                pending.action(activity)
                pending.done.complete(Unit)
            } catch (e: Exception) {
                pending.done.completeExceptionally(PhoneToolException(e.message ?: "The phone couldn't do it."))
            }
            return true
        }

        /** Deny on question [key]; false when nothing waits on it any more. */
        fun deny(key: String): Boolean {
            val pending = waiting.remove(key) ?: return false
            asking.update { list -> list.filterNot { it.key == key } }
            pending.done.completeExceptionally(PhoneToolException("The user said no on the phone."))
            return true
        }

        internal fun allow(intent: Intent, activity: Activity) {
            // The agent stopped waiting, or the app restarted: nothing to do, but the question shouldn't stay.
            if (intent.getStringExtra(EXTRA_KEY)?.let { allow(it, activity) } != true) forget(activity, intent)
        }

        internal fun deny(context: Context, intent: Intent) {
            if (intent.getStringExtra(EXTRA_KEY)?.let { deny(it) } != true) forget(context, intent)
        }

        private fun forget(context: Context, intent: Intent) {
            val id = intent.getIntExtra(EXTRA_ID, 0).takeIf { it != 0 } ?: return
            NotificationManagerCompat.from(context).cancel(TAG, id)
        }
    }
}

/** Opened by Allow: does the action from the user's tap, then closes. Draws nothing. */
class PhoneActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneConfirmations.allow(intent, this)
        finish()
    }
}

/** Deny, or the question swiped away. */
class PhoneDenyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PhoneConfirmations.deny(context, intent)
    }
}
