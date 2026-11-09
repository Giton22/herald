package dev.hermeskotlin.ui.calls

import dev.hermeskotlin.ui.calls.CallStatus.Doing
import dev.hermeskotlin.ui.voice.VoiceChatState
import dev.hermeskotlin.ui.voice.VoicePhase
import kotlin.test.Test
import kotlin.test.assertEquals

class InCallTest {

    @Test
    fun theStatusSaysHowLongAndWhatHermesIsDoing() {
        val call = PhoneCallState("Choose a color")
        assertEquals(CallStatus.Connecting, callStatus(call, 3))
        assertEquals(CallStatus.Live("0:08", Doing.Speaking), callStatus(call.copy(voice = VoiceChatState(VoicePhase.Speaking)), 8))
        assertEquals(CallStatus.Live("1:05", Doing.Listening), callStatus(call.copy(voice = VoiceChatState(VoicePhase.Listening)), 65))
        assertEquals(CallStatus.Live("0:30", Doing.Muted), callStatus(call.copy(voice = VoiceChatState(VoicePhase.Listening, muted = true)), 30))
        assertEquals(
            CallStatus.Live("0:12", Doing.Working, "web_search"),
            callStatus(call.copy(voice = VoiceChatState(VoicePhase.Thinking, working = "web_search")), 12),
        )
        assertEquals(CallStatus.Live("0:12", Doing.Thinking), callStatus(call.copy(voice = VoiceChatState(VoicePhase.Thinking)), 12))
        assertEquals(CallStatus.Ended, callStatus(call.copy(ended = true), 40))
    }
}
