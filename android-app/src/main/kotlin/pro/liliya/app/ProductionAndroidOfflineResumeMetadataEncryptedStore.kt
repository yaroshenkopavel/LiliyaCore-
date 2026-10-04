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
import pro.liliya.core.encryption.CognitiveDekGeneration
import pro.liliya.core.encryption.CognitiveDekId
import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.persistence.PersistentStoreId

internal data class ProductionAndroidOfflineResumeMetadata(
    val activeDek: CognitiveDekReference,
    val memoryStoreId: PersistentStoreId,
    val knowledgeStoreId: PersistentStoreId,
    val learningMutationStoreId: PersistentStoreId,
    val cognitiveStorageDirectoryName: String?,
    val semanticDirectoryName: String
)

internal sealed interface ProductionAndroidOfflineResumeMetadataStoreResult {
    data object Stored : ProductionAndroidOfflineResumeMetadataStoreResult
    data object Rejected : ProductionAndroidOfflineResumeMetadataStoreResult
    data object Failed : ProductionAndroidOfflineResumeMetadataStoreResult
}

internal sealed interface ProductionAndroidOfflineResumeMetadataLoadResult {
    data object Missing : ProductionAndroidOfflineResumeMetadataLoadResult
    data class Loaded(
        val metadata: ProductionAndroidOfflineResumeMetadata
    ) : ProductionAndroidOfflineResumeMetadataLoadResult
    data object Rejected : ProductionAndroidOfflineResumeMetadataLoadResult
}

/**
 * Integrity-protected durable metadata for exact offline runtime reconstruction.
 *
 * Resume Metadata != License.
 * Resume Metadata != Product Auth.
 * Resume Metadata != Authority.
 * Resume Metadata != Execution permission.
 *
 * The store contains only bounded non-secret stable identifiers. Every load must still pass the
 * normal License verification, device-binding, security-floor, Authority/admission, model and
 * cognitive-storage checks before runtime startup.
 */
internal class ProductionAndroidOfflineResumeMetadataEncryptedStore private constructor(
    private val root: File,
    private val alias: String
) {
    private val target = File(root, FILE_NAME)

    fun store(
        metadata: ProductionAndroidOfflineResumeMetadata
    ): ProductionAndroidOfflineResumeMetadataStoreResult = synchronized(lock) {
        val plaintext = encodeMetadata(metadata)
            ?: return@synchronized ProductionAndroidOfflineResumeMetadataStoreResult.Rejected
        try {
            if (!root.exists() && !root.mkdirs()) {
                return@synchronized ProductionAndroidOfflineResumeMetadataStoreResult.Failed
            }
            if (!root.isDirectory) {
                return@synchronized ProductionAndroidOfflineResumeMetadataStoreResult.Failed
            }
            val key = loadOrCreateKey()
                ?: return@synchronized ProductionAndroidOfflineResumeMetadataStoreResult.Failed
            val sealed = seal(key, plaintext)
                ?: return@synchronized ProductionAndroidOfflineResumeMetadataStoreResult.Failed
            try {
                publishAtomically(sealed)
            } finally {
                sealed.fill(0)
            }
            ProductionAndroidOfflineResumeMetadataStoreResult.Stored
        } catch (_: Throwable) {
            ProductionAndroidOfflineResumeMetadataStoreResult.Failed
        } finally {
            plaintext.fill(0)
        }
    }

    fun load(): ProductionAndroidOfflineResumeMetadataLoadResult = synchronized(lock) {
        if (!target.exists()) return@synchronized ProductionAndroidOfflineResumeMetadataLoadResult.Missing
        if (!target.isFile || target.length() !in 1..MAX_FILE_BYTES.toLong()) {
            return@synchronized ProductionAndroidOfflineResumeMetadataLoadResult.Rejected
        }
        val encoded = try {
            target.readBytes()
        } catch (_: Throwable) {
            return@synchronized ProductionAndroidOfflineResumeMetadataLoadResult.Rejected
        }
        try {
            val outer = decodeOuter(encoded)
                ?: return@synchronized ProductionAndroidOfflineResumeMetadataLoadResult.Rejected
            val key = loadKey()
                ?: return@synchronized ProductionAndroidOfflineResumeMetadataLoadResult.Rejected
            val plaintext = try {
                open(key, outer)
            } catch (_: Throwable) {
                return@synchronized ProductionAndroidOfflineResumeMetadataLoadResult.Rejected
            }
            try {
                val metadata = decodeMetadata(plaintext)
                    ?: return@synchronized ProductionAndroidOfflineResumeMetadataLoadResult.Rejected
                ProductionAndroidOfflineResumeMetadataLoadResult.Loaded(metadata)
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

    private fun encodeMetadata(metadata: ProductionAndroidOfflineResumeMetadata): ByteArray? = try {
        val values = listOf(
            metadata.activeDek.id.value,
            metadata.memoryStoreId.value,
            metadata.knowledgeStoreId.value,
            metadata.learningMutationStoreId.value,
            metadata.cognitiveStorageDirectoryName.orEmpty(),
            metadata.semanticDirectoryName
        )
        if (
            metadata.activeDek.generation.value <= 0L ||
            values.any { it.length > MAX_STRING_CHARS } ||
            values.take(4).any { it.isBlank() } ||
            metadata.semanticDirectoryName.isBlank()
        ) return null

        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(INNER_MAGIC)
                data.writeInt(INNER_VERSION)
                data.writeLong(metadata.activeDek.generation.value)
                values.forEach { writeString(data, it) }
            }
            output.toByteArray().takeIf { it.size in 1..MAX_PLAINTEXT_BYTES }
        }
    } catch (_: Throwable) {
        null
    }

    private fun decodeMetadata(bytes: ByteArray): ProductionAndroidOfflineResumeMetadata? = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            if (data.readInt() != INNER_MAGIC || data.readInt() != INNER_VERSION) return null
            val generation = data.readLong()
            if (generation <= 0L) return null
            val dekId = readString(data) ?: return null
            val memory = readString(data) ?: return null
            val knowledge = readString(data) ?: return null
            val mutation = readString(data) ?: return null
            val cognitiveDirectory = readString(data) ?: return null
            val semanticDirectory = readString(data) ?: return null
            if (data.available() != 0) return null
            if (
                dekId.isBlank() || memory.isBlank() || knowledge.isBlank() ||
                mutation.isBlank() || semanticDirectory.isBlank()
            ) return null

            ProductionAndroidOfflineResumeMetadata(
                activeDek = CognitiveDekReference(
                    id = CognitiveDekId(dekId),
                    generation = CognitiveDekGeneration(generation)
                ),
                memoryStoreId = PersistentStoreId(memory),
                knowledgeStoreId = PersistentStoreId(knowledge),
                learningMutationStoreId = PersistentStoreId(mutation),
                cognitiveStorageDirectoryName = cognitiveDirectory.ifBlank { null },
                semanticDirectoryName = semanticDirectory
            )
        }
    } catch (_: Throwable) {
        null
    }

    private fun writeString(data: DataOutputStream, value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= MAX_STRING_BYTES)
        data.writeInt(bytes.size)
        data.write(bytes)
        bytes.fill(0)
    }

    private fun readString(data: DataInputStream): String? {
        val size = data.readInt()
        if (size !in 0..MAX_STRING_BYTES || size > data.available()) return null
        val bytes = ByteArray(size)
        return try {
            data.readFully(bytes)
            bytes.decodeToString()
        } finally {
            bytes.fill(0)
        }
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
            val plaintext = cipher.doFinal(input)
            if (plaintext.size !in 1..MAX_PLAINTEXT_BYTES) {
                plaintext.fill(0)
                throw IllegalStateException("resume metadata plaintext rejected")
            }
            plaintext
        } catch (_: AEADBadTagException) {
            throw IllegalStateException("resume metadata authentication rejected")
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
                throw IllegalStateException("atomic resume metadata publication unavailable", failure)
            }
            runCatching {
                FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
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

    companion object {
        private const val DIRECTORY = "liliya-offline-resume-metadata-v1"
        private const val FILE_NAME = "resume.lrm"
        private const val TEMP_NAME = "resume.lrm.tmp"
        private const val DEFAULT_ALIAS = "pro.liliya.offline-resume-metadata.v1.aes"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val OUTER_MAGIC = 0x4C524D31
        private const val OUTER_VERSION = 1
        private const val INNER_MAGIC = 0x4C524931
        private const val INNER_VERSION = 1
        private const val TAG_BITS = 128
        private const val MIN_NONCE_BYTES = 12
        private const val MAX_NONCE_BYTES = 32
        private const val MAX_STRING_CHARS = 512
        private const val MAX_STRING_BYTES = 2048
        private const val MAX_PLAINTEXT_BYTES = 16 * 1024
        private const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + 32
        private const val OUTER_HEADER_BYTES = 16
        private const val MAX_FILE_BYTES =
            OUTER_HEADER_BYTES + MAX_NONCE_BYTES + MAX_CIPHERTEXT_BYTES
        private val AAD =
            "liliya-offline-resume-metadata|v1|aes-256-gcm".encodeToByteArray()
        private val lock = Any()

        fun create(
            context: Context,
            alias: String = DEFAULT_ALIAS
        ): ProductionAndroidOfflineResumeMetadataEncryptedStore {
            require(alias.isNotBlank()) { "offline resume metadata key alias must not be blank" }
            val root = File(
                context.applicationContext.noBackupFilesDir,
                DIRECTORY
            ).canonicalFile
            return ProductionAndroidOfflineResumeMetadataEncryptedStore(root, alias)
        }
    }
}
