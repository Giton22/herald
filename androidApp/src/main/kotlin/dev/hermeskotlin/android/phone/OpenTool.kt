package dev.hermeskotlin.android.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.net.toUri
import dev.hermeskotlin.android.R
import dev.hermeskotlin.core.phone.PhoneTool
import dev.hermeskotlin.core.phone.PhoneToolException
import dev.hermeskotlin.core.phone.checkLink
import dev.hermeskotlin.core.phone.forQuestion
import dev.hermeskotlin.core.phone.hidden
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * `phone_open`: a web link, a place or directions in the maps app, an installed app, or the share sheet with some
 * text, when enabled. The question shows exactly what opens: a link's real site first, all of the text
 * to share, and nothing hidden in any of it.
 */
internal class OpenTool(private val context: Context, private val confirmations: PhoneConfirmations) {

    suspend fun run(args: JsonObject): JsonObject {
        val value = args.string("value")?.trim()?.takeIf { it.isNotEmpty() } ?: throw PhoneToolException("value is needed.")
        if (value.codePointCount(0, value.length) > MAX_VALUE) throw PhoneToolException("value is at most $MAX_VALUE characters.")
        val navigate = args.boolean("navigate") ?: false
        val kind = args.string("kind")
        // What the user reads is what happens: nothing hidden, and no line breaks but in text to share.
        if (hidden(value, newlines = kind == "share")) throw PhoneToolException("value has hidden characters or odd spaces in it.")
        return when (kind) {
            "url" -> url(value)
            "map" -> map(value, navigate)
            "app" -> app(value)
            "share" -> share(value)
            else -> throw PhoneToolException("kind must be url, map, app or share.")
        }
    }

    private suspend fun url(value: String): JsonObject {
        val link = checkLink(value)
        // The browser only: an app that claims the link (a bank, a shop) would open it signed in, which isn't what was asked.
        val browser = browser() ?: throw PhoneToolException("The phone has no browser.")
        val intent = Intent(Intent.ACTION_VIEW, link.url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(browser)
        val secure = if (link.secure) "" else confirmations.text(R.string.phone_open_not_secure)
        start(intent, confirmations.text(R.string.phone_open_link, link.site, secure, shorten(link.url)), "The phone has no browser.")
        return done("url", "The link went to the browser.") { put("site", link.host) }
    }

    /** The user's browser: what opens a link no app claims, else the first browser there is. */
    private fun browser(): String? {
        val manager = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW, PROBE.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        val browsers = manager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }
        // With no default, this is Android's own "Open with" list, which isn't a browser.
        val chosen = manager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        if (chosen != null && chosen in browsers) return chosen
        // Else one that calls itself a browser: any app may take every link, and it shouldn't get them unasked.
        val real = manager.queryIntentActivities(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER), 0)
            .map { it.activityInfo.packageName }
        return browsers.firstOrNull { it in real }
    }

    private suspend fun map(place: String, navigate: Boolean): JsonObject {
        // All of it in the question: what the maps app searches is what the user reads.
        if (place.codePointCount(0, place.length) > QUESTION) throw PhoneToolException("A place is at most $QUESTION characters.")
        val query = Uri.encode(place)
        // Directions need Google Maps' own link; other maps apps get the place, where they offer directions.
        val directions = Intent(Intent.ACTION_VIEW, "google.navigation:q=$query".toUri())
        val navigating = navigate && directions.resolveActivity(context.packageManager) != null
        val intent = if (navigating) directions else Intent(Intent.ACTION_VIEW, "geo:0,0?q=$query".toUri())
        val what = confirmations.text(if (navigating) R.string.phone_open_directions else R.string.phone_open_map, forQuestion(place))
        start(intent, what, "The phone has no maps app.")
        return done("map", "The place went to the maps app.") {
            put("place", place)
            put("directions", navigating)
            if (navigate && !navigating) put("note_directions", "The maps app shows the place; directions start from there.")
        }
    }

    private suspend fun app(name: String): JsonObject {
        val manager = context.packageManager
        // Every icon on the home screen, by its own name: one app can have several (Phone and Contacts).
        val icons = manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(manager).toString() to ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .filter { it.second.packageName != context.packageName }
        val wanted = name.lowercase(Locale.ROOT)
        val exact = icons.filter { it.first.lowercase(Locale.ROOT) == wanted }
        val matches = exact.ifEmpty { icons.filter { it.first.lowercase(Locale.ROOT).contains(wanted) } }
        // Never other apps' names back: the agent mustn't learn what's installed by guessing.
        val (label, component) = matches.singleOrNull()
            ?: throw PhoneToolException("No one app on the phone goes by that name. Give the name exactly as under its icon.")
        // An app names itself: one with hidden characters in its name could make the question read otherwise.
        if (hidden(label, newlines = false)) throw PhoneToolException("That app's name has hidden characters in it, so it isn't opened.")
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        start(intent, confirmations.text(R.string.phone_open_app, label), "$label can't be opened.")
        return done("app", "$label was opened.") { put("app", label) }
    }

    private suspend fun share(text: String): JsonObject {
        // All of it in the question: what goes to the app the user picks is what they read.
        if (text.codePointCount(0, text.length) > QUESTION) throw PhoneToolException("Text to share is at most $QUESTION characters.")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (send.resolveActivity(context.packageManager) == null) throw PhoneToolException("Nothing on the phone can share text.")
        start(Intent.createChooser(send, null), confirmations.text(R.string.phone_open_share, forQuestion(text)), "Nothing on the phone can share text.")
        return done("share", "The share sheet opened; they pick where it goes, or close it.") {}
    }

    /** Starts [intent] after the configured confirmation, in its own task so Back and Recents work as usual. */
    private suspend fun start(intent: Intent, what: String, none: String) {
        if (intent.resolveActivity(context.packageManager) == null) throw PhoneToolException(none)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        confirmations.confirm(PhoneTool.Open, what, confirmations.text(R.string.phone_open_title), needsActivity = true) { activity -> activity.startActivity(intent) }
    }

    private fun done(kind: String, note: String, more: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("opened", kind)
        put("note", note)
        more()
    }

    /** At most [QUESTION] characters of [text] for the question, whole characters, with … when cut. */
    private fun shorten(text: String): String {
        if (text.codePointCount(0, text.length) <= QUESTION) return text
        return text.substring(0, text.offsetByCodePoints(0, QUESTION)) + "…"
    }

    private companion object {
        const val MAX_VALUE = 2000

        /** A link no app claims (example.com is kept for examples), to find the browser by. */
        const val PROBE = "https://example.com/"
        const val QUESTION = 300
    }
}
