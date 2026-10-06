package dev.hermeskotlin.core.voice

import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.apiCall
import dev.hermeskotlin.core.network.map
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** A synthesized reply: audio bytes and their type (`audio/mpeg`, `audio/ogg`, …). */
class SpokenAudio(val bytes: ByteArray, val mimeType: String)

@Serializable
private data class TranscribeRequest(@SerialName("data_url") val dataUrl: String, @SerialName("mime_type") val mimeType: String)

@Serializable
private data class TranscribeResponse(val transcript: String = "")

@Serializable
private data class SpeakRequest(val text: String)

@Serializable
private data class SpeakResponse(@SerialName("data_url") val dataUrl: String = "", @SerialName("mime_type") val mimeType: String? = null)

@Serializable
private data class LeaseRequest(val lease: String, val active: Boolean)

/**
 * Which voice chat the profile picked (`voice.voice_chat_mode`): Desktop's chained listen → send → read
 * aloud, or GPT-Live, and whether GPT-Live can start (an OpenAI key resolves on the gateway).
 */
@Serializable
data class VoiceLiveStatus(
    val mode: String = "chained",
    val available: Boolean = false,
    val reason: String? = null,
    val model: String? = null,
    val voice: String? = null,
) {
    /** GPT-Live is picked and can start. */
    val live: Boolean get() = mode == "gpt-live" && available
}

@Serializable
private data class LiveSessionRequest(val sdp: String, val history: List<LiveHistoryMessage>)

@Serializable
private data class LiveTransport(val sdp: String = "")

@Serializable
private data class LiveSessionResponse(val transport: LiveTransport? = null)

/**
 * The gateway's voice relay, the endpoints Desktop's voice chat uses: speech-to-text and
 * text-to-speech run with the profile's configured providers (hermes_cli/web_routers/audio.py).
 */
class AudioApi(private val client: HttpClient) {

    /** Transcribes a recording; an empty transcript means nothing was said. */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun transcribe(url: GatewayUrl, audio: ByteArray, mimeType: String, profile: String?): ApiResult<String> = apiCall {
        client.post(url.resolve("api/audio/transcribe")) {
            profile?.let { parameter("profile", it) }
            contentType(ContentType.Application.Json)
            setBody(TranscribeRequest("data:$mimeType;base64,${Base64.encode(audio)}", mimeType))
            // Remote providers and long clips take a while.
            timeout { requestTimeoutMillis = SLOW_MS }
        }
    }.map { it.body<TranscribeResponse>().transcript.trim() }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun speak(url: GatewayUrl, text: String, profile: String?): ApiResult<SpokenAudio> = apiCall {
        client.post(url.resolve("api/audio/speak")) {
            profile?.let { parameter("profile", it) }
            contentType(ContentType.Application.Json)
            setBody(SpeakRequest(text))
            timeout { requestTimeoutMillis = SLOW_MS }
        }
    }.map { response ->
        val body = response.body<SpeakResponse>()
        val mime = body.mimeType ?: body.dataUrl.substringAfter("data:").substringBefore(';').ifBlank { "audio/mpeg" }
        SpokenAudio(Base64.decode(body.dataUrl.substringAfter("base64,")), mime)
    }

    /** The profile's voice chat mode; an older gateway without GPT-Live fails, which means chained. */
    suspend fun voiceLiveStatus(url: GatewayUrl, profile: String?): ApiResult<VoiceLiveStatus> = apiCall {
        client.get(url.resolve("api/audio/voice-live/status")) {
            profile?.let { parameter("profile", it) }
        }
    }.map { it.body<VoiceLiveStatus>() }

    /**
     * Trades this device's WebRTC offer for GPT-Live's answer. The gateway makes the call with the OpenAI key,
     * which never reaches the phone, and opens the call with [history], the chat so far. Blank: no answer came.
     */
    suspend fun startVoiceLive(url: GatewayUrl, offerSdp: String, history: List<LiveHistoryMessage>, profile: String?): ApiResult<String> = apiCall {
        client.post(url.resolve("api/audio/voice-live/session")) {
            profile?.let { parameter("profile", it) }
            contentType(ContentType.Application.Json)
            setBody(LiveSessionRequest(offerSdp, history))
            timeout { requestTimeoutMillis = LIVE_SESSION_MS }
        }
    }.map { it.body<LiveSessionResponse>().transport?.sdp.orEmpty() }

    /**
     * Tells the gateway a voice surface is active, so it warms the speech engine before the first
     * reply (a local model load would otherwise be dead air), and releases it after.
     */
    suspend fun ttsLease(url: GatewayUrl, active: Boolean, profile: String?): ApiResult<Unit> = apiCall {
        client.post(url.resolve("api/audio/tts-lease")) {
            profile?.let { parameter("profile", it) }
            contentType(ContentType.Application.Json)
            setBody(LeaseRequest(LEASE, active))
            timeout { requestTimeoutMillis = LEASE_MS }
        }
    }.map { }

    private companion object {
        const val SLOW_MS = 120_000L
        const val LEASE_MS = 180_000L
        const val LIVE_SESSION_MS = 45_000L
        const val LEASE = "android:conversation"
    }
}
