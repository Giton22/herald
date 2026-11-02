package dev.hermeskotlin.wear

/**
 * Whether a launch asks Herald to listen: the tile's and the complication's [MainActivity.EXTRA_LISTEN], or the
 * system calling Herald as the watch's assistant. The action is mirrored as a string so the decision can be
 * tested without Android (it is `Intent.ACTION_ASSIST`).
 */
internal object AssistEntry {

    const val ACTION_ASSIST = "android.intent.action.ASSIST"

    fun asksToListen(action: String?, wantsListen: Boolean): Boolean = wantsListen || action == ACTION_ASSIST
}
