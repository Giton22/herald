package dev.hermeskotlin.core.voice

import dev.hermeskotlin.core.chat.ApprovalChoice
import dev.hermeskotlin.core.chat.ClarifyQuestion
import dev.hermeskotlin.core.chat.InputRequest

/**
 * A question or an approval Hermes waits on, put into words a voice chat can read aloud and answered by what
 * the person says back. Only [InputRequest.Approval] and [InputRequest.Clarify] are asked aloud: a password or
 * a secret is never spoken, and stays for the screen.
 */
object VoiceAnswers {

    /** Whether [request] can be asked and answered by voice. */
    fun canAsk(request: InputRequest): Boolean = request is InputRequest.Approval || request is InputRequest.Clarify

    /** What to read aloud for [request]: what it's about and how to answer. */
    fun approvalPrompt(request: InputRequest.Approval): String {
        val what = request.description.trim().ifEmpty { request.command.trim() }.let(::short)
        val ways = buildList {
            add("yes")
            if (ApprovalChoice.Session in request.choices) add("for this chat")
            if (ApprovalChoice.Always in request.choices) add("always")
        }
        return "Hermes needs your approval: $what. Say ${ways.joinToString(", ")}, or no."
    }

    /** What to read aloud for one question of a clarify request. */
    fun questionPrompt(question: ClarifyQuestion): String {
        val asked = short(question.question.trim()).ifEmpty { "Hermes has a question." }
        if (question.choices.isEmpty()) return asked
        val choices = question.choices.mapIndexed { i, choice -> "${i + 1}: ${short(choice)}." }.joinToString(" ")
        val pick = if (question.multiSelect) "Say the ones you want" else "Say one"
        return "$asked $pick. $choices Or say something else."
    }

    /**
     * The approval said in [transcript], or null when it isn't clear (then it's asked again). A misheard yes runs a
     * command, so anything doubtful isn't one: a negation anywhere denies; a question, a "but" or an "if" is asked
     * again; and a grant counts only when it's all that was said ("yes, go ahead", "for this chat", "always"): a
     * grant word in passing ("okay, let me think", "show it in the chat") is asked again.
     */
    fun approval(request: InputRequest.Approval, transcript: String): ApprovalChoice? {
        val words = words(transcript)
        if (words.isEmpty()) return null
        val choice = when {
            words.any { it in DENY || it.endsWith("n't") } -> ApprovalChoice.Deny
            // Speech to text often drops the "?": "can you run it" is asked, not granted.
            '?' in transcript || words.any { it in HEDGES } || words.first() in QUESTION_STARTS -> return null
            words.any { it !in GRANT_WORDS } -> return null
            // How far reaching it is, said once: "always for this chat" is two answers, asked again.
            words.any { it in ALWAYS_WORDS } && words.any { it in SESSION_WORDS } -> return null
            words.any { it in ALWAYS_WORDS } -> ApprovalChoice.Always
            words.any { it in SESSION_WORDS } -> ApprovalChoice.Session
            words.any { it in ONCE } -> ApprovalChoice.Once
            else -> return null
        }
        // A reach the gateway doesn't offer for this command is asked again rather than narrowed silently.
        return choice.takeIf { it in request.choices }
    }

    /**
     * The answer to [question] said in [transcript]: the choices picked by their number or their words, or
     * what was said as it is when it names none of them. Empty when nothing was said.
     */
    fun clarify(question: ClarifyQuestion, transcript: String): List<String> {
        val said = transcript.trim()
        if (said.isEmpty()) return emptyList()
        if (question.choices.isEmpty()) return listOf(said)
        val words = words(said)
        // By number only when that's all that was said ("the second one", "one and three"): in a sentence, a number
        // or a "one" is just a word ("the one with the blue icon", "wait 5 minutes").
        val onlyNumbers = words.all { it in NUMBER_FILLER || it == "last" || number(it) != null }
        val byNumber = if (!onlyNumbers) emptyList() else words.withIndex()
            // "the second one": the "one" only follows the number.
            .filterNot { (i, word) -> word == "one" && i > 0 && (words[i - 1] == "last" || number(words[i - 1]) != null) }
            .mapNotNull { (_, word) -> if (word == "last") question.choices.size else number(word) }
            .filter { it in 1..question.choices.size }
        val byWords = question.choices.withIndex()
            .filter { (_, choice) -> words(choice).let { it.isNotEmpty() && words.joinToString(" ").containsPhrase(it.joinToString(" ")) } }
            .map { it.index + 1 }
        val picked = (byNumber + byWords).distinct()
        // One to pick, several named ("keep it, don't delete it"): which isn't for a word match to guess. The words
        // go as they are, for Hermes to read.
        if (picked.isEmpty() || (!question.multiSelect && picked.size > 1)) return listOf(said)
        val chosen = picked.map { question.choices[it - 1] }
        return if (question.multiSelect) chosen else chosen.take(1)
    }

    private fun short(text: String): String {
        val line = text.replace(WHITESPACE, " ")
        return if (line.length <= MAX_SPOKEN) line else line.take(MAX_SPOKEN).substringBeforeLast(' ') + "…"
    }

    private fun words(text: String): List<String> =
        text.lowercase().replace('’', '\'').replace('‘', '\'').replace(PUNCTUATION, " ").split(WHITESPACE).filter { it.isNotEmpty() }

    private fun String.containsPhrase(phrase: String): Boolean = " $this ".contains(" $phrase ")

    private fun number(word: String): Int? = word.toIntOrNull() ?: NUMBERS[word]

    private val WHITESPACE = Regex("\\s+")
    private val PUNCTUATION = Regex("[^\\p{L}\\p{N}']+")
    private const val MAX_SPOKEN = 200

    private val DENY = setOf(
        "no", "not", "nope", "nah", "deny", "dont", "wont", "cant", "cannot", "shouldnt", "wouldnt", "reject", "refuse",
        "cancel", "never", "stop",
    )
    /** Doubt or a question: asked again rather than taken as an answer. */
    private val HEDGES = setOf("but", "if", "what", "why", "how", "which", "when", "where", "who", "wait", "maybe", "unless", "first", "hold")
    private val QUESTION_STARTS = setOf("is", "can", "could", "would", "will", "should", "does", "are", "did", "shall", "may")
    private val ALWAYS_WORDS =setOf("always", "permanently", "forever")
    private val SESSION_WORDS = setOf("chat", "session")
    /** Words that grant it, once unless they say how far. */
    private val ONCE = setOf(
        "yes", "yeah", "yep", "yup", "sure", "ok", "okay", "allow", "allowed", "approve", "approved", "once", "fine",
        "go", "do", "run", "proceed",
    )
    /** All a grant may be said with: anything else in it makes it something other than a plain answer. */
    private val GRANT_WORDS = ONCE + ALWAYS_WORDS + SESSION_WORDS + setOf(
        "ahead", "it", "for", "this", "that", "that's", "thats", "the", "just", "only", "time", "please", "now", "i",
        "you", "can", "is", "in", "and",
    )

    private val NUMBER_FILLER = setOf("the", "number", "option", "choice", "and", "please", "both", "a", "i", "pick", "take", "want")

    private val NUMBERS = mapOf(
        "one" to 1, "first" to 1, "two" to 2, "second" to 2, "three" to 3, "third" to 3, "four" to 4, "fourth" to 4,
        "five" to 5, "fifth" to 5, "six" to 6, "sixth" to 6, "seven" to 7, "seventh" to 7, "eight" to 8, "eighth" to 8,
        "nine" to 9, "ninth" to 9, "ten" to 10, "tenth" to 10,
    )
}
