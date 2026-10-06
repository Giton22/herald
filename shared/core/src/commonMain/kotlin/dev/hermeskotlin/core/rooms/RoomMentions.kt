package dev.hermeskotlin.core.rooms

/**
 * Who a message to a room asks first, by the gateway's own rule (`hosted_room_discussion.resolve_mentions`):
 * the members whose handles it names with `@`, every member for `@all` or `@everyone`, and every member when
 * it names no one the room knows. Handles match without regard to case.
 */
fun roomRecipients(text: String, members: List<RoomMember>): List<RoomMember> {
    val handles = members.mapNotNull { it.handle?.lowercase() }.toSet()
    val named = mutableSetOf<String>()
    var everyone = false
    for (match in MENTION.findAll(text)) {
        val handle = match.groupValues[1].lowercase()
        when {
            handle == "all" || handle == "everyone" -> everyone = true
            handle in handles -> named += handle
        }
    }
    if (everyone || named.isEmpty()) return members
    return members.filter { it.handle?.lowercase() in named }
}

/** The members [query] (what's typed after `@`) can mean, by handle or by any word of their name, best first. */
fun List<RoomMember>.mentionable(query: String): List<RoomMember> {
    val q = query.lowercase()
    return mapNotNull { member -> member.handle?.lowercase()?.let { member to it } }
        .filter { (member, handle) ->
            q.isEmpty() || handle.contains(q) || member.label.lowercase().split(' ').any { it.startsWith(q) }
        }
        .sortedWith(compareByDescending<Pair<RoomMember, String>> { it.second.startsWith(q) }.thenBy { it.first.label.lowercase() })
        .map { it.first }
}

/** Whether [query] can still become `@all`, the room's word for every member. */
fun mentionsEveryone(query: String): Boolean = EVERYONE.any { it.startsWith(query.lowercase()) }

private val EVERYONE = listOf("all", "everyone")

/** The gateway's mention pattern, exactly. */
private val MENTION = Regex("@([A-Za-z0-9][A-Za-z0-9._:-]*)")
