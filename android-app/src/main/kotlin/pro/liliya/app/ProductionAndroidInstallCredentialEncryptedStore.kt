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

internal sealed interface ProductionAndroidInstallCredentialProvisionResult {
    data object Provisioned : ProductionAndroidInstallCredentialProvisionResult
    data object AlreadyProvisioned : ProductionAndroidInstallCredentialProvisionResult
    data object Failed : ProductionAndroidInstallCredentialProvisionResult
}

/**
 * App-private Android Keystore-backed owner for the per-install licensing credential.
 *
 * The plaintext install secret is generated locally, sealed immediately, and only reopened for
 * activation or one authenticated REFRESH attempt. It is never written to disk in plaintext.
 */
internal class ProductionAndroidInstallCredentialEncryptedStore private constructor(
    private val root: File,
    private val alias: String
) {
    private val target = File(root, FILE_NAME)

    fun provisionIfAbsent(
        generator: ProductionAndroidInstallCredentialGenerator =
            ProductionAndroidInstallCredentialGenerator()
    ): ProductionAndroidInstallCredentialProvisionResult = synchronized(lock) {
        if (target.isFile) {
            return@synchronized ProductionAndroidInstallCredentialProvisionResult.AlreadyProvisioned
        }

        val material = try {
            generator.generate()
        } catch (_: Throwable) {
            return@synchronized ProductionAndroidInstallCredentialProvisionResult.Failed
        }

        try {
            if (!root.exists() && !root.mkdirs()) {
                return@synchronized ProductionAndroidInstallCredentialProvisionResult.Failed
            }
            if (!root.isDirectory) {
                return@synchronized ProductionAndroidInstallCredentialProvisionResult.Failed
            }

            val key = loadOrCreateKey()
                ?: return@synchronized ProductionAndroidInstallCredentialProvisionResult.Failed
            val plaintext = encodePlaintext(material)
            try {
                val sealed = seal(key, plaintext)
                    ?: return@synchronized ProductionAndroidInstallCredentialProvisionResult.Failed
                try {
                    publishAtomically(sealed)
                } finally {
                    sealed.fill(0)
                }
            } finally {
                plaintext.fill(0)
            }
            ProductionAndroidInstallCredentialProvisionResult.Provisioned
        } catch (_: Throwable) {
            ProductionAndroidInstallCredentialProvisionResult.Failed
        } finally {
            material.close()
        }
    }

    fun openMaterial(): ProductionAndroidInstallCredentialMaterial = synchronized(lock) {
        if (!target.isFile || target.length() !in 1..MAX_FILE_BYTES.toLong()) {
            throw IllegalStateException(UNAVAILABLE)
        }
        val encoded = try {
            target.readBytes()
        } catch (_: Throwable) {
            throw IllegalStateException(UNAVAILABLE)
        }
        try {
            val envelope = decodeEnvelope(encoded) ?: throw IllegalStateException(UNAVAILABLE)
            val key = loadKey() ?: throw IllegalStateException(UNAVAILABLE)
            val plaintext = open(key, envelope)
            try {
                decodePlaintext(plaintext) ?: throw IllegalStateException(UNAVAILABLE)
            } finally {
                plaintext.fill(0)
            }
        } finally {
            encoded.fill(0)
        }
    }

    fun bearerFactory(): ProductionAndroidLicenseBearerCredentialFactory =
        ProductionAndroidLicenseBearerCredentialFactory {
            val material = openMaterial()
            try {
                val secret = material.copySecret()
                try {
                    LicenseHttpBearerCredential.of(secret)
                } finally {
                    secret.fill(0)
                }
            } finally {
                material.close()
            }
        }

    internal fun publishedFileForTest(): File = target

    private fun encodePlaintext(material: ProductionAndroidInstallCredentialMaterial): ByteArray {
        val idBytes = material.installId.encodeToByteArray()
        val secret = material.copySecret()
        return try {
            val output = ByteArrayOutputStream()
            DataOutputStream(output).use { data ->
                data.writeInt(PLAIN_MAGIC)
                data.writeInt(PLAIN_VERSION)
                data.writeInt(idBytes.size)
                data.writeInt(secret.size)
                data.write(idBytes)
                data.write(secret)
            }
            output.toByteArray()
        } finally {
            idBytes.fill(0)
            secret.fill(0)
        }
    }

    private fun decodePlaintext(bytes: ByteArray): ProductionAndroidInstallCredentialMaterial? =
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                if (data.readInt() != PLAIN_MAGIC || data.readInt() != PLAIN_VERSION) return null
                val idSize = data.readInt()
                val secretSize = data.readInt()
                if (idSize !in MIN_ID_BYTES..MAX_ID_BYTES || secretSize != SECRET_BYTES) return null
                if (bytes.size != HEADER_BYTES + idSize + secretSize) return null
                val id = ByteArray(idSize)
                val secret = ByteArray(secretSize)
                data.readFully(id)
                data.readFully(secret)
                val installId = id.toString(Charsets.UTF_8)
                id.fill(0)
                try {
                    ProductionAndroidInstallCredentialMaterial(installId, secret)
                } catch (_: IllegalArgumentException) {
                    secret.fill(0)
                    null
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

    private fun open(key: SecretKey, envelope: Envelope): ByteArray {
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

    private fun decodeEnvelope(bytes: ByteArray): Envelope? = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            if (data.readInt() != SEAL_MAGIC || data.readInt() != SEAL_VERSION) return null
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

    private fun publishAtomically(bytes: ByteArray) {
        val temp = File(root, TEMP_NAME)
        try {
            FileOutputStream(temp, false).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            try {
                FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
            } catch (_: Throwable) {
                Unit
            }
        } catch (failure: AtomicMoveNotSupportedException) {
            throw IllegalStateException("atomic install-credential publication unavailable", failure)
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

    companion object {
        private const val DIRECTORY = "liliya-install-credential-v1"
        private const val FILE_NAME = "install-credential.lic"
        private const val TEMP_NAME = "install-credential.lic.tmp"
        private const val DEFAULT_ALIAS = "pro.liliya.install-credential.v1.aes"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val PLAIN_MAGIC = 0x4C494331
        private const val PLAIN_VERSION = 1
        private const val SEAL_MAGIC = 0x4C494345
        private const val SEAL_VERSION = 1
        private const val TAG_BITS = 128
        private const val MIN_NONCE_BYTES = 12
        private const val MAX_NONCE_BYTES = 32
        private const val MIN_ID_BYTES = 8
        private const val MAX_ID_BYTES = 128
        private const val SECRET_BYTES = 64
        private const val HEADER_BYTES = 16
        private const val MAX_PLAINTEXT_BYTES = HEADER_BYTES + MAX_ID_BYTES + SECRET_BYTES
        private const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + 32
        private const val MAX_FILE_BYTES = HEADER_BYTES + MAX_NONCE_BYTES + MAX_CIPHERTEXT_BYTES
        private const val UNAVAILABLE = "install credential unavailable"
        private val AAD = "liliya-install-credential|v1|aes-256-gcm".encodeToByteArray()
        private val lock = Any()

        fun create(
            context: Context,
            alias: String = DEFAULT_ALIAS
        ): ProductionAndroidInstallCredentialEncryptedStore {
            require(alias.isNotBlank()) { "install credential key alias must not be blank" }
            val root = File(context.applicationContext.filesDir, DIRECTORY).canonicalFile
            return ProductionAndroidInstallCredentialEncryptedStore(root, alias)
        }
    }
}
