package dev.hermeskotlin.ui.voice

import androidx.compose.runtime.Composable
import dev.hermeskotlin.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The lines a live call says out loud and a watch call reports, in the app's language. They're used from plain
 * functions and coroutines that run outside composition (a call's connect loop, a watch's relay), so the words
 * sit here and [dev.hermeskotlin.ui.App] sets [current] from the catalogue, as it does for dates and times.
 * Until then, and in tests, they're English.
 */
class VoiceWords(
    val unreachable: String = "Sorry, I couldn’t reach Hermes for that request.",
    val questionsInChat: String = "Hermes has a few questions for you. They’re waiting in the chat.",
    /** "Hermes needs an answer before it goes on. [the question]": the question, in its place. */
    val needsAnswer: String = "Hermes needs an answer before it goes on. %1\$s",
    val unlockForApproval: String = "Hermes needs your approval. Unlock the phone, and I’ll ask you for it.",
    val watchTargetChanged: String = "Default Call Target changed. Tap Call again.",
    val watchNoAnswer: String = "GPT-Live sent no answer.",
    val watchNotLive: String = "Live voice isn’t set up on your gateway any more. Tap to ask one question at a time.",
    val watchLost: String = "Kyllene on your phone lost the call.",
) {
    companion object {
        /** The words in use: the app's language once [rememberVoiceWords] ran, English before. */
        var current: VoiceWords = VoiceWords()
    }
}

/** [VoiceWords] from the catalogue, in the app's language. */
@Composable
fun rememberVoiceWords(): VoiceWords = VoiceWords(
    unreachable = stringResource(Res.string.voice_said_unreachable),
    questionsInChat = stringResource(Res.string.voice_said_questions_in_chat),
    needsAnswer = stringResource(Res.string.voice_said_needs_answer),
    unlockForApproval = stringResource(Res.string.voice_said_unlock_for_approval),
    watchTargetChanged = stringResource(Res.string.watch_call_target_changed),
    watchNoAnswer = stringResource(Res.string.voice_error_live_no_answer),
    watchNotLive = stringResource(Res.string.watch_call_not_live),
    watchLost = stringResource(Res.string.watch_call_lost),
)
