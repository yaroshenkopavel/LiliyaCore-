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
import java.net.URL
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

internal class ProductionAndroidOfflineDeploymentLicenseTrustKey(
    val keyId: String,
    material: ByteArray
) {
    private val materialBytes = material.copyOf()
    fun copyMaterial(): ByteArray = materialBytes.copyOf()
}

internal class ProductionAndroidOfflineDeploymentModelSignerTrustKey(
    val signerId: String,
    material: ByteArray
) {
    private val materialBytes = material.copyOf()
    fun copyMaterial(): ByteArray = materialBytes.copyOf()
}

internal class ProductionAndroidOfflineDeploymentProfile(
    val productId: String,
    val endpoint: String,
    val connectTimeoutMillis: Int,
    val readTimeoutMillis: Int,
    tlsCertificates: List<ByteArray>,
    val supportedLicenseSchemaVersion: Long,
    licenseTrustKeys: List<ProductionAndroidOfflineDeploymentLicenseTrustKey>,
    val offlineResumePolicyId: String,
    val offlineResumePolicyVersion: Long,
    modelSignerTrustKeys: List<ProductionAndroidOfflineDeploymentModelSignerTrustKey>,
    val semanticDirectoryName: String,
    val cognitiveStorageDirectoryName: String?
) {
    private val tlsCertificateBytes = tlsCertificates.map { it.copyOf() }
    private val trustKeys = licenseTrustKeys.map {
        ProductionAndroidOfflineDeploymentLicenseTrustKey(it.keyId, it.copyMaterial())
    }
    private val modelSignerKeys = modelSignerTrustKeys.map {
        ProductionAndroidOfflineDeploymentModelSignerTrustKey(it.signerId, it.copyMaterial())
    }

    fun copyTlsCertificates(): List<ByteArray> = tlsCertificateBytes.map { it.copyOf() }
    fun copyLicenseTrustKeys(): List<ProductionAndroidOfflineDeploymentLicenseTrustKey> =
        trustKeys.map { ProductionAndroidOfflineDeploymentLicenseTrustKey(it.keyId, it.copyMaterial()) }
    fun copyModelSignerTrustKeys(): List<ProductionAndroidOfflineDeploymentModelSignerTrustKey> =
        modelSignerKeys.map {
            ProductionAndroidOfflineDeploymentModelSignerTrustKey(it.signerId, it.copyMaterial())
        }
}

internal sealed interface ProductionAndroidOfflineDeploymentProfileStoreResult {
    data object Stored : ProductionAndroidOfflineDeploymentProfileStoreResult
    data object Rejected : ProductionAndroidOfflineDeploymentProfileStoreResult
    data object Failed : ProductionAndroidOfflineDeploymentProfileStoreResult
}

internal sealed interface ProductionAndroidOfflineDeploymentProfileLoadResult {
    data object Missing : ProductionAndroidOfflineDeploymentProfileLoadResult
    data class Loaded(
        val profile: ProductionAndroidOfflineDeploymentProfile
    ) : ProductionAndroidOfflineDeploymentProfileLoadResult
    data object Rejected : ProductionAndroidOfflineDeploymentProfileLoadResult
}

/**
 * Integrity-protected durable public deployment inputs for offline reconstruction.
 *
 * Deployment Profile != Product Auth.
 * Deployment Profile != License.
 * Deployment Profile != Subject/Request/Enrollment identity.
 * Deployment Profile != Authority/Execution.
 */
internal class ProductionAndroidOfflineDeploymentProfileEncryptedStore private constructor(
    private val root: File,
    private val alias: String
) {
    private val target = File(root, FILE_NAME)

    fun store(
        profile: ProductionAndroidOfflineDeploymentProfile
    ): ProductionAndroidOfflineDeploymentProfileStoreResult = synchronized(lock) {
        val plaintext = encodeProfile(profile)
            ?: return@synchronized ProductionAndroidOfflineDeploymentProfileStoreResult.Rejected
        try {
            if (!root.exists() && !root.mkdirs()) {
                return@synchronized ProductionAndroidOfflineDeploymentProfileStoreResult.Failed
            }
            if (!root.isDirectory) {
                return@synchronized ProductionAndroidOfflineDeploymentProfileStoreResult.Failed
            }
            val key = loadOrCreateKey()
                ?: return@synchronized ProductionAndroidOfflineDeploymentProfileStoreResult.Failed
            val sealed = seal(key, plaintext)
                ?: return@synchronized ProductionAndroidOfflineDeploymentProfileStoreResult.Failed
            try {
                publishAtomically(sealed)
            } finally {
                sealed.fill(0)
            }
            ProductionAndroidOfflineDeploymentProfileStoreResult.Stored
        } catch (_: Throwable) {
            ProductionAndroidOfflineDeploymentProfileStoreResult.Failed
        } finally {
            plaintext.fill(0)
        }
    }

    fun load(): ProductionAndroidOfflineDeploymentProfileLoadResult = synchronized(lock) {
        if (!target.exists()) return@synchronized ProductionAndroidOfflineDeploymentProfileLoadResult.Missing
        if (!target.isFile || target.length() !in 1..MAX_FILE_BYTES.toLong()) {
            return@synchronized ProductionAndroidOfflineDeploymentProfileLoadResult.Rejected
        }
        val encoded = try { target.readBytes() } catch (_: Throwable) {
            return@synchronized ProductionAndroidOfflineDeploymentProfileLoadResult.Rejected
        }
        try {
            val outer = decodeOuter(encoded)
                ?: return@synchronized ProductionAndroidOfflineDeploymentProfileLoadResult.Rejected
            val key = loadKey()
                ?: return@synchronized ProductionAndroidOfflineDeploymentProfileLoadResult.Rejected
            val plaintext = try { open(key, outer) } catch (_: Throwable) {
                return@synchronized ProductionAndroidOfflineDeploymentProfileLoadResult.Rejected
            }
            try {
                val profile = decodeProfile(plaintext)
                    ?: return@synchronized ProductionAndroidOfflineDeploymentProfileLoadResult.Rejected
                ProductionAndroidOfflineDeploymentProfileLoadResult.Loaded(profile)
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
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun encodeProfile(profile: ProductionAndroidOfflineDeploymentProfile): ByteArray? = try {
        val endpoint = URL(profile.endpoint)
        if (endpoint.protocol != "https") return null
        if (
            profile.productId.isBlank() ||
            profile.productId.length > MAX_STRING_CHARS ||
            profile.endpoint.length > MAX_ENDPOINT_CHARS ||
            profile.connectTimeoutMillis !in 1..MAX_TIMEOUT_MILLIS ||
            profile.readTimeoutMillis !in 1..MAX_TIMEOUT_MILLIS ||
            profile.supportedLicenseSchemaVersion <= 0L ||
            profile.offlineResumePolicyId.isBlank() ||
            profile.offlineResumePolicyId.length > MAX_STRING_CHARS ||
            profile.offlineResumePolicyVersion <= 0L ||
            profile.semanticDirectoryName.isBlank() ||
            profile.semanticDirectoryName.length > MAX_STRING_CHARS ||
            (profile.cognitiveStorageDirectoryName?.length ?: 0) > MAX_STRING_CHARS
        ) return null

        val certs = profile.copyTlsCertificates()
        val keys = profile.copyLicenseTrustKeys()
        val modelSignerKeys = profile.copyModelSignerTrustKeys()
        if (certs.size > MAX_CERTIFICATES || keys.isEmpty() || keys.size > MAX_TRUST_KEYS) return null
        if (modelSignerKeys.isEmpty() || modelSignerKeys.size > MAX_MODEL_SIGNER_KEYS) return null
        if (certs.any { it.isEmpty() || it.size > MAX_BLOB_BYTES }) return null
        if (keys.any {
                it.keyId.isBlank() || it.keyId.length > MAX_STRING_CHARS ||
                    it.copyMaterial().let { bytes -> bytes.isEmpty() || bytes.size > MAX_BLOB_BYTES }
            }
        ) return null
        if (keys.map { it.keyId }.toSet().size != keys.size) return null
        if (modelSignerKeys.any {
                it.signerId.isBlank() || it.signerId.length > MAX_STRING_CHARS ||
                    it.copyMaterial().let { bytes -> bytes.isEmpty() || bytes.size > MAX_BLOB_BYTES }
            }
        ) return null
        if (modelSignerKeys.map { it.signerId }.toSet().size != modelSignerKeys.size) return null

        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(INNER_MAGIC)
                data.writeInt(INNER_VERSION)
                writeString(data, profile.productId)
                writeString(data, profile.endpoint)
                data.writeInt(profile.connectTimeoutMillis)
                data.writeInt(profile.readTimeoutMillis)
                data.writeLong(profile.supportedLicenseSchemaVersion)
                writeString(data, profile.offlineResumePolicyId)
                data.writeLong(profile.offlineResumePolicyVersion)
                writeString(data, profile.semanticDirectoryName)
                writeString(data, profile.cognitiveStorageDirectoryName.orEmpty())
                data.writeInt(certs.size)
                certs.forEach { writeBytes(data, it) }
                data.writeInt(keys.size)
                keys.forEach { key ->
                    writeString(data, key.keyId)
                    writeBytes(data, key.copyMaterial())
                }
                data.writeInt(modelSignerKeys.size)
                modelSignerKeys.forEach { key ->
                    writeString(data, key.signerId)
                    writeBytes(data, key.copyMaterial())
                }
            }
            output.toByteArray().takeIf { it.size in 1..MAX_PLAINTEXT_BYTES }
        }
    } catch (_: Throwable) {
        null
    }

    private fun decodeProfile(bytes: ByteArray): ProductionAndroidOfflineDeploymentProfile? = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            if (data.readInt() != INNER_MAGIC || data.readInt() != INNER_VERSION) return null
            val productId = readString(data) ?: return null
            val endpoint = readString(data) ?: return null
            val connectTimeout = data.readInt()
            val readTimeout = data.readInt()
            val schema = data.readLong()
            val policyId = readString(data) ?: return null
            val policyVersion = data.readLong()
            val semantic = readString(data) ?: return null
            val cognitive = readString(data) ?: return null

            val certCount = data.readInt()
            if (certCount !in 0..MAX_CERTIFICATES) return null
            val certs = ArrayList<ByteArray>(certCount)
            repeat(certCount) { certs += readBytes(data) ?: return null }

            val keyCount = data.readInt()
            if (keyCount !in 1..MAX_TRUST_KEYS) return null
            val keys = ArrayList<ProductionAndroidOfflineDeploymentLicenseTrustKey>(keyCount)
            repeat(keyCount) {
                val keyId = readString(data) ?: return null
                val material = readBytes(data) ?: return null
                keys += ProductionAndroidOfflineDeploymentLicenseTrustKey(keyId, material)
                material.fill(0)
            }
            val modelSignerKeyCount = data.readInt()
            if (modelSignerKeyCount !in 1..MAX_MODEL_SIGNER_KEYS) return null
            val modelSignerKeys =
                ArrayList<ProductionAndroidOfflineDeploymentModelSignerTrustKey>(modelSignerKeyCount)
            repeat(modelSignerKeyCount) {
                val signerId = readString(data) ?: return null
                val material = readBytes(data) ?: return null
                modelSignerKeys += ProductionAndroidOfflineDeploymentModelSignerTrustKey(
                    signerId,
                    material
                )
                material.fill(0)
            }
            if (data.available() != 0) return null

            val profile = ProductionAndroidOfflineDeploymentProfile(
                productId = productId,
                endpoint = endpoint,
                connectTimeoutMillis = connectTimeout,
                readTimeoutMillis = readTimeout,
                tlsCertificates = certs,
                supportedLicenseSchemaVersion = schema,
                licenseTrustKeys = keys,
                offlineResumePolicyId = policyId,
                offlineResumePolicyVersion = policyVersion,
                modelSignerTrustKeys = modelSignerKeys,
                semanticDirectoryName = semantic,
                cognitiveStorageDirectoryName = cognitive.ifBlank { null }
            )
            if (encodeProfile(profile) == null) return null
            profile
        }
    } catch (_: Throwable) {
        null
    }

    private fun writeString(data: DataOutputStream, value: String) =
        writeBytes(data, value.encodeToByteArray())

    private fun readString(data: DataInputStream): String? {
        val bytes = readBytes(data) ?: return null
        return try { bytes.decodeToString() } finally { bytes.fill(0) }
    }

    private fun writeBytes(data: DataOutputStream, bytes: ByteArray) {
        require(bytes.size <= MAX_BLOB_BYTES)
        data.writeInt(bytes.size)
        data.write(bytes)
    }

    private fun readBytes(data: DataInputStream): ByteArray? {
        val size = data.readInt()
        if (size !in 0..MAX_BLOB_BYTES || size > data.available()) return null
        return ByteArray(size).also { data.readFully(it) }
    }

    private fun seal(key: SecretKey, plaintext: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(AAD)
        encodeOuter(cipher.iv, cipher.doFinal(plaintext))
    } catch (_: Throwable) {
        null
    }

    private fun open(key: SecretKey, envelope: OuterEnvelope): ByteArray {
        val input = envelope.ciphertext.copyOf()
        return try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, envelope.nonce))
            cipher.updateAAD(AAD)
            cipher.doFinal(input)
        } catch (_: AEADBadTagException) {
            throw IllegalStateException("offline deployment profile rejected")
        } finally {
            input.fill(0)
        }
    }

    private fun encodeOuter(nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(OUTER_MAGIC)
            data.writeInt(OUTER_VERSION)
            data.writeInt(nonce.size)
            data.writeInt(ciphertext.size)
            data.write(nonce)
            data.write(ciphertext)
        }
        return output.toByteArray()
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
            throw IllegalStateException("atomic offline deployment profile publication unavailable", failure)
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

    private data class OuterEnvelope(val nonce: ByteArray, val ciphertext: ByteArray)

    companion object {
        private const val DIRECTORY = "liliya-offline-deployment-profile-v1"
        private const val FILE_NAME = "profile.lodp"
        private const val TEMP_NAME = "profile.lodp.tmp"
        private const val DEFAULT_ALIAS = "pro.liliya.offline-deployment-profile.v1.aes"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val INNER_MAGIC = 0x4C4F4450
        private const val INNER_VERSION = 2
        private const val OUTER_MAGIC = 0x4C4F4431
        private const val OUTER_VERSION = 1
        private const val TAG_BITS = 128
        private const val MIN_NONCE_BYTES = 12
        private const val MAX_NONCE_BYTES = 32
        private const val MAX_STRING_CHARS = 512
        private const val MAX_ENDPOINT_CHARS = 2048
        private const val MAX_TIMEOUT_MILLIS = 300_000
        private const val MAX_CERTIFICATES = 8
        private const val MAX_TRUST_KEYS = 16
        private const val MAX_MODEL_SIGNER_KEYS = 8
        private const val MAX_BLOB_BYTES = 262_144
        private const val MAX_PLAINTEXT_BYTES = 1_048_576
        private const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + 64
        private const val OUTER_HEADER_BYTES = 16
        private const val MAX_FILE_BYTES =
            OUTER_HEADER_BYTES + MAX_NONCE_BYTES + MAX_CIPHERTEXT_BYTES
        private val AAD = "liliya-offline-deployment-profile|v1|aes-256-gcm".encodeToByteArray()
        private val lock = Any()

        fun create(
            context: Context,
            directoryName: String = DIRECTORY,
            alias: String = DEFAULT_ALIAS
        ): ProductionAndroidOfflineDeploymentProfileEncryptedStore {
            val root = File(context.applicationContext.filesDir, directoryName)
            return ProductionAndroidOfflineDeploymentProfileEncryptedStore(root, alias)
        }
    }
}
