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
import pro.liliya.core.licensetransport.LicenseHttpBearerCredential

internal sealed interface ProductionAndroidProductAuthProvisionResult {
    data object Provisioned : ProductionAndroidProductAuthProvisionResult
    data object AlreadyProvisioned : ProductionAndroidProductAuthProvisionResult
    data object Rejected : ProductionAndroidProductAuthProvisionResult
    data object Failed : ProductionAndroidProductAuthProvisionResult
}

/**
 * Dedicated app-private encrypted owner for the product request-authentication secret.
 *
 * This key domain is intentionally separate from Device Key, cognitive DEKs, protected-model DEKs,
 * and durable License state. The store owns no License entitlement, trust, Authority or Execution.
 */
internal class ProductionAndroidProductAuthEncryptedStore private constructor(
    private val root: File,
    private val alias: String
) : ProductionAndroidProductAuthCredentialSource {
    private val target = File(root, FILE_NAME)

    fun provision(secret: ByteArray): ProductionAndroidProductAuthProvisionResult = synchronized(lock) {
        if (target.exists()) {
            return@synchronized ProductionAndroidProductAuthProvisionResult.AlreadyProvisioned
        }
        if (secret.isEmpty() || secret.size > MAX_SECRET_BYTES) {
            return@synchronized ProductionAndroidProductAuthProvisionResult.Rejected
        }

        val plaintext = secret.copyOf()
        try {
            try {
                LicenseHttpBearerCredential.of(plaintext).close()
            } catch (_: IllegalArgumentException) {
                return@synchronized ProductionAndroidProductAuthProvisionResult.Rejected
            }

            if (!root.exists() && !root.mkdirs()) {
                return@synchronized ProductionAndroidProductAuthProvisionResult.Failed
            }
            if (!root.isDirectory) {
                return@synchronized ProductionAndroidProductAuthProvisionResult.Failed
            }

            val key = loadOrCreateKey()
                ?: return@synchronized ProductionAndroidProductAuthProvisionResult.Failed
            val encoded = seal(key, plaintext)
                ?: return@synchronized ProductionAndroidProductAuthProvisionResult.Failed
            try {
                publishAtomically(encoded)
            } finally {
                encoded.fill(0)
            }
            ProductionAndroidProductAuthProvisionResult.Provisioned
        } catch (_: Throwable) {
            ProductionAndroidProductAuthProvisionResult.Failed
        } finally {
            plaintext.fill(0)
        }
    }

    override fun openSecret(): ByteArray = synchronized(lock) {
        if (!target.isFile || target.length() !in 1..MAX_FILE_BYTES.toLong()) {
            throw IllegalStateException(UNAVAILABLE)
        }
        val encoded = try {
            target.readBytes()
        } catch (_: Throwable) {
            throw IllegalStateException(UNAVAILABLE)
        }
        try {
            val envelope = decode(encoded) ?: throw IllegalStateException(UNAVAILABLE)
            val key = loadKey() ?: throw IllegalStateException(UNAVAILABLE)
            return@synchronized open(key, envelope)
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
        if (cipher.iv.size !in MIN_NONCE_BYTES..MAX_NONCE_BYTES || ciphertext.isEmpty()) return null
        encode(cipher.iv, ciphertext)
    } catch (_: Throwable) {
        null
    }

    private fun open(key: SecretKey, envelope: Envelope): ByteArray {
        val input = envelope.ciphertext.copyOf()
        return try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, envelope.nonce))
            cipher.updateAAD(AAD)
            val plaintext = cipher.doFinal(input)
            if (plaintext.isEmpty() || plaintext.size > MAX_SECRET_BYTES) {
                plaintext.fill(0)
                throw IllegalStateException(UNAVAILABLE)
            }
            try {
                LicenseHttpBearerCredential.of(plaintext).close()
            } catch (_: IllegalArgumentException) {
                plaintext.fill(0)
                throw IllegalStateException(UNAVAILABLE)
            }
            plaintext
        } catch (_: AEADBadTagException) {
            throw IllegalStateException(UNAVAILABLE)
        } catch (_: IllegalStateException) {
            throw IllegalStateException(UNAVAILABLE)
        } catch (_: Throwable) {
            throw IllegalStateException(UNAVAILABLE)
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
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE
            )
            try {
                FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
            } catch (_: Throwable) {
                Unit
            }
        } catch (failure: AtomicMoveNotSupportedException) {
            throw IllegalStateException("atomic product-auth publication unavailable", failure)
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

    private data class Envelope(val nonce: ByteArray, val ciphertext: ByteArray)

    private fun encode(nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeInt(nonce.size)
            data.writeInt(ciphertext.size)
            data.write(nonce)
            data.write(ciphertext)
        }
        return output.toByteArray()
    }

    private fun decode(bytes: ByteArray): Envelope? = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            if (data.readInt() != MAGIC || data.readInt() != VERSION) return null
            val nonceSize = data.readInt()
            val ciphertextSize = data.readInt()
            if (nonceSize !in MIN_NONCE_BYTES..MAX_NONCE_BYTES) return null
            if (ciphertextSize !in 1..MAX_CIPHERTEXT_BYTES) return null
            if (bytes.size != HEADER_BYTES + nonceSize + ciphertextSize) return null
            val nonce = ByteArray(nonceSize)
            val ciphertext = ByteArray(ciphertextSize)
            data.readFully(nonce)
            data.readFully(ciphertext)
            Envelope(nonce, ciphertext)
        }
    } catch (_: Throwable) {
        null
    }

    companion object {
        private const val DIRECTORY = "liliya-product-auth-v1"
        private const val FILE_NAME = "credential.lpa"
        private const val TEMP_NAME = "credential.lpa.tmp"
        private const val DEFAULT_ALIAS = "pro.liliya.product-auth.v1.aes"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val MAGIC = 0x4C504131
        private const val VERSION = 1
        private const val TAG_BITS = 128
        private const val MIN_NONCE_BYTES = 12
        private const val MAX_NONCE_BYTES = 32
        private const val MAX_SECRET_BYTES = 4096
        private const val MAX_CIPHERTEXT_BYTES = MAX_SECRET_BYTES + 32
        private const val HEADER_BYTES = 16
        private const val MAX_FILE_BYTES = HEADER_BYTES + MAX_NONCE_BYTES + MAX_CIPHERTEXT_BYTES
        private const val UNAVAILABLE = "product auth credential unavailable"
        private val AAD = "liliya-product-auth|v1|aes-256-gcm".encodeToByteArray()
        private val lock = Any()

        fun create(
            context: Context,
            alias: String = DEFAULT_ALIAS
        ): ProductionAndroidProductAuthEncryptedStore {
            require(alias.isNotBlank()) { "product-auth key alias must not be blank" }
            val root = File(context.applicationContext.filesDir, DIRECTORY).canonicalFile
            return ProductionAndroidProductAuthEncryptedStore(root, alias)
        }
    }
}
