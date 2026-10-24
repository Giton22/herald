package dev.hermeskotlin.android.phone

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.service.notification.Condition
import dev.hermeskotlin.core.phone.PhoneToolException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** `phone_sound`: Do Not Disturb, the ringer and the volumes; a change asks first. */
internal class SoundTool(private val context: Context, private val confirmations: PhoneConfirmations) {

    private val audio get() = context.getSystemService(AudioManager::class.java) ?: throw PhoneToolException("The phone has no audio settings.")
    private val notifications get() = context.getSystemService(NotificationManager::class.java)!!

    suspend fun run(args: JsonObject): JsonObject = when (args.string("action")) {
        "get", null -> state()
        "set" -> set(args)
        else -> throw PhoneToolException("action must be get or set.")
    }

    private fun state(): JsonObject = buildJsonObject {
        put(
            "dnd",
            when (notifications.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
                NotificationManager.INTERRUPTION_FILTER_NONE -> "silence"
                else -> "off"
            },
        )
        put(
            "ringer",
            when (audio.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            },
        )
        putJsonObject("volume_percent") {
            for ((name, stream) in STREAMS) put(name, percent(stream))
        }
    }

    private fun percent(stream: Int): Int {
        val max = audio.getStreamMaxVolume(stream).takeIf { it > 0 } ?: return 0
        return audio.getStreamVolume(stream) * 100 / max
    }

    private suspend fun set(args: JsonObject): JsonObject {
        val dnd = args.string("dnd")?.let { DND[it] ?: throw PhoneToolException("dnd must be off, priority, alarms or silence.") }
        val ringer = args.string("ringer")?.let { RINGER[it] ?: throw PhoneToolException("ringer must be normal, vibrate or silent.") }
        val streamName = args.string("stream")
        val volume = args.int("volume")
        if ((streamName == null) != (volume == null)) throw PhoneToolException("Give stream and volume together.")
        val stream = streamName?.let { STREAMS[it] ?: throw PhoneToolException("stream must be ring, notification, media or alarm.") }
        if (dnd == null && ringer == null && stream == null) throw PhoneToolException("Nothing to change: give dnd, ringer, or stream and volume.")
        if (volume != null && volume !in 0..100) throw PhoneToolException("volume is a percent, 0 to 100.")
        if (ringer != null && ringer != AudioManager.RINGER_MODE_NORMAL && streamName in RINGER_STREAMS && volume != 0) {
            // A ring volume above 0 is a normal ringer, and a quiet ringer mutes it: both can't be true.
            throw PhoneToolException("A silent or vibrating ringer can't have a $streamName volume above 0. Set one or the other.")
        }
        if ((dnd != null || ringer != null) && !notifications.isNotificationPolicyAccessGranted) {
            throw PhoneToolException("Kyllene isn't allowed to change Do Not Disturb. The user can allow it in Settings → Phone tools.")
        }
        if (dnd == NotificationManager.INTERRUPTION_FILTER_ALL &&
            notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL && !ownsDnd()
        ) {
            // Asking would be a promise Android won't keep.
            throw PhoneToolException("Do Not Disturb was turned on by the user or another app, and on this Android only they can turn it off.")
        }
        val what = buildList {
            args.string("dnd")?.let { add(if (it == "off") "turn Do Not Disturb off" else "turn on Do Not Disturb (${DND_WORDS[it]})") }
            args.string("ringer")?.let { add("set the ringer to $it") }
            if (streamName != null) add("set the $streamName volume to $volume%")
        }.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
        confirmations.confirm(what) {
            try {
                // The volume before the ringer: a ring volume above 0 puts the ringer back to normal.
                if (stream != null && volume != null) {
                    val max = audio.getStreamMaxVolume(stream)
                    audio.setStreamVolume(stream, (volume * max + 50) / 100, 0)
                }
                dnd?.let(notifications::setInterruptionFilter)
                ringer?.let { audio.ringerMode = it }
            } catch (e: SecurityException) {
                throw PhoneToolException("Android didn't allow that change.")
            }
        }
        // Android can quietly keep its own state (a stricter mode the user has on, say): say so, not "done".
        delay(SETTLE_MS)
        val now = state()
        val kept = buildList {
            args.string("dnd")?.let { if (now["dnd"]?.jsonPrimitive?.content != it) add("Do Not Disturb") }
            args.string("ringer")?.let { if (now["ringer"]?.jsonPrimitive?.content != it) add("the ringer") }
            if (stream != null && volume != null) {
                // Within one step: a phone has few, so 30% is 33% on a 15-step stream.
                val step = 100.0 / audio.getStreamMaxVolume(stream).coerceAtLeast(1)
                if (kotlin.math.abs(percent(stream) - volume) > step) add("the $streamName volume")
            }
        }
        if (kept.isNotEmpty()) {
            throw PhoneToolException("The user allowed it, but Android kept ${kept.joinToString(" and ")} as it was: $now")
        }
        return now
    }

    /**
     * Whether Kyllene may turn Do Not Disturb off. From Android 15 an app turns on and off only its own mode, so
     * only one Kyllene turned on; before that, any.
     */
    private fun ownsDnd(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return true
        return notifications.automaticZenRules.keys.any { notifications.getAutomaticZenRuleState(it) == Condition.STATE_TRUE }
    }

    private companion object {
        /** How long Android takes to settle a sound change before it's read back. */
        const val SETTLE_MS = 400L

        /** Streams the ringer mode rules: on most phones the notification volume follows the ring volume. */
        val RINGER_STREAMS = setOf("ring", "notification")

        val STREAMS = linkedMapOf(
            "ring" to AudioManager.STREAM_RING,
            "notification" to AudioManager.STREAM_NOTIFICATION,
            "media" to AudioManager.STREAM_MUSIC,
            "alarm" to AudioManager.STREAM_ALARM,
        )
        val DND = mapOf(
            "off" to NotificationManager.INTERRUPTION_FILTER_ALL,
            "priority" to NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            "alarms" to NotificationManager.INTERRUPTION_FILTER_ALARMS,
            "silence" to NotificationManager.INTERRUPTION_FILTER_NONE,
        )
        val DND_WORDS = mapOf("priority" to "priority only", "alarms" to "alarms only", "silence" to "total silence")
        val RINGER = mapOf(
            "normal" to AudioManager.RINGER_MODE_NORMAL,
            "vibrate" to AudioManager.RINGER_MODE_VIBRATE,
            "silent" to AudioManager.RINGER_MODE_SILENT,
        )
    }
}
