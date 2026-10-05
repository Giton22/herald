package dev.hermeskotlin.core.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PushCryptoTest {

    private val gatewayEnc = PushCrypto.generateKeyPair()
    private val gatewaySig = PushCrypto.generateKeyPair()
    private val phoneEnc = PushCrypto.generateKeyPair()
    private val phoneSig = PushCrypto.generateKeyPair()
    private val topic = PushCrypto.newTopic()

    private fun sealToPhone(text: String, onTopic: String = topic) =
        PushCrypto.seal(text.encodeToByteArray(), onTopic, PushDirection.GatewayToPhone, phoneEnc.public, gatewaySig.private)

    private fun openOnPhone(envelope: PushEnvelope, onTopic: String = topic, from: java.security.PublicKey = gatewaySig.public) =
        PushCrypto.open(envelope, onTopic, PushDirection.GatewayToPhone, phoneEnc.private, from).decodeToString()

    @Test
    fun roundTripsBothWays() {
        assertEquals("hello", openOnPhone(sealToPhone("hello")))
        val back = PushCrypto.seal("reply".encodeToByteArray(), topic, PushDirection.PhoneToGateway, gatewayEnc.public, phoneSig.private)
        assertEquals("reply", PushCrypto.open(back, topic, PushDirection.PhoneToGateway, gatewayEnc.private, phoneSig.public).decodeToString())
        // Through its JSON form as it travels.
        val wire = PushCrypto.encodeEnvelope(sealToPhone("über ✓"))
        assertEquals("über ✓", openOnPhone(PushCrypto.decodeEnvelope(wire)))
    }

    @Test
    fun aForgedSenderIsRefused() {
        // Someone who knows the phone's public key can encrypt to it, but can't sign as the gateway.
        val impostor = PushCrypto.generateKeyPair()
        val forged = PushCrypto.seal("approve".encodeToByteArray(), topic, PushDirection.GatewayToPhone, phoneEnc.public, impostor.private)
        assertFailsWith<PushRejected> { openOnPhone(forged) }
    }

    @Test
    fun tamperingAnyPartIsRefused() {
        val envelope = sealToPhone("hello")
        fun flip(s: String) = PushCrypto.b64(PushCrypto.unb64(s).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        listOf(
            envelope.copy(ct = flip(envelope.ct)),
            envelope.copy(iv = flip(envelope.iv)),
            envelope.copy(salt = flip(envelope.salt)),
            envelope.copy(sig = flip(envelope.sig)),
            envelope.copy(v = 2),
        ).forEach { assertFailsWith<PushRejected> { openOnPhone(it) } }
    }

    @Test
    fun anEnvelopeOnlyOpensOnItsOwnTopicAndDirection() {
        val envelope = sealToPhone("hello")
        assertFailsWith<PushRejected> { openOnPhone(envelope, onTopic = PushCrypto.newTopic()) }
        // Reflected back the other way, it's refused too.
        assertFailsWith<PushRejected> {
            PushCrypto.open(envelope, topic, PushDirection.PhoneToGateway, phoneEnc.private, gatewaySig.public)
        }
    }

    @Test
    fun publicKeysTravelAsUncompressedPointsAndBadOnesAreRefused() {
        val encoded = PushCrypto.encodePublic(phoneEnc.public)
        assertEquals(65, PushCrypto.unb64(encoded).size)
        assertEquals(phoneEnc.public, PushCrypto.decodePublic(encoded))
        // A point off the curve (invalid-curve attack) and malformed input are refused.
        val offCurve = PushCrypto.unb64(encoded).also { it[64] = (it[64].toInt() xor 1).toByte() }
        assertFailsWith<PushRejected> { PushCrypto.decodePublic(PushCrypto.b64(offCurve)) }
        assertFailsWith<PushRejected> { PushCrypto.decodePublic(PushCrypto.b64(ByteArray(33))) }
        assertFailsWith<PushRejected> { PushCrypto.decodePublic("not base64!") }
        // Private keys survive their storage form.
        val restored = PushCrypto.decodePrivate(PushCrypto.encodePrivate(phoneEnc.private))
        assertEquals("hi", PushCrypto.open(sealToPhone("hi"), topic, PushDirection.GatewayToPhone, restored, gatewaySig.public).decodeToString())
    }

    @Test
    fun topicsAreUnguessable() {
        val topics = List(50) { PushCrypto.newTopic() }
        assertTrue(topics.all { Regex("^hp-[0-9a-f]{32}$").matches(it) })
        assertEquals(50, topics.toSet().size)
    }
}
