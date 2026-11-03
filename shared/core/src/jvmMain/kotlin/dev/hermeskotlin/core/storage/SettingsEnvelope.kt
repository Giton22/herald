package dev.hermeskotlin.core.storage

import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KDF
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.HKDFParameterSpec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal class SettingsPayload(val values: Map<String, String>, val legacyDigest: ByteArray?)

internal class SettingsEnvelope(val nonce: ByteArray, val ciphertext: ByteArray, val token: String?) {
    companion object {
        private const val FORMAT = "kyllene.settings.encrypted"
        private val base64 = Base64.getEncoder()
        private val unbase64 = Base64.getDecoder()

        fun isEncrypted(root: JsonObject) = (root["format"] as? JsonPrimitive)?.contentOrNull == FORMAT

        fun parse(bytes: ByteArray): JsonObject = try {
            Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
                ?: throw SecureStorageException(SecureStorageException.Reason.Corrupt)
        } catch (_: SerializationException) { throw SecureStorageException(SecureStorageException.Reason.Corrupt) }
          catch (_: java.nio.charset.CharacterCodingException) { throw SecureStorageException(SecureStorageException.Reason.Corrupt) }

        fun values(root: JsonObject): Map<String, String> = root.mapValues { (_, value) ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw SecureStorageException(SecureStorageException.Reason.Corrupt)
        }

        fun read(root: JsonObject): SettingsEnvelope {
            if ((root["version"] as? JsonPrimitive)?.intOrNull != 1) {
                throw SecureStorageException(SecureStorageException.Reason.Version)
            }
            try {
                fun text(name: String) = (root[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw SecureStorageException(SecureStorageException.Reason.Corrupt)
                val nonce = unbase64.decode(text("nonce"))
                val ciphertext = unbase64.decode(text("ciphertext"))
                if (nonce.size != 12 || ciphertext.size < 16) throw SecureStorageException(SecureStorageException.Reason.Corrupt)
                val token = if (root.containsKey("token")) text("token") else null
                return SettingsEnvelope(nonce, ciphertext, token)
            } catch (_: IllegalArgumentException) { throw SecureStorageException(SecureStorageException.Reason.Corrupt) }
        }

        fun derive(secret: ByteArray, appId: String): SecretKey {
            val parameters = HKDFParameterSpec.ofExtract().addIKM(secret)
                .addSalt(FORMAT.encodeToByteArray()).thenExpand(appId.encodeToByteArray(), 32)
            return KDF.getInstance("HKDF-SHA256").deriveKey("AES", parameters)
        }

        fun seal(values: Map<String, String>, key: SecretKey, appId: String, token: String?, legacyDigest: ByteArray?): ByteArray {
            val payload = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
                "values" to JsonObject(values.mapValues { JsonPrimitive(it.value) }))
            if (legacyDigest != null) payload["legacy"] = JsonPrimitive(base64.encodeToString(legacyDigest))
            val plain = JsonObject(payload).toString().encodeToByteArray()
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key)
                cipher.updateAAD(context(appId, token))
                val result = linkedMapOf("format" to JsonPrimitive(FORMAT), "version" to JsonPrimitive(1),
                    "nonce" to JsonPrimitive(base64.encodeToString(cipher.iv)),
                    "ciphertext" to JsonPrimitive(base64.encodeToString(cipher.doFinal(plain))))
                if (token != null) result["token"] = JsonPrimitive(token)
                return JsonObject(result).toString().encodeToByteArray()
            } finally { plain.fill(0) }
        }

        private fun context(appId: String, token: String?) = JsonObject(buildMap {
            put("format", JsonPrimitive(FORMAT)); put("version", JsonPrimitive(1)); put("app", JsonPrimitive(appId))
            if (token != null) put("token", JsonPrimitive(token))
        }).toString().encodeToByteArray()
    }

    fun open(key: SecretKey, appId: String): SettingsPayload {
        val plain = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce)); updateAAD(context(appId, token))
                doFinal(ciphertext)
            }
        } catch (_: GeneralSecurityException) { throw SecureStorageException(SecureStorageException.Reason.Corrupt) }
        try {
            val payload = parse(plain)
            val map = payload["values"] as? JsonObject ?: throw SecureStorageException(SecureStorageException.Reason.Corrupt)
            val legacy = payload["legacy"]?.let {
                val text = (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
                    ?: throw SecureStorageException(SecureStorageException.Reason.Corrupt)
                try { unbase64.decode(text).also { digest ->
                    if (digest.size != 32) throw SecureStorageException(SecureStorageException.Reason.Corrupt)
                } } catch (_: IllegalArgumentException) { throw SecureStorageException(SecureStorageException.Reason.Corrupt) }
            }
            return SettingsPayload(values(map), legacy)
        } finally { plain.fill(0) }
    }
}
