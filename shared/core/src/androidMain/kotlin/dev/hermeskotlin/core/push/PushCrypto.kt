package dev.hermeskotlin.core.push

import dev.hermeskotlin.core.network.HermesJson
import kotlinx.serialization.Serializable
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** One message on an ntfy topic: encrypted to the recipient, signed by the sender (docs/push-protocol.md). */
@Serializable
data class PushEnvelope(val v: Int = 1, val epk: String, val salt: String, val iv: String, val ct: String, val sig: String)

/** Which way an envelope travels; part of the key derivation and the signed bytes, so neither can be reflected. */
enum class PushDirection(val wire: String) { GatewayToPhone("g2p"), PhoneToGateway("p2g") }

/** An envelope that failed its signature or decryption: dropped, never shown or acted on. */
class PushRejected(message: String) : Exception(message)

/**
 * Herald push's cryptography, protocol v1: P-256 ECDH to an ephemeral key, HKDF-SHA256, AES-256-GCM bound to
 * the ntfy topic, and an ECDSA P-256 signature over everything, checked before anything is decrypted.
 */
object PushCrypto {

    private const val VERSION = 1
    private const val INFO = "herald-push/v1/"
    private val random = SecureRandom()

    fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), random)
    }.generateKeyPair()

    /** A public key as the protocol carries it: the 65-byte uncompressed point, base64url. */
    fun encodePublic(key: PublicKey): String {
        val point = (key as ECPublicKey).w
        return b64(byteArrayOf(0x04) + point.affineX.fixed32() + point.affineY.fixed32())
    }

    fun decodePublic(encoded: String): PublicKey {
        val bytes = unb64(encoded)
        if (bytes.size != 65 || bytes[0] != 0x04.toByte()) throw PushRejected("not an uncompressed P-256 point")
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        // A point off the curve is an invalid-curve attack on ECDH: refuse it before any key agreement.
        if (!onCurve(x, y)) throw PushRejected("point not on P-256")
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), p256))
    }

    /** y² = x³ − 3x + b (mod p), with both coordinates in the field. */
    private fun onCurve(x: BigInteger, y: BigInteger): Boolean {
        if (x.signum() < 0 || y.signum() < 0 || x >= P || y >= P) return false
        val left = y.modPow(BigInteger.valueOf(2), P)
        val right = x.modPow(BigInteger.valueOf(3), P).subtract(x.multiply(BigInteger.valueOf(3))).add(B).mod(P)
        return left == right
    }

    private val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)

    fun encodePrivate(key: PrivateKey): ByteArray = key.encoded

    fun decodePrivate(pkcs8: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    /** Encrypts [plaintext] to [recipientEnc] for [topic], signed with [senderSig]. */
    fun seal(plaintext: ByteArray, topic: String, direction: PushDirection, recipientEnc: PublicKey, senderSig: PrivateKey): PushEnvelope {
        val ephemeral = generateKeyPair()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val key = deriveKey(ecdh(ephemeral.private, recipientEnc), salt, direction)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            updateAAD(topic.toByteArray(Charsets.UTF_8))
        }
        val epk = encodePublic(ephemeral.public)
        val ct = b64(cipher.doFinal(plaintext))
        val saltB = b64(salt)
        val ivB = b64(iv)
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(senderSig, random)
            update(signedBytes(direction, topic, epk, saltB, ivB, ct))
            sign()
        }
        return PushEnvelope(VERSION, epk, saltB, ivB, ct, b64(sig))
    }

    /** Verifies [envelope] came from [senderSig] for [topic], then decrypts it with [recipientEnc]. */
    fun open(envelope: PushEnvelope, topic: String, direction: PushDirection, recipientEnc: PrivateKey, senderSig: PublicKey): ByteArray {
        if (envelope.v != VERSION) throw PushRejected("unknown version ${envelope.v}")
        val valid = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(senderSig)
                update(signedBytes(direction, topic, envelope.epk, envelope.salt, envelope.iv, envelope.ct))
                verify(unb64(envelope.sig))
            }
        } catch (e: Exception) {
            false
        }
        if (!valid) throw PushRejected("bad signature")
        return try {
            val key = deriveKey(ecdh(recipientEnc, decodePublic(envelope.epk)), unb64(envelope.salt), direction)
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, unb64(envelope.iv)))
                updateAAD(topic.toByteArray(Charsets.UTF_8))
                doFinal(unb64(envelope.ct))
            }
        } catch (e: PushRejected) {
            throw e
        } catch (e: Exception) {
            throw PushRejected("can't decrypt")
        }
    }

    fun encodeEnvelope(envelope: PushEnvelope): String = HermesJson.encodeToString(PushEnvelope.serializer(), envelope)

    fun decodeEnvelope(body: String): PushEnvelope = try {
        HermesJson.decodeFromString(PushEnvelope.serializer(), body)
    } catch (e: Exception) {
        throw PushRejected("not an envelope")
    }

    /** A fresh topic: `hp-` and 128 random bits in hex, unguessable. */
    fun newTopic(): String = "hp-" + ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun signedBytes(direction: PushDirection, topic: String, epk: String, salt: String, iv: String, ct: String): ByteArray =
        listOf("herald-push/v1", direction.wire, topic, epk, salt, iv, ct).joinToString("|").toByteArray(Charsets.UTF_8)

    private fun ecdh(private: PrivateKey, public: PublicKey): ByteArray = KeyAgreement.getInstance("ECDH").run {
        init(private)
        doPhase(public, true)
        generateSecret()
    }

    /** HKDF-SHA256 (RFC 5869) to 32 bytes. */
    private fun deriveKey(ikm: ByteArray, salt: ByteArray, direction: PushDirection): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(salt, "HmacSHA256"))
            doFinal(ikm)
        }
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(prk, "HmacSHA256"))
            update((INFO + direction.wire).toByteArray(Charsets.UTF_8))
            update(1)
            doFinal()
        }
    }

    private val p256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }

    private fun BigInteger.fixed32(): ByteArray {
        val raw = toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun b64(bytes: ByteArray): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)

    @OptIn(ExperimentalEncodingApi::class)
    fun unb64(text: String): ByteArray = try {
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(text)
    } catch (e: IllegalArgumentException) {
        throw PushRejected("bad base64")
    }

    /** A short fingerprint of a public key, for showing which gateway a phone trusts. */
    fun fingerprint(encodedPublic: String): String =
        MessageDigest.getInstance("SHA-256").digest(unb64(encodedPublic)).take(6).joinToString(":") { "%02x".format(it) }
}
