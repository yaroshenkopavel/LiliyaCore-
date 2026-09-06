package pro.liliya.android.cognitivestorage

import android.content.Context
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import pro.liliya.android.devicekey.AndroidCognitiveKeyProtector
import pro.liliya.android.persistence.AndroidDurablePersistentRecordBackend
import pro.liliya.core.encryption.CognitiveAeadProvider
import pro.liliya.core.encryption.CognitiveAeadSealedData
import pro.liliya.core.encryption.CognitiveAssociatedData
import pro.liliya.core.encryption.CognitiveDekMaterial
import pro.liliya.core.encryption.CognitiveDekMaterialSource
import pro.liliya.core.encryption.CognitiveEncryptionFailureCategory
import pro.liliya.core.encryption.CognitiveEncryptionProfile
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitiveEnvelopeVersion
import pro.liliya.core.encryption.CognitiveNonce
import pro.liliya.core.encryption.CognitiveNonceSource
import pro.liliya.core.encryption.CognitivePlaintext
import pro.liliya.core.encryption.EncryptedPersistentRecordStore
import pro.liliya.core.encryption.PersistentCognitiveDekOpenResult
import pro.liliya.core.encryption.PersistentCognitiveDekStore
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.knowledge.EncryptedPersistentKnowledgeComposition
import pro.liliya.core.knowledge.EncryptedPersistentKnowledgeOpenResult
import pro.liliya.core.learning.EncryptedPersistentLearningApplicationMutationComposition
import pro.liliya.core.learning.EncryptedPersistentLearningApplicationMutationOpenResult
import pro.liliya.core.memory.EncryptedPersistentMemoryComposition
import pro.liliya.core.memory.EncryptedPersistentMemoryOpenResult
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult

sealed interface AndroidCognitiveStorageOpenResult {
    data class Ready(
        val assembly: AndroidCognitiveStorageAssembly
    ) : AndroidCognitiveStorageOpenResult

    data object Corrupt : AndroidCognitiveStorageOpenResult
    data class Incompatible(val reason: String) : AndroidCognitiveStorageOpenResult
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : AndroidCognitiveStorageOpenResult
}

sealed interface AndroidEncryptedMemoryOpenResult {
    data class Opened(
        val composition: EncryptedPersistentMemoryComposition
    ) : AndroidEncryptedMemoryOpenResult

    data object Corrupt : AndroidEncryptedMemoryOpenResult
    data class Incompatible(val reason: String) : AndroidEncryptedMemoryOpenResult
    data class EncryptionUnavailable(
        val category: CognitiveEncryptionFailureCategory
    ) : AndroidEncryptedMemoryOpenResult
    data class Failed(val reason: String) : AndroidEncryptedMemoryOpenResult
}

sealed interface AndroidEncryptedKnowledgeOpenResult {
    data class Opened(
        val composition: EncryptedPersistentKnowledgeComposition
    ) : AndroidEncryptedKnowledgeOpenResult

    data object Corrupt : AndroidEncryptedKnowledgeOpenResult
    data class Incompatible(val reason: String) : AndroidEncryptedKnowledgeOpenResult
    data class EncryptionUnavailable(
        val category: CognitiveEncryptionFailureCategory
    ) : AndroidEncryptedKnowledgeOpenResult
    data class Failed(val reason: String) : AndroidEncryptedKnowledgeOpenResult
}

sealed interface AndroidEncryptedLearningMutationOpenResult {
    data class Opened(
        val composition: EncryptedPersistentLearningApplicationMutationComposition
    ) : AndroidEncryptedLearningMutationOpenResult

    data object Corrupt : AndroidEncryptedLearningMutationOpenResult
    data class Incompatible(val reason: String) : AndroidEncryptedLearningMutationOpenResult
    data class EncryptionUnavailable(val reason: String) : AndroidEncryptedLearningMutationOpenResult
    data class Failed(val reason: String) : AndroidEncryptedLearningMutationOpenResult
}

sealed interface AndroidEncryptedRecordStoreOpenResult {
    data class Opened(
        val store: EncryptedPersistentRecordStore
    ) : AndroidEncryptedRecordStoreOpenResult

    data object Corrupt : AndroidEncryptedRecordStoreOpenResult
    data class Incompatible(val reason: String) : AndroidEncryptedRecordStoreOpenResult
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : AndroidEncryptedRecordStoreOpenResult
}

/**
 * Production Android composition for encrypted cognitive storage.
 *
 * This assembly does not create, rotate, replace or recover a key protector implicitly. Callers
 * must explicitly create/inspect an exact protector through [keyProtector] and pass the resulting
 * descriptor when registering a DEK.
 */
class AndroidCognitiveStorageAssembly private constructor(
    private val foundation: FoundationComposition,
    internal val backend: AndroidDurablePersistentRecordBackend,
    val keyProtector: AndroidCognitiveKeyProtector,
    val dekStore: PersistentCognitiveDekStore,
    private val nonceSource: CognitiveNonceSource,
    private val aead: CognitiveAeadProvider
) {
    fun openEncryptedMemory(
        storeId: PersistentStoreId,
        activeDek: pro.liliya.core.encryption.CognitiveDekReference
    ): AndroidEncryptedMemoryOpenResult =
        when (val encrypted = openEncryptedRecordStore(storeId)) {
            is AndroidEncryptedRecordStoreOpenResult.Opened ->
                when (
                    val opened = EncryptedPersistentMemoryComposition.open(
                        foundation = foundation,
                        encryptedStore = encrypted.store,
                        activeDek = activeDek
                    )
                ) {
                    is EncryptedPersistentMemoryOpenResult.Opened ->
                        AndroidEncryptedMemoryOpenResult.Opened(opened.composition)
                    EncryptedPersistentMemoryOpenResult.Corrupt ->
                        AndroidEncryptedMemoryOpenResult.Corrupt
                    is EncryptedPersistentMemoryOpenResult.Incompatible ->
                        AndroidEncryptedMemoryOpenResult.Incompatible(opened.reason)
                    is EncryptedPersistentMemoryOpenResult.EncryptionUnavailable ->
                        AndroidEncryptedMemoryOpenResult.EncryptionUnavailable(opened.category)
                    is EncryptedPersistentMemoryOpenResult.RestorationFailed ->
                        AndroidEncryptedMemoryOpenResult.Failed(opened.reason)
                }

            AndroidEncryptedRecordStoreOpenResult.Corrupt ->
                AndroidEncryptedMemoryOpenResult.Corrupt
            is AndroidEncryptedRecordStoreOpenResult.Incompatible ->
                AndroidEncryptedMemoryOpenResult.Incompatible(encrypted.reason)
            is AndroidEncryptedRecordStoreOpenResult.Failed ->
                AndroidEncryptedMemoryOpenResult.Failed(encrypted.reason)
        }

    fun openEncryptedKnowledge(
        storeId: PersistentStoreId,
        activeDek: pro.liliya.core.encryption.CognitiveDekReference
    ): AndroidEncryptedKnowledgeOpenResult =
        when (val encrypted = openEncryptedRecordStore(storeId)) {
            is AndroidEncryptedRecordStoreOpenResult.Opened ->
                when (
                    val opened = EncryptedPersistentKnowledgeComposition.open(
                        foundation = foundation,
                        encryptedStore = encrypted.store,
                        activeDek = activeDek
                    )
                ) {
                    is EncryptedPersistentKnowledgeOpenResult.Opened ->
                        AndroidEncryptedKnowledgeOpenResult.Opened(opened.composition)
                    EncryptedPersistentKnowledgeOpenResult.Corrupt ->
                        AndroidEncryptedKnowledgeOpenResult.Corrupt
                    is EncryptedPersistentKnowledgeOpenResult.Incompatible ->
                        AndroidEncryptedKnowledgeOpenResult.Incompatible(opened.reason)
                    is EncryptedPersistentKnowledgeOpenResult.EncryptionUnavailable ->
                        AndroidEncryptedKnowledgeOpenResult.EncryptionUnavailable(opened.category)
                    is EncryptedPersistentKnowledgeOpenResult.RestorationFailed ->
                        AndroidEncryptedKnowledgeOpenResult.Failed(opened.reason)
                }

            AndroidEncryptedRecordStoreOpenResult.Corrupt ->
                AndroidEncryptedKnowledgeOpenResult.Corrupt
            is AndroidEncryptedRecordStoreOpenResult.Incompatible ->
                AndroidEncryptedKnowledgeOpenResult.Incompatible(encrypted.reason)
            is AndroidEncryptedRecordStoreOpenResult.Failed ->
                AndroidEncryptedKnowledgeOpenResult.Failed(encrypted.reason)
        }

    fun openEncryptedLearningMutations(
        storeId: PersistentStoreId,
        activeDek: pro.liliya.core.encryption.CognitiveDekReference
    ): AndroidEncryptedLearningMutationOpenResult =
        when (val encrypted = openEncryptedRecordStore(storeId)) {
            is AndroidEncryptedRecordStoreOpenResult.Opened ->
                when (
                    val opened = EncryptedPersistentLearningApplicationMutationComposition.open(
                        foundation = foundation,
                        encryptedStore = encrypted.store,
                        dek = activeDek
                    )
                ) {
                    is EncryptedPersistentLearningApplicationMutationOpenResult.Opened ->
                        AndroidEncryptedLearningMutationOpenResult.Opened(opened.composition)
                    EncryptedPersistentLearningApplicationMutationOpenResult.Corrupt ->
                        AndroidEncryptedLearningMutationOpenResult.Corrupt
                    is EncryptedPersistentLearningApplicationMutationOpenResult.Incompatible ->
                        AndroidEncryptedLearningMutationOpenResult.Incompatible(opened.reason)
                    is EncryptedPersistentLearningApplicationMutationOpenResult.EncryptionUnavailable ->
                        AndroidEncryptedLearningMutationOpenResult.EncryptionUnavailable(opened.reason)
                    is EncryptedPersistentLearningApplicationMutationOpenResult.RestorationFailed ->
                        AndroidEncryptedLearningMutationOpenResult.Failed(opened.reason)
                }

            AndroidEncryptedRecordStoreOpenResult.Corrupt ->
                AndroidEncryptedLearningMutationOpenResult.Corrupt
            is AndroidEncryptedRecordStoreOpenResult.Incompatible ->
                AndroidEncryptedLearningMutationOpenResult.Incompatible(encrypted.reason)
            is AndroidEncryptedRecordStoreOpenResult.Failed ->
                AndroidEncryptedLearningMutationOpenResult.Failed(encrypted.reason)
        }

    fun openEncryptedRecordStore(
        storeId: PersistentStoreId
    ): AndroidEncryptedRecordStoreOpenResult {
        if (storeId == PersistentCognitiveDekStore.STORE_ID) {
            return AndroidEncryptedRecordStoreOpenResult.Incompatible(
                "encrypted record store id collides with wrapped DEK registry"
            )
        }
        return when (
            val opened = PersistentRecordStore.open(
                foundation = foundation,
                storeId = storeId,
                backend = backend
            )
        ) {
            is PersistentStoreOpenResult.Opened ->
                AndroidEncryptedRecordStoreOpenResult.Opened(
                    EncryptedPersistentRecordStore(
                        store = opened.store,
                        profile = CognitiveEncryptionProfile.AES_256_GCM,
                        envelopeVersion = CognitiveEnvelopeVersion(1),
                        nonceSource = nonceSource,
                        aead = aead,
                        dekResolver = dekStore
                    )
                )

            PersistentStoreOpenResult.Corrupt -> AndroidEncryptedRecordStoreOpenResult.Corrupt
            is PersistentStoreOpenResult.Incompatible ->
                AndroidEncryptedRecordStoreOpenResult.Incompatible(opened.reason)
            is PersistentStoreOpenResult.Failed ->
                AndroidEncryptedRecordStoreOpenResult.Failed(
                    opened.reason,
                    opened.throwable
                )
        }
    }

    companion object {
        private const val DEFAULT_DIRECTORY = "liliya-cognitive-storage-v1"

        fun open(
            context: Context,
            foundation: FoundationComposition,
            directoryName: String = DEFAULT_DIRECTORY
        ): AndroidCognitiveStorageOpenResult {
            val backend = try {
                AndroidDurablePersistentRecordBackend.create(context, directoryName)
            } catch (t: Throwable) {
                return AndroidCognitiveStorageOpenResult.Failed(
                    "android cognitive durable backend open failed",
                    t
                )
            }
            val protector = AndroidCognitiveKeyProtector(context)
            val materialSource = AndroidSecureRandomCognitiveDekMaterialSource()
            return when (
                val opened = PersistentCognitiveDekStore.open(
                    foundation = foundation,
                    backend = backend,
                    protector = protector,
                    materialSource = materialSource
                )
            ) {
                is PersistentCognitiveDekOpenResult.Opened ->
                    AndroidCognitiveStorageOpenResult.Ready(
                        AndroidCognitiveStorageAssembly(
                            foundation = foundation,
                            backend = backend,
                            keyProtector = protector,
                            dekStore = opened.store,
                            nonceSource = AndroidSecureRandomCognitiveNonceSource(),
                            aead = AndroidAesGcmCognitiveAeadProvider()
                        )
                    )

                PersistentCognitiveDekOpenResult.Corrupt ->
                    AndroidCognitiveStorageOpenResult.Corrupt
                is PersistentCognitiveDekOpenResult.Incompatible ->
                    AndroidCognitiveStorageOpenResult.Incompatible(opened.reason)
                is PersistentCognitiveDekOpenResult.Failed ->
                    AndroidCognitiveStorageOpenResult.Failed(
                        opened.reason,
                        opened.throwable
                    )
            }
        }
    }
}

internal class AndroidSecureRandomCognitiveDekMaterialSource(
    private val random: SecureRandom = SecureRandom()
) : CognitiveDekMaterialSource {
    override fun next(): CognitiveEncryptionResult<CognitiveDekMaterial> {
        val bytes = ByteArray(DEK_BYTES)
        return try {
            random.nextBytes(bytes)
            CognitiveEncryptionResult.Success(CognitiveDekMaterial(bytes))
        } catch (t: Throwable) {
            CognitiveEncryptionResult.Failed(
                CognitiveEncryptionFailureCategory.PROVIDER_FAILED,
                t
            )
        } finally {
            bytes.fill(0)
        }
    }

    private companion object {
        const val DEK_BYTES = 32
    }
}

internal class AndroidSecureRandomCognitiveNonceSource(
    private val random: SecureRandom = SecureRandom()
) : CognitiveNonceSource {
    override fun next(
        profile: CognitiveEncryptionProfile
    ): CognitiveEncryptionResult<CognitiveNonce> {
        val bytes = ByteArray(profile.nonceSizeBytes)
        return try {
            random.nextBytes(bytes)
            CognitiveEncryptionResult.Success(CognitiveNonce(profile, bytes))
        } catch (t: Throwable) {
            CognitiveEncryptionResult.Failed(
                CognitiveEncryptionFailureCategory.PROVIDER_FAILED,
                t
            )
        } finally {
            bytes.fill(0)
        }
    }
}

internal class AndroidAesGcmCognitiveAeadProvider : CognitiveAeadProvider {
    override fun seal(
        profile: CognitiveEncryptionProfile,
        dek: CognitiveDekMaterial,
        nonce: CognitiveNonce,
        associatedData: CognitiveAssociatedData,
        plaintext: CognitivePlaintext
    ): CognitiveEncryptionResult<CognitiveAeadSealedData> {
        val keyBytes = dek.copyBytes()
        val nonceBytes = nonce.copyBytes()
        val aadBytes = associatedData.copyBytes()
        val plaintextBytes = plaintext.copyBytes()
        return try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyBytes, KEY_ALGORITHM),
                GCMParameterSpec(profile.authenticationTagSizeBits, nonceBytes)
            )
            cipher.updateAAD(aadBytes)
            val output = cipher.doFinal(plaintextBytes)
            val tagBytes = profile.authenticationTagSizeBits / 8
            val ciphertextBytes = output.size - tagBytes
            if (ciphertextBytes < 0) {
                return CognitiveEncryptionResult.Failed(
                    CognitiveEncryptionFailureCategory.PROVIDER_FAILED
                )
            }
            CognitiveEncryptionResult.Success(
                CognitiveAeadSealedData(
                    ciphertext = output.copyOfRange(0, ciphertextBytes),
                    authenticationTag = output.copyOfRange(ciphertextBytes, output.size)
                )
            )
        } catch (t: Throwable) {
            CognitiveEncryptionResult.Failed(
                CognitiveEncryptionFailureCategory.PROVIDER_FAILED,
                t
            )
        } finally {
            keyBytes.fill(0)
            nonceBytes.fill(0)
            aadBytes.fill(0)
            plaintextBytes.fill(0)
        }
    }

    override fun open(
        profile: CognitiveEncryptionProfile,
        dek: CognitiveDekMaterial,
        nonce: CognitiveNonce,
        associatedData: CognitiveAssociatedData,
        sealed: CognitiveAeadSealedData
    ): CognitiveEncryptionResult<CognitivePlaintext> {
        val keyBytes = dek.copyBytes()
        val nonceBytes = nonce.copyBytes()
        val aadBytes = associatedData.copyBytes()
        val ciphertext = sealed.copyCiphertext()
        val tag = sealed.copyAuthenticationTag()
        val input = ciphertext + tag
        ciphertext.fill(0)
        tag.fill(0)
        return try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, KEY_ALGORITHM),
                GCMParameterSpec(profile.authenticationTagSizeBits, nonceBytes)
            )
            cipher.updateAAD(aadBytes)
            val plaintext = cipher.doFinal(input)
            try {
                CognitiveEncryptionResult.Success(CognitivePlaintext(plaintext))
            } finally {
                plaintext.fill(0)
            }
        } catch (_: AEADBadTagException) {
            CognitiveEncryptionResult.Rejected(
                CognitiveEncryptionFailureCategory.CIPHERTEXT_AUTHENTICATION_FAILED
            )
        } catch (t: Throwable) {
            CognitiveEncryptionResult.Failed(
                CognitiveEncryptionFailureCategory.PROVIDER_FAILED,
                t
            )
        } finally {
            keyBytes.fill(0)
            nonceBytes.fill(0)
            aadBytes.fill(0)
            input.fill(0)
        }
    }

    private companion object {
        const val CIPHER = "AES/GCM/NoPadding"
        const val KEY_ALGORITHM = "AES"
    }
}
