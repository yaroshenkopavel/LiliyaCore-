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

internal sealed interface ProductionAndroidActivatedLicenseStoreResult {
    data object Stored : ProductionAndroidActivatedLicenseStoreResult
    data object Rejected : ProductionAndroidActivatedLicenseStoreResult
    data object Failed : ProductionAndroidActivatedLicenseStoreResult
}

internal sealed interface ProductionAndroidActivatedLicenseLoadResult {
    data object Missing : ProductionAndroidActivatedLicenseLoadResult
    data class Loaded(
        val envelope: LicenseSignedEnvelope
    ) : ProductionAndroidActivatedLicenseLoadResult
    data object Rejected : ProductionAndroidActivatedLicenseLoadResult
}

/**
 * Durable encrypted owner for the last successfully accepted signed License envelope.
 *
 * Durable License != Verified License.
 * Durable License != Authority.
 * Durable License != Execution permission.
 *
 * Every load must be followed by the normal production trust, device-binding, policy and
 * Authority/admission path before runtime startup can be installed.
 */
internal class ProductionAndroidActivatedLicenseEncryptedStore private constructor(
    private val root: File,
    private val alias: String
) {
    private val target = File(root, FILE_NAME)

    fun store(envelope: LicenseSignedEnvelope): ProductionAndroidActivatedLicenseStoreResult =
        synchronized(lock) {
            val plaintext = try {
                encodeEnvelope(envelope)
            } catch (_: Throwable) {
                return@synchronized ProductionAndroidActivatedLicenseStoreResult.Rejected
            }
            try {
                if (plaintext.size !in 1..MAX_PLAINTEXT_BYTES) {
                    return@synchronized ProductionAndroidActivatedLicenseStoreResult.Rejected
                }
                if (!root.exists() && !root.mkdirs()) {
                    return@synchronized ProductionAndroidActivatedLicenseStoreResult.Failed
                }
                if (!root.isDirectory) {
                    return@synchronized ProductionAndroidActivatedLicenseStoreResult.Failed
                }
                val key = loadOrCreateKey()
                    ?: return@synchronized ProductionAndroidActivatedLicenseStoreResult.Failed
                val sealed = seal(key, plaintext)
                    ?: return@synchronized ProductionAndroidActivatedLicenseStoreResult.Failed
                try {
                    publishAtomically(sealed)
                } finally {
                    sealed.fill(0)
                }
                ProductionAndroidActivatedLicenseStoreResult.Stored
            } catch (_: Throwable) {
                ProductionAndroidActivatedLicenseStoreResult.Failed
            } finally {
                plaintext.fill(0)
            }
        }

    fun load(): ProductionAndroidActivatedLicenseLoadResult = synchronized(lock) {
        if (!target.exists()) return@synchronized ProductionAndroidActivatedLicenseLoadResult.Missing
        if (!target.isFile || target.length() !in 1..MAX_FILE_BYTES.toLong()) {
            return@synchronized ProductionAndroidActivatedLicenseLoadResult.Rejected
        }

        val encoded = try {
            target.readBytes()
        } catch (_: Throwable) {
            return@synchronized ProductionAndroidActivatedLicenseLoadResult.Rejected
        }
        try {
            val outer = decodeOuter(encoded)
                ?: return@synchronized ProductionAndroidActivatedLicenseLoadResult.Rejected
            val key = loadKey()
                ?: return@synchronized ProductionAndroidActivatedLicenseLoadResult.Rejected
            val plaintext = try {
                open(key, outer)
            } catch (_: Throwable) {
                return@synchronized ProductionAndroidActivatedLicenseLoadResult.Rejected
            }
            try {
                val envelope = decodeEnvelope(plaintext)
                    ?: return@synchronized ProductionAndroidActivatedLicenseLoadResult.Rejected
                ProductionAndroidActivatedLicenseLoadResult.Loaded(envelope)
            } finally {
                plaintext.fill(0)
            }
        } finally {
            encoded.fill(0)
        }
    }

    internal fun publishedFileForTest(): File = target

    internal fun deleteForTests() = synchronized(lock) {
        target.delete()
        File(root, TEMP_NAME).delete()
        try {
            keyStore().deleteEntry(alias)
        } catch (_: Throwable) {
            Unit
        }
    }

    private fun seal(key: SecretKey, plaintext: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(plaintext)
        if (cipher.iv.size !in MIN_NONCE_BYTES..MAX_NONCE_BYTES) return null
        encodeOuter(cipher.iv, ciphertext)
    } catch (_: Throwable) {
        null
    }

    private fun open(key: SecretKey, envelope: OuterEnvelope): ByteArray {
        val input = envelope.ciphertext.copyOf()
        return try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, envelope.nonce))
            cipher.updateAAD(AAD)
            val plaintext = cipher.doFinal(input)
            if (plaintext.size !in 1..MAX_PLAINTEXT_BYTES) {
                plaintext.fill(0)
                throw IllegalStateException("activated license plaintext rejected")
            }
            plaintext
        } catch (_: AEADBadTagException) {
            throw IllegalStateException("activated license authentication rejected")
        } finally {
            input.fill(0)
        }
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
                throw IllegalStateException("atomic activated-license publication unavailable", failure)
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
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
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

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private data class OuterEnvelope(
        val nonce: ByteArray,
        val ciphertext: ByteArray
    )

    private fun encodeOuter(nonce: ByteArray, ciphertext: ByteArray): ByteArray =
        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(OUTER_MAGIC)
                data.writeInt(OUTER_VERSION)
                data.writeInt(nonce.size)
                data.writeInt(ciphertext.size)
                data.write(nonce)
                data.write(ciphertext)
            }
            output.toByteArray()
        }

    private fun decodeOuter(bytes: ByteArray): OuterEnvelope? = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            if (data.readInt() != OUTER_MAGIC || data.readInt() != OUTER_VERSION) return null
            val nonceSize = data.readInt()
            val ciphertextSize = data.readInt()
            if (nonceSize !in MIN_NONCE_BYTES..MAX_NONCE_BYTES) return null
            if (ciphertextSize !in 1..MAX_CIPHERTEXT_BYTES) return null
            if (bytes.size != OUTER_HEADER_BYTES + nonceSize + ciphertextSize) return null
            val nonce = ByteArray(nonceSize)
            val ciphertext = ByteArray(ciphertextSize)
            data.readFully(nonce)
            data.readFully(ciphertext)
            OuterEnvelope(nonce, ciphertext)
        }
    } catch (_: Throwable) {
        null
    }

    private fun encodeEnvelope(envelope: LicenseSignedEnvelope): ByteArray {
        val algorithm = envelope.algorithm.value.encodeToByteArray()
        val keyId = envelope.signingKeyId.value.encodeToByteArray()
        val payload = envelope.payload.copyBytes()
        val signature = envelope.signature.copyBytes()
        try {
            require(algorithm.size in 1..MAX_ALGORITHM_BYTES)
            require(keyId.size in 1..MAX_KEY_ID_BYTES)
            require(payload.size in 1..MAX_PAYLOAD_BYTES)
            require(signature.size in 1..MAX_SIGNATURE_BYTES)
            return ByteArrayOutputStream().use { output ->
                DataOutputStream(output).use { data ->
                    data.writeInt(INNER_MAGIC)
                    data.writeInt(INNER_VERSION)
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
            }
        } finally {
            algorithm.fill(0)
            keyId.fill(0)
            payload.fill(0)
            signature.fill(0)
        }
    }

    private fun decodeEnvelope(bytes: ByteArray): LicenseSignedEnvelope? = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            if (data.readInt() != INNER_MAGIC || data.readInt() != INNER_VERSION) return null
            val schemaVersion = data.readLong()
            val algorithmSize = data.readInt()
            val keyIdSize = data.readInt()
            val payloadSize = data.readInt()
            val signatureSize = data.readInt()
            if (algorithmSize !in 1..MAX_ALGORITHM_BYTES) return null
            if (keyIdSize !in 1..MAX_KEY_ID_BYTES) return null
            if (payloadSize !in 1..MAX_PAYLOAD_BYTES) return null
            if (signatureSize !in 1..MAX_SIGNATURE_BYTES) return null
            val expected = INNER_HEADER_BYTES +
                algorithmSize + keyIdSize + payloadSize + signatureSize
            if (bytes.size != expected) return null

            val algorithm = ByteArray(algorithmSize)
            val keyId = ByteArray(keyIdSize)
            val payload = ByteArray(payloadSize)
            val signature = ByteArray(signatureSize)
            try {
                data.readFully(algorithm)
                data.readFully(keyId)
                data.readFully(payload)
                data.readFully(signature)
                LicenseSignedEnvelope(
                    schemaVersion = LicenseVersion(schemaVersion),
                    algorithm = LicenseAlgorithm(algorithm.decodeToString()),
                    signingKeyId = LicenseKeyId(keyId.decodeToString()),
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

    companion object {
        private const val DIRECTORY = "liliya-activated-license-v1"
        private const val FILE_NAME = "license.lal"
        private const val TEMP_NAME = "license.lal.tmp"
        private const val DEFAULT_ALIAS = "pro.liliya.activated-license.v1.aes"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val OUTER_MAGIC = 0x4C414C31
        private const val OUTER_VERSION = 1
        private const val INNER_MAGIC = 0x4C534531
        private const val INNER_VERSION = 1
        private const val TAG_BITS = 128
        private const val MIN_NONCE_BYTES = 12
        private const val MAX_NONCE_BYTES = 32
        private const val MAX_ALGORITHM_BYTES = 128
        private const val MAX_KEY_ID_BYTES = 256
        private const val MAX_PAYLOAD_BYTES = 64 * 1024
        private const val MAX_SIGNATURE_BYTES = 4 * 1024
        private const val INNER_HEADER_BYTES = 32
        private const val MAX_PLAINTEXT_BYTES =
            INNER_HEADER_BYTES + MAX_ALGORITHM_BYTES + MAX_KEY_ID_BYTES +
                MAX_PAYLOAD_BYTES + MAX_SIGNATURE_BYTES
        private const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + 32
        private const val OUTER_HEADER_BYTES = 16
        private const val MAX_FILE_BYTES =
            OUTER_HEADER_BYTES + MAX_NONCE_BYTES + MAX_CIPHERTEXT_BYTES
        private val AAD = "liliya-activated-license|v1|aes-256-gcm".encodeToByteArray()
        private val lock = Any()

        fun create(
            context: Context,
            alias: String = DEFAULT_ALIAS
        ): ProductionAndroidActivatedLicenseEncryptedStore {
            require(alias.isNotBlank()) { "activated-license key alias must not be blank" }
            val root = File(
                context.applicationContext.noBackupFilesDir,
                DIRECTORY
            ).canonicalFile
            return ProductionAndroidActivatedLicenseEncryptedStore(root, alias)
        }
    }
}
