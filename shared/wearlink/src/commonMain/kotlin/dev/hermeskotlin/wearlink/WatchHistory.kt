package dev.hermeskotlin.wearlink

import kotlinx.serialization.Serializable

/** A read-only request for stored messages, owned independently of the phone's browsing profile. */
@Serializable
data class HistoryRequest(
    val requestId: String,
    val scope: WatchScope,
    val browserScope: WatchScope,
    val chatId: String? = null,
    val botProfile: String? = null,
    val offset: Int = 0,
)

@Serializable
data class HistoryMessage(val id: String, val role: String, val text: String)

@Serializable
data class HistoryPage(
    val request: HistoryRequest,
    val chatId: String? = null,
    val messages: List<HistoryMessage> = emptyList(),
    val nextOffset: Int? = null,
    val error: String? = null,
)

/** One scoped transcript; responses from an earlier selection never enter it. */
class HistoryPager {
    var page: HistoryPage? = null
        private set
    var loading: Boolean = false
        private set
    private var pending: HistoryRequest? = null

    fun begin(request: HistoryRequest) {
        pending = request
        loading = true
        val previous = page?.request
        if (request.offset == 0 || previous == null || request.scope != previous.scope ||
            request.browserScope != previous.browserScope || request.chatId != previous.chatId || request.botProfile != previous.botProfile) {
            page = HistoryPage(request)
        }
    }

    fun accept(response: HistoryPage): Boolean {
        if (response.request != pending) return false
        pending = null
        loading = false
        val previous = page
        page = when {
            response.request.offset == 0 || previous == null -> response
            response.error != null -> previous.copy(error = response.error)
            response.chatId != previous.chatId -> HistoryPage(response.request, error = "The conversation changed. Refresh to read it again.")
            else -> response.copy(messages = (response.messages + previous.messages).distinctBy { it.id })
        }
        return true
    }

    /** A reply or incoming call can adopt a different stored conversation without opening the picker. */
    fun select(scope: WatchScope?, chatId: String?, botProfile: String?): Boolean {
        val previous = pending ?: page?.request ?: return false
        if (previous.scope == scope && previous.chatId == chatId && previous.botProfile == botProfile) return false
        reset()
        return true
    }

    fun reset() { pending = null; page = null; loading = false }
}
