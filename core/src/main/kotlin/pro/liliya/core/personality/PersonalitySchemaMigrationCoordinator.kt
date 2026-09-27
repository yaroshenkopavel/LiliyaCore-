package pro.liliya.core.personality

import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.encryption.CognitiveEncryptionFailureCategory
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitivePersistentRecordDraft
import pro.liliya.core.encryption.CognitivePlaintext
import pro.liliya.core.encryption.EncryptedPersistentRecordStore
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentRecord

sealed interface PersonalitySchemaMigrationStepResult {
    data object UpToDate : PersonalitySchemaMigrationStepResult
    data class MigratedOne(
        val profileId: PersonalityProfileId,
        val generation: PersonalityGeneration
    ) : PersonalitySchemaMigrationStepResult
    data object Corrupt : PersonalitySchemaMigrationStepResult
    data class Incompatible(val reason: String) : PersonalitySchemaMigrationStepResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) : PersonalitySchemaMigrationStepResult
    data class Rejected(val reason: String) : PersonalitySchemaMigrationStepResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : PersonalitySchemaMigrationStepResult
}

/**
 * Explicit one-step Personality schema migration.
 *
 * This coordinator is intentionally separate from normal reopen. Every step first validates the
 * complete decrypted durable set. Only a fully understood set may mutate, and at most one reviewed
 * legacy v1 record is transitioned to current v2 per call. Exact transition preserves the durable
 * generation and store high-watermark, making interruption/retry restart-safe without replay.
 */
class PersonalitySchemaMigrationCoordinator(
    private val encryptedStore: EncryptedPersistentRecordStore,
    private val activeDek: CognitiveDekReference
) {
    @Synchronized
    fun step(): PersonalitySchemaMigrationStepResult {
        val decrypted = when (val result = encryptedStore.decryptedSnapshotEntries()) {
            is CognitiveEncryptionResult.Success -> result.value
            is CognitiveEncryptionResult.Rejected ->
                return PersonalitySchemaMigrationStepResult.EncryptionUnavailable(result.category)
            is CognitiveEncryptionResult.Failed ->
                return PersonalitySchemaMigrationStepResult.EncryptionUnavailable(result.category)
        }

        data class LegacyCandidate(
            val record: PersistentRecord,
            val generation: PersistentGeneration,
            val profile: PersonalityProfile
        )

        val legacy = ArrayList<LegacyCandidate>()
        for (snapshot in decrypted) {
            val decoded = PersonalityPersistentRecordCodec.decodeForMigration(snapshot.record)
            val profile = when (decoded) {
                is PersonalityPersistentDecodeResult.Decoded -> decoded.profile
                PersonalityPersistentDecodeResult.Corrupt -> return PersonalitySchemaMigrationStepResult.Corrupt
                is PersonalityPersistentDecodeResult.Incompatible ->
                    return PersonalitySchemaMigrationStepResult.Incompatible(decoded.reason)
            }
            when (snapshot.record.schemaVersion) {
                PersonalityPersistentRecordCodec.schemaVersion -> Unit
                PersonalityPersistentRecordCodec.legacySchemaVersion -> legacy += LegacyCandidate(
                    record = snapshot.record,
                    generation = snapshot.generation,
                    profile = profile
                )
                else -> return PersonalitySchemaMigrationStepResult.Incompatible(
                    "persistent personality schema version mismatch"
                )
            }
        }

        val candidate = legacy.firstOrNull() ?: return PersonalitySchemaMigrationStepResult.UpToDate
        val replacement = PersonalityPersistentRecordCodec.encode(candidate.profile)
        val plaintext = replacement.payload.copyBytes()
        val transitioned = try {
            encryptedStore.transitionExact(
                sourceId = candidate.record.id,
                sourceGeneration = candidate.generation,
                replacement = CognitivePersistentRecordDraft(
                    id = replacement.id,
                    schemaId = replacement.schemaId,
                    schemaVersion = replacement.schemaVersion,
                    plaintext = CognitivePlaintext(plaintext),
                    createdAt = replacement.createdAt,
                    dek = activeDek
                )
            )
        } finally {
            plaintext.fill(0)
        }

        return when (transitioned) {
            is CognitiveEncryptionResult.Success -> PersonalitySchemaMigrationStepResult.MigratedOne(
                profileId = candidate.profile.id,
                generation = PersonalityGeneration(transitioned.value.generation.value)
            )
            is CognitiveEncryptionResult.Rejected -> PersonalitySchemaMigrationStepResult.Rejected(
                "personality schema migration exact transition rejected: ${transitioned.category}"
            )
            is CognitiveEncryptionResult.Failed -> PersonalitySchemaMigrationStepResult.Failed(
                "personality schema migration exact transition failed",
                transitioned.throwable
            )
        }
    }
}
