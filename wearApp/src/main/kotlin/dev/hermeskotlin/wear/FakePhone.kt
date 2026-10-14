package dev.hermeskotlin.wear

import dev.hermeskotlin.wearlink.ChatListRequest
import dev.hermeskotlin.wearlink.WatchScope
import dev.hermeskotlin.wearlink.ChatList
import dev.hermeskotlin.wearlink.PhoneStatus
import dev.hermeskotlin.wearlink.PhoneStatusRequest
import dev.hermeskotlin.wearlink.RecordedAsk
import dev.hermeskotlin.wearlink.ReplyPhase
import dev.hermeskotlin.wearlink.ReplySpeech
import dev.hermeskotlin.wearlink.ReplyUpdate
import dev.hermeskotlin.wearlink.WatchAsk
import dev.hermeskotlin.wearlink.WatchChat
import dev.hermeskotlin.wearlink.WatchBot
import dev.hermeskotlin.wearlink.WatchStop
import dev.hermeskotlin.wearlink.WearJson
import dev.hermeskotlin.wearlink.WearPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug builds only: stands in for Herald on the phone when the watch has no phone at all, an emulator that
 * isn't paired, so every screen can be tried. It answers with made-up sample turns, never anything real.
 */
class FakePhone(private val scope: CoroutineScope, private val deliver: (String, ByteArray) -> Unit) {

    private var turn: Job? = null

    fun receive(path: String, data: ByteArray) {
        when {
            path == WearPaths.ASK || path == WearPaths.BOT_ASK -> WearJson.decode<WatchAsk>(data)?.let { answer(it, heard = null) }
            WearPaths.isRecording(path) -> RecordedAsk.read(data)?.let { (ask, _) -> answer(ask, heard = SAMPLE_HEARD) }
            path == WearPaths.STOP -> WearJson.decode<WatchStop>(data)?.let { turn?.cancel() }
            path == WearPaths.CHATS -> deliver(WearPaths.CHATS, WearJson.encode(ChatList(SAMPLE_CHATS, bots = SAMPLE_BOTS, requestId = WearJson.decode<ChatListRequest>(data)?.requestId, scope = SAMPLE_SCOPE)))
            path == WearPaths.STATUS -> {
                val request = WearJson.decode<PhoneStatusRequest>(data)
                deliver(WearPaths.STATUS, WearJson.encode(PhoneStatus(signedIn = true, connected = true, botChats = true,
                    botProfile = request?.botProfile, requestId = request?.requestId, profileSync = true,
                    activeScope = SAMPLE_SCOPE, scope = request?.scope ?: SAMPLE_SCOPE)))
            }
        }
    }

    private fun answer(ask: WatchAsk, heard: String?) {
        turn?.cancel()
        turn = scope.launch {
            var seq = 0
            val chatId = ask.chatId ?: "sample-chat"
            fun tell(update: ReplyUpdate) = deliver(WearPaths.REPLY, WearJson.encode(update.copy(seq = ++seq)))
            val base = ReplyUpdate(ask.id, ReplyPhase.Sending, chatId = chatId, title = "Weekend plans", heard = heard, model = "Sample model", contextPercent = 34, scope = ask.scope ?: SAMPLE_SCOPE)
            if (heard != null) {
                tell(base.copy(phase = ReplyPhase.Transcribing))
                delay(700)
            }
            tell(base)
            delay(500)
            tell(base.copy(phase = ReplyPhase.Working, activity = "Searching the web"))
            delay(1_200)
            val words = SAMPLE_REPLY.split(' ')
            for (n in 4..words.size step 4) {
                tell(base.copy(phase = ReplyPhase.Working, text = words.take(n).joinToString(" "), activity = null))
                delay(300)
            }
            tell(base.copy(phase = ReplyPhase.Done, text = SAMPLE_REPLY, speech = if (ask.speak) ReplySpeech.Unavailable else ReplySpeech.Off))
        }
    }

    private companion object {
        val SAMPLE_SCOPE = WatchScope("https://sample.example")
        const val SAMPLE_HEARD = "What's the weather like this weekend?"
        const val SAMPLE_REPLY = "Saturday looks sunny with a high of 22°C, so it's a good day to be outside. " +
            "Sunday turns cloudy in the afternoon, with a chance of light rain after 4 pm. " +
            "If you're planning the hike, Saturday morning is the best window."
        val SAMPLE_CHATS = listOf(
            WatchChat("sample-chat", "Weekend plans"),
            WatchChat("sample-2", "Grocery list"),
            WatchChat("sample-3", "Fix the build script"),
            WatchChat("sample-4", "Book recommendations"),
        )
        val SAMPLE_BOTS = listOf(WatchBot("assistant", "Assistant"), WatchBot("research", "Research"))
    }
}
