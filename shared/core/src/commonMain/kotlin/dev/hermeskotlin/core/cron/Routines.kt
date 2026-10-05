package dev.hermeskotlin.core.cron

/**
 * Bot Mode's routines are plain cron jobs, named `[bot:<profile>] <title>` and kept in the bot's own
 * profile's cron store, so a run happens as that bot, with its keys, skills and memory (cron/jobs.py).
 * Desktop's Routines pane (apps/desktop/src/plugins/hermes-bots/cron.tsx) makes and reads them this way;
 * the CLI lists them with `hermes cron list`.
 */
object Routines {
    private val BOT_TAG = Regex("""^\[bot:([a-z0-9][a-z0-9_-]*)]\s*""", RegexOption.IGNORE_CASE)

    /**
     * Delivered into the owning bot's Bot Chat as a message it reads and answers (one turn per run), rather
     * than kept only as the run's own session. Bare, so the scheduler resolves it to the job's own profile.
     */
    const val BOT_CHAT_DELIVERY = "bot-chat"

    /** The name a routine of [profile] titled [title] is stored under. */
    fun name(profile: String, title: String): String = "[bot:$profile] ${title.trim()}"

    /** The bot a job's name tags it for, or null when it isn't tagged. */
    fun taggedBot(name: String): String? = BOT_TAG.find(name)?.groupValues?.get(1)?.lowercase()

    /** [name] without its `[bot:…]` tag. */
    fun title(name: String): String = name.replace(BOT_TAG, "")

    internal const val DEFAULT_PROFILE = "default"
}

/** The job's name without a `[bot:…]` tag; the name itself for any other job. */
val CronJob.routineTitle: String get() = Routines.title(name)

/**
 * Whether the job is [bot]'s routine: one in the bot's own cron store. Before profile-scoped stores,
 * Desktop kept every routine in the launch profile's store, tagged with the bot; those count too.
 */
fun CronJob.isRoutineOf(bot: String): Boolean {
    val owner = profile ?: Routines.DEFAULT_PROFILE
    val tagged = Routines.taggedBot(name)
    // The launch store also holds other bots' older routines, tagged for them: theirs, not the default's.
    if (owner == Routines.DEFAULT_PROFILE) return (tagged ?: Routines.DEFAULT_PROFILE) == bot.lowercase()
    return owner.equals(bot, ignoreCase = true)
}

/** Why a job isn't doing what it's set to do, in a line; null while it's fine. */
val CronJob.problem: String? get() {
    val reason = listOf(lastFireError, lastDeliveryError, lastError, pausedReason).firstOrNull { !it.isNullOrBlank() }?.trim()
    val headline = when (lastStatus) {
        "error" -> "The last run failed"
        // The run worked, but its result never reached anyone: not a run to trust.
        "delivery_failed" -> "The last run's result wasn't delivered"
        "blocked_config" -> "Couldn't run: something isn't set up"
        else -> return if (state == "error") reason ?: "Stopped after an error" else null
    }
    return listOfNotNull(headline, reason?.lineSequence()?.firstOrNull()?.take(160)).joinToString(": ")
}
