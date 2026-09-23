package pro.liliya.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseCanonicalPayload
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicenseSignature
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseVersion

internal class ProductionAndroidPendingActivationEnvelopeStore private constructor(
    private val root: File,
    private val alias: String
) {
    private val target = File(root, FILE_NAME)

    fun save(envelope: LicenseSignedEnvelope): Boolean = synchronized(lock) {
        val plaintext = encode(envelope) ?: return@synchronized false
        try {
            if (!root.exists() && !root.mkdirs()) return@synchronized false
            if (!root.isDirectory) return@synchronized false
            val key = loadOrCreateKey() ?: return@synchronized false
            val sealed = seal(key, plaintext) ?: return@synchronized false
            try {
                publishAtomically(sealed)
            } finally {
                sealed.fill(0)
            }
            true
        } catch (_: Throwable) {
            false
        } finally {
            plaintext.fill(0)
        }
    }

    fun openEnvelope(): LicenseSignedEnvelope = synchronized(lock) {
        if (!target.isFile || target.length() !in 1..MAX_FILE_BYTES.toLong()) {
            throw IllegalStateException(UNAVAILABLE)
        }
        val encoded = try {
            target.readBytes()
        } catch (_: Throwable) {
            throw IllegalStateException(UNAVAILABLE)
        }
        try {
            val envelope = decodeSealed(encoded) ?: throw IllegalStateException(UNAVAILABLE)
            val key = loadKey() ?: throw IllegalStateException(UNAVAILABLE)
            val plaintext = open(key, envelope)
            try {
                decode(plaintext) ?: throw IllegalStateException(UNAVAILABLE)
            } finally {
                plaintext.fill(0)
            }
        } finally {
            encoded.fill(0)
        }
    }

    fun exists(): Boolean = target.isFile

    private fun encode(envelope: LicenseSignedEnvelope): ByteArray? = try {
        val algorithm = envelope.algorithm.value.encodeToByteArray()
        val keyId = envelope.signingKeyId.value.encodeToByteArray()
        val payload = envelope.payload.copyBytes()
        val signature = envelope.signature.copyBytes()
        try {
            if (
                algorithm.size !in 1..MAX_TEXT_BYTES ||
                keyId.size !in 1..MAX_TEXT_BYTES ||
                payload.size !in 1..MAX_PAYLOAD_BYTES ||
                signature.size !in 1..MAX_SIGNATURE_BYTES
            ) return null
            val output = ByteArrayOutputStream()
            DataOutputStream(output).use { data ->
                data.writeInt(PLAIN_MAGIC)
                data.writeInt(PLAIN_VERSION)
                data.writeLong(envelope.schemaVersion.value)
                data.writeInt(algorithm.size)
                data.writeInt(keyId.size)
                data.writeInt(payload.size)
                data.writeInt(signature.size)
                data.write(algorithm)
                data.write(keyId)
                data.write(payload)
                data.write(signature)
            }
            output.toByteArray()
        } finally {
            algorithm.fill(0)
            keyId.fill(0)
            payload.fill(0)
            signature.fill(0)
        }
    } catch (_: Throwable) {
        null
    }

    private fun decode(bytes: ByteArray): LicenseSignedEnvelope? = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            if (data.readInt() != PLAIN_MAGIC || data.readInt() != PLAIN_VERSION) return null
            val schema = data.readLong()
            val algorithmSize = data.readInt()
            val keyIdSize = data.readInt()
            val payloadSize = data.readInt()
            val signatureSize = data.readInt()
            if (schema <= 0L) return null
            if (algorithmSize !in 1..MAX_TEXT_BYTES) return null
            if (keyIdSize !in 1..MAX_TEXT_BYTES) return null
            if (payloadSize !in 1..MAX_PAYLOAD_BYTES) return null
            if (signatureSize !in 1..MAX_SIGNATURE_BYTES) return null
            val expected = PLAIN_HEADER_BYTES + algorithmSize + keyIdSize + payloadSize + signatureSize
            if (bytes.size != expected) return null

            val algorithm = ByteArray(algorithmSize)
            val keyId = ByteArray(keyIdSize)
            val payload = ByteArray(payloadSize)
            val signature = ByteArray(signatureSize)
            data.readFully(algorithm)
            data.readFully(keyId)
            data.readFully(payload)
            data.readFully(signature)
            try {
                LicenseSignedEnvelope(
                    schemaVersion = LicenseVersion(schema),
                    algorithm = LicenseAlgorithm(algorithm.toString(Charsets.UTF_8)),
                    signingKeyId = LicenseKeyId(keyId.toString(Charsets.UTF_8)),
                    payload = LicenseCanonicalPayload.of(payload),
                    signature = LicenseSignature.of(signature)
                )
            } finally {
                algorithm.fill(0)
                keyId.fill(0)
                payload.fill(0)
                signature.fill(0)
            }
        }
    } catch (_: Throwable) {
        null
    }

    private fun seal(key: SecretKey, plaintext: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(plaintext)
        if (cipher.iv.size !in MIN_NONCE_BYTES..MAX_NONCE_BYTES || ciphertext.isEmpty()) return null
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(SEAL_MAGIC)
            data.writeInt(SEAL_VERSION)
            data.writeInt(cipher.iv.size)
            data.writeInt(ciphertext.size)
            data.write(cipher.iv)
            data.write(ciphertext)
        }
        output.toByteArray()
    } catch (_: Throwable) {
        null
    }

    private fun open(key: SecretKey, envelope: SealedEnvelope): ByteArray {
        val input = envelope.ciphertext.copyOf()
        return try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, envelope.nonce))
            cipher.updateAAD(AAD)
            cipher.doFinal(input)
        } catch (_: AEADBadTagException) {
            throw IllegalStateException(UNAVAILABLE)
        } catch (_: Throwable) {
            throw IllegalStateException(UNAVAILABLE)
        } finally {
            input.fill(0)
        }
    }

    private fun decodeSealed(bytes: ByteArray): SealedEnvelope? = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            if (data.readInt() != SEAL_MAGIC || data.readInt() != SEAL_VERSION) return null
            val nonceSize = data.readInt()
            val ciphertextSize = data.readInt()
            if (nonceSize !in MIN_NONCE_BYTES..MAX_NONCE_BYTES) return null
            if (ciphertextSize !in 1..MAX_CIPHERTEXT_BYTES) return null
            if (bytes.size != SEAL_HEADER_BYTES + nonceSize + ciphertextSize) return null
            val nonce = ByteArray(nonceSize)
            val ciphertext = ByteArray(ciphertextSize)
            data.readFully(nonce)
            data.readFully(ciphertext)
            SealedEnvelope(nonce, ciphertext)
        }
    } catch (_: Throwable) {
        null
    }

    private fun publishAtomically(bytes: ByteArray) {
        val temp = File(root, TEMP_NAME)
        try {
            FileOutputStream(temp, false).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (failure: AtomicMoveNotSupportedException) {
                throw IllegalStateException("atomic activation envelope publication unavailable", failure)
            }
            try {
                FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
            } catch (_: Throwable) {
                Unit
            }
        } finally {
            temp.delete()
        }
    }

    private fun loadOrCreateKey(): SecretKey? {
        loadKey()?.let { return it }
        return try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generator.generateKey()
        } catch (_: Throwable) {
            null
        }
    }

    private fun loadKey(): SecretKey? = try {
        keyStore().getKey(alias, null) as? SecretKey
    } catch (_: Throwable) {
        null
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private data class SealedEnvelope(val nonce: ByteArray, val ciphertext: ByteArray)

    companion object {
        private const val DIRECTORY = "liliya-activation-envelope-v1"
        private const val FILE_NAME = "activation-envelope.lae"
        private const val TEMP_NAME = "activation-envelope.lae.tmp"
        private const val DEFAULT_ALIAS = "pro.liliya.activation-envelope.v1.aes"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val PLAIN_MAGIC = 0x4C414531
        private const val PLAIN_VERSION = 1
        private const val SEAL_MAGIC = 0x4C414553
        private const val SEAL_VERSION = 1
        private const val TAG_BITS = 128
        private const val MIN_NONCE_BYTES = 12
        private const val MAX_NONCE_BYTES = 32
        private const val MAX_TEXT_BYTES = 512
        private const val MAX_PAYLOAD_BYTES = 64 * 1024
        private const val MAX_SIGNATURE_BYTES = 8 * 1024
        private const val PLAIN_HEADER_BYTES = 28
        private const val SEAL_HEADER_BYTES = 16
        private const val MAX_PLAINTEXT_BYTES =
            PLAIN_HEADER_BYTES + MAX_TEXT_BYTES * 2 + MAX_PAYLOAD_BYTES + MAX_SIGNATURE_BYTES
        private const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + 32
        private const val MAX_FILE_BYTES = SEAL_HEADER_BYTES + MAX_NONCE_BYTES + MAX_CIPHERTEXT_BYTES
        private const val UNAVAILABLE = "activation envelope unavailable"
        private val AAD = "liliya-activation-envelope|v1|aes-256-gcm".encodeToByteArray()
        private val lock = Any()

        fun create(
            context: Context,
            alias: String = DEFAULT_ALIAS
        ): ProductionAndroidPendingActivationEnvelopeStore {
            require(alias.isNotBlank()) { "activation envelope key alias must not be blank" }
            val root = File(context.applicationContext.filesDir, DIRECTORY).canonicalFile
            return ProductionAndroidPendingActivationEnvelopeStore(root, alias)
        }
    }
}
