package dev.hermeskotlin.ui.calls

import dev.hermeskotlin.ui.voice.VoiceChatState
import dev.hermeskotlin.ui.voice.VoicePhase
import kotlin.test.Test
import kotlin.test.assertEquals

class InCallTest {

    @Test
    fun theStatusSaysHowLongAndWhatHermesIsDoing() {
        val call = PhoneCallState("Choose a color")
        assertEquals("Connecting…", callStatus(call, 3))
        assertEquals("0:08 · Speaking", callStatus(call.copy(voice = VoiceChatState(VoicePhase.Speaking)), 8))
        assertEquals("1:05 · Listening", callStatus(call.copy(voice = VoiceChatState(VoicePhase.Listening)), 65))
        assertEquals("0:30 · Muted", callStatus(call.copy(voice = VoiceChatState(VoicePhase.Listening, muted = true)), 30))
        assertEquals("0:12 · Working: web_search", callStatus(call.copy(voice = VoiceChatState(VoicePhase.Thinking, working = "web_search")), 12))
        assertEquals("Call ended", callStatus(call.copy(ended = true), 40))
    }
}
