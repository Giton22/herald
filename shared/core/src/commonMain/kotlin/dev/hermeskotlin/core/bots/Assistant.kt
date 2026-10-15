package dev.hermeskotlin.core.bots

/** The profile the voice assistant runs on, unless the user names it otherwise in setup. */
const val ASSISTANT_PROFILE = "assistant"

/** The assistant's title in the Bots roster and its introduction. */
const val ASSISTANT_TITLE = "Assistant"

const val ASSISTANT_DESCRIPTION = "Voice assistant on the phone's assistant button"

/**
 * Toolsets an assistant keeps on: what a spoken question tends to need. Every other toolset is pinned off, since
 * every enabled tool goes out on every call (latency and tokens) and terminal, file or browser access shouldn't come
 * with a lock-screen trigger by default.
 */
val ASSISTANT_TOOLSETS: List<String> = listOf("web", "memory", "session_search", "todo", "clarify", "skills", "vision")

/**
 * Off until the user turns it on in setup: a lock-screen trigger shouldn't schedule unattended jobs that deliver
 * output by default.
 */
const val ASSISTANT_OPTIONAL_TOOLSET = "cronjob"

/** The SOUL: the reply format. Spoken prose, no Markdown, since the reply is read aloud. */
val ASSISTANT_SOUL: String = """
You are a voice assistant. Your replies are spoken aloud, so answer in short, plain sentences. Do not use Markdown,
bullet lists, tables, headings, code blocks or links. Give the answer first, and add one sentence of detail only when
it helps. If the answer is long or needs a list, say the short version and offer to put the details in the chat.
Keep the tone direct and friendly.
""".trimIndent()
