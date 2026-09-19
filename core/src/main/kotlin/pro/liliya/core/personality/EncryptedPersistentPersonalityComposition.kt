package pro.liliya.core.personality

import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.encryption.CognitiveEncryptionFailureCategory
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitivePersistentRecordDraft
import pro.liliya.core.encryption.CognitivePlaintext
import pro.liliya.core.encryption.EncryptedPersistentRecordStore
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.persistence.PersistentMutationResult
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentRecordOwnership

sealed interface EncryptedPersistentPersonalityOpenResult {
    data class Opened(val composition: EncryptedPersistentPersonalityComposition) : EncryptedPersistentPersonalityOpenResult
    data object Corrupt : EncryptedPersistentPersonalityOpenResult
    data class Incompatible(val reason: String) : EncryptedPersistentPersonalityOpenResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) : EncryptedPersistentPersonalityOpenResult
    data class RestorationFailed(val reason: String) : EncryptedPersistentPersonalityOpenResult
}

interface PersistentPersonalityOwnership {
    val profile: PersonalityProfile
    val generation: PersonalityGeneration
    fun remove(): PersistentPersonalityMutationResult
}

sealed interface PersistentPersonalityInstallResult {
    data class Installed(val ownership: PersistentPersonalityOwnership) : PersistentPersonalityInstallResult
    data class Rejected(val reason: String) : PersistentPersonalityInstallResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : PersistentPersonalityInstallResult
}

sealed interface PersistentPersonalityMutationResult {
    data object Committed : PersistentPersonalityMutationResult
    data class Rejected(val reason: String) : PersistentPersonalityMutationResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : PersistentPersonalityMutationResult
}

/**
 * Durable Personality owner over authenticated encrypted persistence.
 *
 * Restored profile data is descriptive context only. Reopen never restores or manufactures
 * Authority, Capability, execution permission, learning enablement, or mutable model output.
 */
class EncryptedPersistentPersonalityComposition private constructor(
    private val foundation: FoundationComposition,
    private val encryptedStore: EncryptedPersistentRecordStore,
    private val store: PersonalityProfileStore,
    private val activeDek: CognitiveDekReference
) {
    @Synchronized
    fun install(profile: PersonalityProfile): PersistentPersonalityInstallResult {
        val encoded = PersonalityPersistentRecordCodec.encode(profile)
        val plaintext = encoded.payload.copyBytes()
        val durable = try {
            encryptedStore.install(
                CognitivePersistentRecordDraft(
                    id = encoded.id,
                    schemaId = encoded.schemaId,
                    schemaVersion = encoded.schemaVersion,
                    plaintext = CognitivePlaintext(plaintext),
                    createdAt = encoded.createdAt,
                    dek = activeDek
                )
            )
        } finally {
            plaintext.fill(0)
        }
        return when (durable) {
            is CognitiveEncryptionResult.Success -> installCommitted(profile, durable.value)
            is CognitiveEncryptionResult.Rejected -> PersistentPersonalityInstallResult.Rejected(
                "encrypted personality persistence rejected: ${durable.category}"
            )
            is CognitiveEncryptionResult.Failed -> PersistentPersonalityInstallResult.Failed(
                "encrypted personality durable install failed",
                durable.throwable
            )
        }
    }

    fun find(id: PersonalityProfileId): PersonalityProfile? = store.find(id)
    fun inspect(id: PersonalityProfileId): PersonalityProfileSnapshot? = store.inspect(id)
    fun contains(id: PersonalityProfileId): Boolean = store.contains(id)
    fun snapshot(): List<PersonalityProfile> = store.snapshot()
    fun snapshotEntries(): List<PersonalityProfileSnapshot> = store.snapshotEntries()

    private fun installCommitted(
        profile: PersonalityProfile,
        persistentOwnership: PersistentRecordOwnership
    ): PersistentPersonalityInstallResult {
        val generation = PersonalityGeneration(persistentOwnership.generation.value)
        val context = foundation.rootContext(
            operation = "installEncryptedPersistedPersonality",
            component = "Personality",
            metadata = mapOf(
                "personalityGeneration" to generation.value.toString(),
                "personalityAttributeCount" to profile.attributes.size.toString()
            )
        )
        return when (
            val local = store.installCommitted(
                profile = profile,
                generation = generation,
                highWatermark = encryptedStore.generationHighWatermark(),
                context = context
            )
        ) {
            is PersonalityProfileRegistrationResult.Registered -> PersistentPersonalityInstallResult.Installed(
                ownership(persistentOwnership, local.registration, context)
            )
            is PersonalityProfileRegistrationResult.Rejected -> {
                val compensated = persistentOwnership.remove()
                val reason = if (compensated is PersistentMutationResult.Committed) {
                    "local personality install rejected after durable commit; durable candidate compensated"
                } else {
                    "local personality install rejected after durable commit; durable compensation failed"
                }
                PersistentPersonalityInstallResult.Failed(reason)
            }
        }
    }

    private fun ownership(
        persistentOwnership: PersistentRecordOwnership,
        localRegistration: PersonalityProfileRegistration,
        operationContext: pro.liliya.core.logging.LogContext
    ): PersistentPersonalityOwnership = object : PersistentPersonalityOwnership {
        override val profile: PersonalityProfile = localRegistration.profile
        override val generation: PersonalityGeneration = localRegistration.generation

        override fun remove(): PersistentPersonalityMutationResult = synchronized(this@EncryptedPersistentPersonalityComposition) {
            when (val durable = persistentOwnership.remove()) {
                PersistentMutationResult.Committed -> {
                    val local = localRegistration.remove(
                        foundation.childContext(
                            parent = operationContext,
                            component = "Personality",
                            operation = "removeEncryptedPersistedPersonality",
                            metadata = mapOf("personalityGeneration" to generation.value.toString())
                        )
                    )
                    if (local) PersistentPersonalityMutationResult.Committed
                    else PersistentPersonalityMutationResult.Failed("durable personality removal committed but local exact removal failed")
                }
                is PersistentMutationResult.Rejected -> PersistentPersonalityMutationResult.Rejected(durable.reason)
                is PersistentMutationResult.Failed -> PersistentPersonalityMutationResult.Failed(
                    "encrypted personality durable removal failed",
                    durable.throwable
                )
            }
        }
    }

    companion object {
        fun open(
            foundation: FoundationComposition,
            encryptedStore: EncryptedPersistentRecordStore,
            activeDek: CognitiveDekReference
        ): EncryptedPersistentPersonalityOpenResult {
            val restoredEntries = mutableListOf<PersonalityProfileSnapshot>()
            val decrypted = when (val result = encryptedStore.decryptedSnapshotEntries()) {
                is CognitiveEncryptionResult.Success -> result.value
                is CognitiveEncryptionResult.Rejected -> return EncryptedPersistentPersonalityOpenResult.EncryptionUnavailable(result.category)
                is CognitiveEncryptionResult.Failed -> return EncryptedPersistentPersonalityOpenResult.EncryptionUnavailable(result.category)
            }
            for (snapshot in decrypted) {
                val decoded = PersonalityPersistentRecordCodec.decode(
                    PersistentRecord(
                        id = snapshot.record.id,
                        schemaId = snapshot.record.schemaId,
                        schemaVersion = snapshot.record.schemaVersion,
                        payload = snapshot.record.payload,
                        createdAt = snapshot.record.createdAt
                    )
                )
                when (decoded) {
                    is PersonalityPersistentDecodeResult.Decoded -> restoredEntries += PersonalityProfileSnapshot(
                        decoded.profile,
                        PersonalityGeneration(snapshot.generation.value)
                    )
                    PersonalityPersistentDecodeResult.Corrupt -> return EncryptedPersistentPersonalityOpenResult.Corrupt
                    is PersonalityPersistentDecodeResult.Incompatible -> return EncryptedPersistentPersonalityOpenResult.Incompatible(decoded.reason)
                }
            }
            return when (
                val restored = PersonalityProfileStore.restore(
                    observability = foundation.observability,
                    entries = restoredEntries,
                    highWatermark = encryptedStore.generationHighWatermark()
                )
            ) {
                is PersonalityProfileRestorationResult.Restored -> EncryptedPersistentPersonalityOpenResult.Opened(
                    EncryptedPersistentPersonalityComposition(foundation, encryptedStore, restored.store, activeDek)
                )
                is PersonalityProfileRestorationResult.Rejected -> EncryptedPersistentPersonalityOpenResult.RestorationFailed(restored.reason)
            }
        }
    }
}
