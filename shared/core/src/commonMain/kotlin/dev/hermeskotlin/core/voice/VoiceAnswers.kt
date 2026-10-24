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
     * command, so anything doubtful isn't one: not knowing or a wait is asked again; a negation anywhere else denies;
     * a question, a "but" or an "if" is asked again; and a grant counts only when it's all that was said ("yes, go
     * ahead", "for this chat", "always"): a grant word in passing ("okay, let me think", "show it in the chat") is
     * asked again.
     */
    fun approval(request: InputRequest.Approval, transcript: String): ApprovalChoice? {
        // "Um, yes" is yes; a lone "okay" is one too.
        val words = words(transcript).let { all -> all.dropWhile(::filler).ifEmpty { all } }
        if (words.isEmpty()) return null
        val said = words.joinToString(" ")
        val choice = when {
            // Not knowing or putting it off is said with a negation too ("I don't know what that does", "no, wait"):
            // that's asked again, not a no.
            HOLD_PHRASES.any { said.containsPhrase(it) } || words.any { it in HOLD_ON } -> return null
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
     * Whether [transcript], said once [request] was asked, is its answer as it stands, to take without the voice
     * handing it over: a clear approval, or anything said to a question but a question back or a hedge ("which one is
     * faster", "hmm, wait", "I'm not sure"). The rest is left to the voice, which talks it through and hands the
     * answer over: a lone "okay" too, which may only be "I heard you" and would run a command here.
     */
    fun takesAsAnswer(request: InputRequest, transcript: String): Boolean {
        // "Um, blue" is blue.
        val words = words(transcript).dropWhile(::filler)
        if ('?' in transcript) return false
        // Fillers alone ("okay", "umm"): no answer to take yet.
        if (words.isEmpty()) return false
        val said = " ${words.joinToString(" ")} "
        // "How about blue" offers an answer; "do I need to pick" asks.
        val asksBack = (words.first() in ASKS_BACK && !said.startsWith(" how about ")) ||
            (words.first() == "do" && words.getOrNull(1) in PEOPLE) ||
            // "when the tests pass" answers; "when does it run" asks.
            (words.first() == "when" && words.getOrNull(1) in AUXILIARIES)
        val holdsOff = words.first() in QUESTION_STARTS || asksBack || words.none { it !in UNSURE } ||
            words.none { it !in NOTED } || words.any { it in HOLD_ON } || HOLD_PHRASES.any { " $it " in said } ||
            // "One minute" is putting it off, unless it's in an answer ("every one minute").
            said.trim() in HOLD_ALONE || asksForTime(words)
        if (holdsOff) return false
        return when (request) {
            // "I don't know what that does" isn't a no; "um, yes" is a yes.
            is InputRequest.Approval -> approval(request, transcript) != null
            is InputRequest.Clarify -> {
                val question = request.questions.singleOrNull() ?: return false
                // A bare "okay" while they think names none of the choices.
                !(question.choices.isNotEmpty() && words.all { it in ACKS } && clarify(question, transcript) == listOf(transcript.trim()))
            }
            else -> false
        }
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

    /** "Give me five minutes", "give me a couple of seconds": a wait, however long; "updates every minute" isn't one. */
    private fun asksForTime(words: List<String>) = words.windowed(2).withIndex().any { (i, pair) ->
        val after = words.drop(i + 2).take(4)
        pair == listOf("give", "me") && after.any { it in TIME_WORDS } && after.none { it in EVERY }
    }

    private fun filler(word: String) = word in FILLERS || FILLER_SOUND.matches(word)

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

    /** First words that ask back rather than answer (speech to text drops the "?": "which one is faster"). */
    private val ASKS_BACK = setOf("what", "why", "how", "which", "where", "who", "pardon", "repeat", "huh", "explain", "say")
    private val PEOPLE = setOf("i", "you", "we", "they")
    private val AUXILIARIES = setOf("is", "are", "does", "do", "did", "will", "would", "should", "can", "could")
    /** All "got it" or "cool" is made of: heard, not answered. */
    private val NOTED = setOf("got", "it", "cool")

    /** Words before the answer, not part of it: "sorry, the blue one". Said alone, there's no answer. */
    private val FILLERS = setOf("so", "well", "sorry", "okay", "ok", "alright")
    /** Sounds, however long they're drawn out: "umm", "hmmm", "mhm". */
    private val FILLER_SOUND = Regex("u+h*m*|u+m+|h*m+|m+h+m+|e+r+m*|e+h+|a+h+|o+h+")

    /** All a lone acknowledgement is made of. */
    private val ACKS = setOf("yeah", "yes", "right", "sure", "got", "it", "fine", "cool")
    /** All a guess with nothing in it is made of: "maybe", "I guess probably". */
    private val UNSURE = setOf("maybe", "probably", "perhaps", "i", "guess", "think", "possibly")

    /** Words that put the answer off. */
    private val HOLD_ON = setOf("wait", "hold", "moment", "sec", "secs", "hang", "dunno", "idk", "unsure")

    /** Ways of putting it off, of not knowing or of not having heard, that aren't an answer. */
    private val HOLD_PHRASES = listOf(
        "just a second", "just a minute", "just one second", "just one minute", "give me a second", "give me a minute",
        "missed that", "come again",
        "let me think", "let me see", "let me check", "let me look", "let's see", "lets see",
        "not sure", "don't know", "dont know", "do not know", "don't remember", "no idea", "no clue", "not certain",
        "didn't catch", "didn't hear", "one more time", "say again", "again please", "tell me", "remind me",
        "options again", "read the options", "list the options",
    )
    private val EVERY = setOf("every", "each", "per")
    private val TIME_WORDS = setOf("second", "seconds", "sec", "secs", "minute", "minutes", "min", "mins", "moment")
    /** Said on its own, a wait. */
    private val HOLD_ALONE = setOf(
        "one second", "one minute", "two seconds", "two minutes", "a few seconds", "a few minutes", "a second", "a minute",
    )
    private val ALWAYS_WORDS = setOf("always", "permanently", "forever")
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
