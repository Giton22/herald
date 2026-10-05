package dev.hermeskotlin.core.push

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The plaintext inside a push envelope (docs/push-protocol.md). One shape for every type; the fields a type
 * doesn't use stay null.
 */
@Serializable
data class PushMessage(
    val id: String,
    val ts: Long,
    val type: String,
    val bot: String? = null,
    val label: String? = null,
    val session: String? = null,
    val text: String? = null,
    @SerialName("request_id") val requestId: String? = null,
    val command: String? = null,
    val description: String? = null,
    val choices: List<String> = emptyList(),
    val expires: Long? = null,
    val choice: String? = null,
) {
    companion object {
        // The types the phone acts on. The others in docs/push-protocol.md (approval, and the
        // phone → gateway ones) arrive with the code that handles them.
        const val BOT_MESSAGE = "bot_message"
        const val PING = "ping"

        /** Gateway → phone messages are news; older than a day they're dropped. */
        const val MAX_AGE_G2P_SECONDS = 24 * 3600L

        /** Clocks differ; further ahead than this is a forgery or a broken clock. */
        const val MAX_SKEW_SECONDS = 300L
    }
}

/** What the phone sends the gateway once, over the trusted connection, to receive pushes. */
@Serializable
data class PushRegistration(
    @SerialName("device_id") val deviceId: String,
    val name: String,
    val server: String,
    @SerialName("push_topic") val pushTopic: String,
    @SerialName("reply_topic") val replyTopic: String,
    @SerialName("enc_pub") val encPub: String,
    @SerialName("sig_pub") val sigPub: String,
)

/** The gateway's answer: the keys the phone pins. */
@Serializable
data class PushGateway(
    @SerialName("gateway_id") val gatewayId: String,
    @SerialName("enc_pub") val encPub: String,
    @SerialName("sig_pub") val sigPub: String,
    val version: Int = 1,
)
