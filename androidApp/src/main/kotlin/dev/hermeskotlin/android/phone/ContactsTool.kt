package dev.hermeskotlin.android.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.content.ContextCompat
import dev.hermeskotlin.core.phone.PhoneToolException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `phone_contacts`: looks people up in the phone's contacts by name, number or email, and gives only their names,
 * numbers and email addresses: never notes, addresses, birthdays or photos. Names match from the start of a word, the
 * way the Contacts app's search does, and only contacts the Contacts app shows are looked at.
 */
internal class ContactsTool(private val context: Context) {

    fun run(args: JsonObject): JsonObject {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw PhoneToolException("Kyllene isn't allowed to read the contacts. The user can allow it in Kyllene: Settings → Phone tools.")
        }
        val query = args.string("query")?.trim()?.takeIf { it.length >= MIN_QUERY }
            ?: throw PhoneToolException("query is needed: at least $MIN_QUERY characters of a name, number or email.")
        if (query.length > MAX_QUERY) throw PhoneToolException("query is at most $MAX_QUERY characters.")
        val limit = (args.int("limit") ?: DEFAULT_LIMIT).also {
            if (it !in 1..MAX_LIMIT) throw PhoneToolException("limit is 1 to $MAX_LIMIT.")
        }
        // One more than asked, to tell whether there are more.
        val found = when {
            NUMBER_LIKE.matches(query) -> byNumber(query, limit + 1)
            '@' in query -> byEmail(query, limit + 1)
            else -> byName(query, limit + 1)
        }
        val people = found.entries.take(limit).associate { it.key to it.value }
        details(people)
        return buildJsonObject {
            putJsonArray("contacts") {
                for (person in people.values) {
                    addJsonObject {
                        put("name", person.name)
                        if (person.phones.isNotEmpty()) put("phones", JsonArray(person.phones.map { JsonPrimitive(it.first) }))
                        if (person.emails.isNotEmpty()) put("emails", JsonArray(person.emails.map(::JsonPrimitive)))
                    }
                }
            }
            if (found.size > limit) put("note", "There are more; ask with a narrower query.")
        }
    }

    private class Person(val name: String) {
        /** (number as saved, the same in a form to tell repeats apart). */
        val phones = mutableListOf<Pair<String, String>>()
        val emails = mutableListOf<String>()
    }

    /** Contacts whose name has a word starting with [query]: the provider's own name index, accents and case aside. */
    private fun byName(query: String, limit: Int): Map<Long, Person> {
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query))
        return collect(uri, ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, null, emptyArray(), limit)
    }

    private fun byEmail(query: String, limit: Int): Map<Long, Person> {
        val like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val where = "${ContactsContract.Data.MIMETYPE} = ? AND ${Email.ADDRESS} LIKE ? ESCAPE '\\'"
        return collect(ContactsContract.Data.CONTENT_URI, ContactsContract.Data.CONTACT_ID, ContactsContract.Data.DISPLAY_NAME_PRIMARY, where, arrayOf(Email.CONTENT_ITEM_TYPE, like), limit)
    }

    private fun byNumber(query: String, limit: Int): Map<Long, Person> {
        // Without a leading 0, so "0151 234…" finds "+49151234…".
        val digits = query.filter { it in '0'..'9' }.trimStart('0')
        if (digits.length < MIN_DIGITS) throw PhoneToolException("A number to look up needs at least $MIN_DIGITS digits.")
        // A number as saved, without its separators: not every number has a normalized form.
        val where = "${ContactsContract.Data.MIMETYPE} = ? AND (${Phone.NORMALIZED_NUMBER} LIKE ? OR ${bare(Phone.NUMBER)} LIKE ?)"
        return collect(ContactsContract.Data.CONTENT_URI, ContactsContract.Data.CONTACT_ID, ContactsContract.Data.DISPLAY_NAME_PRIMARY, where, arrayOf(Phone.CONTENT_ITEM_TYPE, "%$digits%", "%$digits%"), limit)
    }

    /** Up to [limit] people from [uri], shown ones only, favourites first and then as the Contacts app sorts them. */
    private fun collect(uri: Uri, idColumn: String, nameColumn: String, where: String?, whereArgs: Array<String>, limit: Int): Map<Long, Person> {
        val visible = "${ContactsContract.Contacts.IN_VISIBLE_GROUP} = 1"
        val people = LinkedHashMap<Long, Person>()
        context.contentResolver.query(
            uri,
            arrayOf(idColumn, nameColumn),
            if (where == null) visible else "($where) AND $visible",
            whereArgs,
            "${ContactsContract.Contacts.STARRED} DESC, ${ContactsContract.Contacts.SORT_KEY_PRIMARY} ASC",
        )?.use { c ->
            while (c.moveToNext() && people.size < limit) {
                val id = c.getLong(0)
                if (id !in people) people[id] = Person(c.getString(1)?.takeIf { it.isNotBlank() } ?: "(no name)")
            }
        }
        return people
    }

    /** Numbers and emails of [people], and nothing else of theirs; the main number first. */
    private fun details(people: Map<Long, Person>) {
        if (people.isEmpty()) return
        val marks = people.keys.joinToString(",") { "?" }
        val where = "${ContactsContract.Data.CONTACT_ID} IN ($marks) AND ${ContactsContract.Data.MIMETYPE} IN (?, ?)"
        val whereArgs = people.keys.map(Long::toString) + Phone.CONTENT_ITEM_TYPE + Email.CONTENT_ITEM_TYPE
        val columns = arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1, Phone.NORMALIZED_NUMBER)
        val order = "${ContactsContract.Data.IS_SUPER_PRIMARY} DESC, ${ContactsContract.Data.IS_PRIMARY} DESC"
        context.contentResolver.query(ContactsContract.Data.CONTENT_URI, columns, where, whereArgs.toTypedArray(), order)?.use { c ->
            while (c.moveToNext()) {
                val person = people[c.getLong(0)] ?: continue
                val value = c.getString(2)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                if (c.getString(1) == Phone.CONTENT_ITEM_TYPE) {
                    // The same number often comes from two accounts, written two ways.
                    val same = c.getString(3)?.takeIf { it.isNotBlank() } ?: value.filter { it in '0'..'9' || it == '+' }
                    if (person.phones.none { it.second == same }) person.phones += value to same
                } else if (person.emails.none { it.equals(value, ignoreCase = true) }) {
                    person.emails += value
                }
            }
        }
    }

    private fun bare(column: String) = listOf(" ", "-", ".", "(", ")", "/").fold(column) { inner, mark -> "REPLACE($inner, '$mark', '')" }

    private companion object {
        const val MIN_QUERY = 2
        const val MAX_QUERY = 100
        const val MIN_DIGITS = 5
        const val DEFAULT_LIMIT = 5
        const val MAX_LIMIT = 20

        /** A phone number, or a piece of one: digits and the marks numbers are written with. */
        val NUMBER_LIKE = Regex("""^\+?[0-9 ()./-]+$""")
    }
}
