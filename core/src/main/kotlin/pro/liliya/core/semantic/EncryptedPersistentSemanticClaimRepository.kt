package pro.liliya.core.semantic

import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.encryption.CognitiveEncryptionFailureCategory
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitivePersistentRecordDraft
import pro.liliya.core.encryption.CognitivePlaintext
import pro.liliya.core.encryption.EncryptedPersistentRecordStore
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentRecordLookupResult

sealed interface SemanticClaimStoreResult {
    data class Stored(val record: SemanticClaimRecord) : SemanticClaimStoreResult
    data class AlreadyPresent(val record: SemanticClaimRecord) : SemanticClaimStoreResult
    data class Rejected(val reason: String) : SemanticClaimStoreResult
    data class EncryptionUnavailable(
        val category: CognitiveEncryptionFailureCategory
    ) : SemanticClaimStoreResult
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : SemanticClaimStoreResult
}

sealed interface SemanticRelationStoreResult {
    data class Stored(val relation: SemanticClaimRelation) : SemanticRelationStoreResult
    data class AlreadyPresent(val relation: SemanticClaimRelation) : SemanticRelationStoreResult
    data class Rejected(val reason: String) : SemanticRelationStoreResult
    data class EncryptionUnavailable(
        val category: CognitiveEncryptionFailureCategory
    ) : SemanticRelationStoreResult
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : SemanticRelationStoreResult
}

class EncryptedPersistentSemanticClaimRepository(
    private val encryptedStore: EncryptedPersistentRecordStore,
    private val activeDek: CognitiveDekReference
) {
    fun storeClaim(record: SemanticClaimRecord): SemanticClaimStoreResult {
        val encoded = try {
            SemanticClaimPersistentCodec.encode(record)
        } catch (error: IllegalArgumentException) {
            return SemanticClaimStoreResult.Rejected(
                error.message ?: "invalid semantic claim"
            )
        }

        when (
            val existing = loadExactClaim(
                SemanticClaimVersionReference(record.id, record.version)
            )
        ) {
            ClaimLookupResult.Missing -> Unit
            is ClaimLookupResult.Found -> {
                return if (existing.record == record) {
                    SemanticClaimStoreResult.AlreadyPresent(record)
                } else {
                    SemanticClaimStoreResult.Rejected(
                        "semantic claim version already exists with different content"
                    )
                }
            }
            is ClaimLookupResult.Rejected ->
                return SemanticClaimStoreResult.EncryptionUnavailable(existing.category)
            is ClaimLookupResult.Failed ->
                return SemanticClaimStoreResult.Failed(
                    existing.reason,
                    existing.throwable
                )
        }

        if (record.version.value > 1L) {
            val previous = SemanticClaimVersionReference(
                claimId = record.id,
                version = SemanticClaimVersion(record.version.value - 1L)
            )
            when (val loaded = loadExactClaim(previous)) {
                is ClaimLookupResult.Found -> Unit
                ClaimLookupResult.Missing ->
                    return SemanticClaimStoreResult.Rejected(
                        "previous semantic claim version is missing"
                    )
                is ClaimLookupResult.Rejected ->
                    return SemanticClaimStoreResult.EncryptionUnavailable(loaded.category)
                is ClaimLookupResult.Failed ->
                    return SemanticClaimStoreResult.Failed(
                        loaded.reason,
                        loaded.throwable
                    )
            }
        }

        if (record.version.value < Long.MAX_VALUE) {
            val successor = SemanticClaimVersionReference(
                claimId = record.id,
                version = SemanticClaimVersion(record.version.value + 1L)
            )
            when (val loaded = loadExactClaim(successor)) {
                ClaimLookupResult.Missing -> Unit
                is ClaimLookupResult.Found ->
                    return SemanticClaimStoreResult.Rejected(
                        "later semantic claim version already exists"
                    )
                is ClaimLookupResult.Rejected ->
                    return SemanticClaimStoreResult.EncryptionUnavailable(loaded.category)
                is ClaimLookupResult.Failed ->
                    return SemanticClaimStoreResult.Failed(
                        loaded.reason,
                        loaded.throwable
                    )
            }
        }

        val bytes = encoded.payload.copyBytes()
        val installed = try {
            encryptedStore.install(
                CognitivePersistentRecordDraft(
                    id = encoded.id,
                    schemaId = encoded.schemaId,
                    schemaVersion = encoded.schemaVersion,
                    plaintext = CognitivePlaintext(bytes),
                    createdAt = encoded.createdAt,
                    dek = activeDek
                )
            )
        } finally {
            bytes.fill(0)
        }

        return when (installed) {
            is CognitiveEncryptionResult.Success ->
                SemanticClaimStoreResult.Stored(record)

            is CognitiveEncryptionResult.Rejected ->
                if (
                    installed.category ==
                    CognitiveEncryptionFailureCategory.PERSISTENCE_CONFLICT
                ) {
                    classifyClaimInstallConflict(record)
                } else {
                    SemanticClaimStoreResult.EncryptionUnavailable(
                        installed.category
                    )
                }

            is CognitiveEncryptionResult.Failed ->
                SemanticClaimStoreResult.Failed(
                    "semantic claim persistence failed",
                    installed.throwable
                )
        }
    }

    fun storeRelation(
        relation: SemanticClaimRelation
    ): SemanticRelationStoreResult {
        val canonicalRelation =
            SemanticClaimRelationPersistentCodec.canonical(relation)

        val source = when (
            val loaded = loadExactClaim(canonicalRelation.source)
        ) {
            is ClaimLookupResult.Found -> loaded.record
            ClaimLookupResult.Missing ->
                return SemanticRelationStoreResult.Rejected(
                    "relation source version is missing"
                )
            is ClaimLookupResult.Rejected ->
                return SemanticRelationStoreResult.EncryptionUnavailable(
                    loaded.category
                )
            is ClaimLookupResult.Failed ->
                return SemanticRelationStoreResult.Failed(
                    loaded.reason,
                    loaded.throwable
                )
        }

        val target = when (
            val loaded = loadExactClaim(canonicalRelation.target)
        ) {
            is ClaimLookupResult.Found -> loaded.record
            ClaimLookupResult.Missing ->
                return SemanticRelationStoreResult.Rejected(
                    "relation target version is missing"
                )
            is ClaimLookupResult.Rejected ->
                return SemanticRelationStoreResult.EncryptionUnavailable(
                    loaded.category
                )
            is ClaimLookupResult.Failed ->
                return SemanticRelationStoreResult.Failed(
                    loaded.reason,
                    loaded.throwable
                )
        }

        val sourceGroup = SemanticClaimIds.forConflictGroup(source.identity)
        val targetGroup = SemanticClaimIds.forConflictGroup(target.identity)
        if (sourceGroup != targetGroup) {
            return SemanticRelationStoreResult.Rejected(
                "semantic claim relation must remain inside one conflict group"
            )
        }
        if (
            canonicalRelation.type == SemanticClaimRelationType.SUPERSEDES &&
            source.id == target.id &&
            source.version.value <= target.version.value
        ) {
            return SemanticRelationStoreResult.Rejected(
                "same-claim supersession must point from newer version to older version"
            )
        }

        val encoded =
            SemanticClaimRelationPersistentCodec.encode(canonicalRelation)
        when (val existing = loadExactRelation(encoded.id)) {
            RelationLookupResult.Missing -> Unit
            is RelationLookupResult.Found -> {
                return if (existing.relation == canonicalRelation) {
                    SemanticRelationStoreResult.AlreadyPresent(
                        canonicalRelation
                    )
                } else {
                    SemanticRelationStoreResult.Rejected(
                        "semantic relation id already exists with different content"
                    )
                }
            }
            is RelationLookupResult.Rejected ->
                return SemanticRelationStoreResult.EncryptionUnavailable(
                    existing.category
                )
            is RelationLookupResult.Failed ->
                return SemanticRelationStoreResult.Failed(
                    existing.reason,
                    existing.throwable
                )
        }

        val bytes = encoded.payload.copyBytes()
        val installed = try {
            encryptedStore.install(
                CognitivePersistentRecordDraft(
                    id = encoded.id,
                    schemaId = encoded.schemaId,
                    schemaVersion = encoded.schemaVersion,
                    plaintext = CognitivePlaintext(bytes),
                    createdAt = encoded.createdAt,
                    dek = activeDek
                )
            )
        } finally {
            bytes.fill(0)
        }

        return when (installed) {
            is CognitiveEncryptionResult.Success ->
                SemanticRelationStoreResult.Stored(canonicalRelation)

            is CognitiveEncryptionResult.Rejected ->
                if (
                    installed.category ==
                    CognitiveEncryptionFailureCategory.PERSISTENCE_CONFLICT
                ) {
                    classifyRelationInstallConflict(
                        canonicalRelation,
                        encoded.id
                    )
                } else {
                    SemanticRelationStoreResult.EncryptionUnavailable(
                        installed.category
                    )
                }

            is CognitiveEncryptionResult.Failed ->
                SemanticRelationStoreResult.Failed(
                    "semantic relation persistence failed",
                    installed.throwable
                )
        }
    }

    private sealed interface ClaimLookupResult {
        data object Missing : ClaimLookupResult
        data class Found(
            val record: SemanticClaimRecord
        ) : ClaimLookupResult
        data class Rejected(
            val category: CognitiveEncryptionFailureCategory
        ) : ClaimLookupResult
        data class Failed(
            val reason: String,
            val throwable: Throwable? = null
        ) : ClaimLookupResult
    }

    private fun loadExactClaim(
        reference: SemanticClaimVersionReference
    ): ClaimLookupResult {
        val id = SemanticClaimPersistentCodec.persistentId(
            reference.claimId,
            reference.version
        )
        val snapshot = when (
            val inspected = encryptedStore.inspectResult(id)
        ) {
            PersistentRecordLookupResult.Missing ->
                return ClaimLookupResult.Missing
            is PersistentRecordLookupResult.Found ->
                inspected.snapshot
            PersistentRecordLookupResult.Corrupt ->
                return ClaimLookupResult.Failed(
                    "semantic claim persistent entry is corrupt"
                )
            is PersistentRecordLookupResult.Incompatible ->
                return ClaimLookupResult.Failed(inspected.reason)
            is PersistentRecordLookupResult.Failed ->
                return ClaimLookupResult.Failed(
                    inspected.reason,
                    inspected.throwable
                )
        }

        if (
            snapshot.record.schemaId !=
            SemanticClaimPersistentCodec.schemaId
        ) {
            return ClaimLookupResult.Failed(
                "semantic claim schema id mismatch"
            )
        }

        val plaintext = when (val opened = encryptedStore.open(id)) {
            is CognitiveEncryptionResult.Success -> opened.value
            is CognitiveEncryptionResult.Rejected ->
                return ClaimLookupResult.Rejected(opened.category)
            is CognitiveEncryptionResult.Failed ->
                return ClaimLookupResult.Failed(
                    "semantic claim decryption failed",
                    opened.throwable
                )
        }

        val bytes = plaintext.copyBytes()
        val decoded = try {
            SemanticClaimPersistentCodec.decode(
                PersistentRecord(
                    id = snapshot.record.id,
                    schemaId = snapshot.record.schemaId,
                    schemaVersion = snapshot.record.schemaVersion,
                    payload = PersistentPayload(bytes),
                    createdAt = snapshot.record.createdAt
                )
            )
        } finally {
            bytes.fill(0)
        }

        return when (decoded) {
            is SemanticClaimPersistentDecodeResult.Decoded ->
                if (
                    decoded.record.id == reference.claimId &&
                    decoded.record.version == reference.version
                ) {
                    ClaimLookupResult.Found(decoded.record)
                } else {
                    ClaimLookupResult.Failed(
                        "semantic claim exact lookup identity mismatch"
                    )
                }

            SemanticClaimPersistentDecodeResult.Corrupt ->
                ClaimLookupResult.Failed(
                    "semantic claim record is corrupt"
                )

            is SemanticClaimPersistentDecodeResult.Incompatible ->
                ClaimLookupResult.Failed(decoded.reason)
        }
    }

    private fun classifyClaimInstallConflict(
        record: SemanticClaimRecord
    ): SemanticClaimStoreResult =
        when (
            val loaded = loadExactClaim(
                SemanticClaimVersionReference(
                    record.id,
                    record.version
                )
            )
        ) {
            is ClaimLookupResult.Found ->
                if (loaded.record == record) {
                    SemanticClaimStoreResult.AlreadyPresent(record)
                } else {
                    SemanticClaimStoreResult.Rejected(
                        "semantic claim version already exists with different content"
                    )
                }

            ClaimLookupResult.Missing ->
                SemanticClaimStoreResult.Rejected(
                    "semantic claim persistence conflict"
                )

            is ClaimLookupResult.Rejected ->
                SemanticClaimStoreResult.EncryptionUnavailable(
                    loaded.category
                )

            is ClaimLookupResult.Failed ->
                SemanticClaimStoreResult.Failed(
                    loaded.reason,
                    loaded.throwable
                )
        }

    private sealed interface RelationLookupResult {
        data object Missing : RelationLookupResult
        data class Found(
            val relation: SemanticClaimRelation
        ) : RelationLookupResult
        data class Rejected(
            val category: CognitiveEncryptionFailureCategory
        ) : RelationLookupResult
        data class Failed(
            val reason: String,
            val throwable: Throwable? = null
        ) : RelationLookupResult
    }

    private fun loadExactRelation(
        id: PersistentEntityId
    ): RelationLookupResult {
        val snapshot = when (
            val inspected = encryptedStore.inspectResult(id)
        ) {
            PersistentRecordLookupResult.Missing ->
                return RelationLookupResult.Missing
            is PersistentRecordLookupResult.Found ->
                inspected.snapshot
            PersistentRecordLookupResult.Corrupt ->
                return RelationLookupResult.Failed(
                    "semantic relation persistent entry is corrupt"
                )
            is PersistentRecordLookupResult.Incompatible ->
                return RelationLookupResult.Failed(inspected.reason)
            is PersistentRecordLookupResult.Failed ->
                return RelationLookupResult.Failed(
                    inspected.reason,
                    inspected.throwable
                )
        }

        if (
            snapshot.record.schemaId !=
            SemanticClaimRelationPersistentCodec.schemaId
        ) {
            return RelationLookupResult.Failed(
                "semantic relation schema id mismatch"
            )
        }

        val plaintext = when (val opened = encryptedStore.open(id)) {
            is CognitiveEncryptionResult.Success -> opened.value
            is CognitiveEncryptionResult.Rejected ->
                return RelationLookupResult.Rejected(opened.category)
            is CognitiveEncryptionResult.Failed ->
                return RelationLookupResult.Failed(
                    "semantic relation decryption failed",
                    opened.throwable
                )
        }

        val bytes = plaintext.copyBytes()
        val decoded = try {
            SemanticClaimRelationPersistentCodec.decode(
                PersistentRecord(
                    id = snapshot.record.id,
                    schemaId = snapshot.record.schemaId,
                    schemaVersion = snapshot.record.schemaVersion,
                    payload = PersistentPayload(bytes),
                    createdAt = snapshot.record.createdAt
                )
            )
        } finally {
            bytes.fill(0)
        }

        return when (decoded) {
            is SemanticClaimRelationDecodeResult.Decoded ->
                RelationLookupResult.Found(decoded.relation)
            SemanticClaimRelationDecodeResult.Corrupt ->
                RelationLookupResult.Failed(
                    "semantic relation record is corrupt"
                )
            is SemanticClaimRelationDecodeResult.Incompatible ->
                RelationLookupResult.Failed(decoded.reason)
        }
    }

    private fun classifyRelationInstallConflict(
        relation: SemanticClaimRelation,
        id: PersistentEntityId
    ): SemanticRelationStoreResult =
        when (val loaded = loadExactRelation(id)) {
            is RelationLookupResult.Found ->
                if (loaded.relation == relation) {
                    SemanticRelationStoreResult.AlreadyPresent(
                        relation
                    )
                } else {
                    SemanticRelationStoreResult.Rejected(
                        "semantic relation id already exists with different content"
                    )
                }

            RelationLookupResult.Missing ->
                SemanticRelationStoreResult.Rejected(
                    "semantic relation persistence conflict"
                )

            is RelationLookupResult.Rejected ->
                SemanticRelationStoreResult.EncryptionUnavailable(
                    loaded.category
                )

            is RelationLookupResult.Failed ->
                SemanticRelationStoreResult.Failed(
                    loaded.reason,
                    loaded.throwable
                )
        }
}
