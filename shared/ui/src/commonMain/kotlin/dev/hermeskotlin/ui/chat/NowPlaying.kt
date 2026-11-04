package dev.hermeskotlin.ui.chat

/**
 * Keeps one kind of player sounding at a time across the app: the chat's clip player and the players in the
 * agent's screens. A player that starts says so with [started]; every player of another [Group] that's playing is
 * paused. Players of the same group are left alone, so the screens keep their own rules among themselves. Called on
 * the main thread only.
 */
internal class NowPlaying {

    enum class Group { Chat, Screens }

    private class Playing(val group: Group, val pause: () -> Unit)

    private val playing = LinkedHashMap<Any, Playing>()

    /** [player] of [group] started (or is about to); [pause] stops it when another group's player starts. */
    fun started(player: Any, group: Group, pause: () -> Unit) {
        val others = playing.filter { (key, it) -> key !== player && it.group != group }
        // Gone from the list before they're told, so a pause that reports back with [stopped] finds nothing to do.
        others.keys.forEach { playing.remove(it) }
        playing[player] = Playing(group, pause)
        others.values.forEach { it.pause() }
    }

    /** [player] paused, ended or went away. */
    fun stopped(player: Any) {
        playing.remove(player)
    }

    /** Whether [player] is playing, as far as this knows. */
    fun isPlaying(player: Any): Boolean = player in playing

    companion object {
        /** The app's own: the chat's player and the screens' players share it. */
        val app = NowPlaying()
    }
}
