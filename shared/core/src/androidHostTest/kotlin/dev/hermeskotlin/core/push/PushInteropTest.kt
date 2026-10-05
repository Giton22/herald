package dev.hermeskotlin.core.push

import dev.hermeskotlin.core.network.HermesJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The phone and the herald-push plugin speak one protocol in two languages. A vector sealed by the plugin's
 * Python must open here; one sealed here is written out for the plugin's tests to open
 * (hermes-herald-push/tests/test_interop.py).
 */
class PushInteropTest {

    @Test
    fun opensWhatThePluginSealed() {
        val vector = HermesJson.parseToJsonElement(
            javaClass.classLoader!!.getResource("push-vector-g2p.json")!!.readText(),
        ).jsonObject
        fun field(name: String) = vector[name]!!.jsonPrimitive.content
        val envelope = HermesJson.decodeFromJsonElement(PushEnvelope.serializer(), vector["envelope"] as JsonObject)
        val opened = PushCrypto.open(
            envelope,
            field("topic"),
            PushDirection.GatewayToPhone,
            PushCrypto.decodePrivate(PushCrypto.unb64(field("phone_enc_pkcs8"))),
            PushCrypto.decodePublic(field("gateway_sig_pub")),
        )
        assertEquals(field("plaintext"), opened.decodeToString())
    }

    @Test
    fun writesAPhoneVectorForThePlugin() {
        val gatewayEnc = PushCrypto.generateKeyPair()
        val phoneSig = PushCrypto.generateKeyPair()
        val topic = PushCrypto.newTopic()
        val plaintext = """{"id":"k1","ts":1791170000,"type":"approval_answer","request_id":"r1","choice":"once"}"""
        val envelope = PushCrypto.seal(plaintext.encodeToByteArray(), topic, PushDirection.PhoneToGateway, gatewayEnc.public, phoneSig.private)
        val vector = buildJsonObject {
            put("topic", topic)
            put("plaintext", plaintext)
            put("gateway_enc_pkcs8", PushCrypto.b64(PushCrypto.encodePrivate(gatewayEnc.private)))
            put("phone_sig_pub", PushCrypto.encodePublic(phoneSig.public))
            put("envelope", HermesJson.encodeToJsonElement(PushEnvelope.serializer(), envelope))
        }
        File("build/push-vector-p2g.json").apply { parentFile.mkdirs() }.writeText(vector.toString())
    }
}
