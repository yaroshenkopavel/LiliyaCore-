package pro.liliya.core.semantic

import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.encryption.CognitiveEncryptionFailureCategory
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitivePersistentRecordDraft
import pro.liliya.core.encryption.CognitivePlaintext
import pro.liliya.core.encryption.EncryptedPersistentRecordPageResult
import pro.liliya.core.encryption.EncryptedPersistentRecordStore
import pro.liliya.core.persistence.PersistentBackendPageCursor
import pro.liliya.core.persistence.PersistentBackendPageOrder
import pro.liliya.core.persistence.PersistentBackendPageRequest
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord

sealed interface SemanticClaimStoreResult {
    data class Stored(val record: SemanticClaimRecord) : SemanticClaimStoreResult
    data class AlreadyPresent(val record: SemanticClaimRecord) : SemanticClaimStoreResult
    data class Rejected(val reason: String) : SemanticClaimStoreResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) : SemanticClaimStoreResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : SemanticClaimStoreResult
}

sealed interface SemanticRelationStoreResult {
    data class Stored(val relation: SemanticClaimRelation) : SemanticRelationStoreResult
    data class AlreadyPresent(val relation: SemanticClaimRelation) : SemanticRelationStoreResult
    data class Rejected(val reason: String) : SemanticRelationStoreResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) : SemanticRelationStoreResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : SemanticRelationStoreResult
}

class EncryptedPersistentSemanticClaimRepository(
    private val encryptedStore: EncryptedPersistentRecordStore,
    private val activeDek: CognitiveDekReference
) {
    fun storeClaim(record: SemanticClaimRecord): SemanticClaimStoreResult {
        val encoded = try {
            SemanticClaimPersistentCodec.encode(record)
        } catch (error: IllegalArgumentException) {
            return SemanticClaimStoreResult.Rejected(error.message ?: "invalid semantic claim")
        }

        val history = when (val loaded = loadAllClaims()) {
            is ClaimHistoryResult.Loaded -> loaded.records.filter { it.id == record.id }
            is ClaimHistoryResult.Rejected ->
                return SemanticClaimStoreResult.EncryptionUnavailable(loaded.category)
            is ClaimHistoryResult.Failed ->
                return SemanticClaimStoreResult.Failed(loaded.reason, loaded.throwable)
        }

        val exactVersion = history.filter { it.version == record.version }
        if (exactVersion.isNotEmpty()) {
            return if (exactVersion.size == 1 && exactVersion.single() == record) {
                SemanticClaimStoreResult.AlreadyPresent(record)
            } else {
                SemanticClaimStoreResult.Rejected("semantic claim version already exists with different content")
            }
        }

        val maxVersion = history.maxOfOrNull { it.version.value }
        val expected = (maxVersion ?: 0L) + 1L
        if (record.version.value != expected) {
            return SemanticClaimStoreResult.Rejected(
                "semantic claim version must be exactly next monotonic version: expected $expected"
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
            is CognitiveEncryptionResult.Success -> SemanticClaimStoreResult.Stored(record)
            is CognitiveEncryptionResult.Rejected ->
                SemanticClaimStoreResult.EncryptionUnavailable(installed.category)
            is CognitiveEncryptionResult.Failed ->
                SemanticClaimStoreResult.Failed("semantic claim persistence failed", installed.throwable)
        }
    }

    fun storeRelation(relation: SemanticClaimRelation): SemanticRelationStoreResult {
        val claims = when (val loaded = loadAllClaims()) {
            is ClaimHistoryResult.Loaded -> loaded.records
            is ClaimHistoryResult.Rejected ->
                return SemanticRelationStoreResult.EncryptionUnavailable(loaded.category)
            is ClaimHistoryResult.Failed ->
                return SemanticRelationStoreResult.Failed(loaded.reason, loaded.throwable)
        }

        val source = claims.singleOrNull {
            it.id == relation.source.claimId && it.version == relation.source.version
        } ?: return SemanticRelationStoreResult.Rejected("relation source version is missing")

        val target = claims.singleOrNull {
            it.id == relation.target.claimId && it.version == relation.target.version
        } ?: return SemanticRelationStoreResult.Rejected("relation target version is missing")

        val sourceGroup = SemanticClaimIds.forConflictGroup(source.identity)
        val targetGroup = SemanticClaimIds.forConflictGroup(target.identity)
        if (sourceGroup != targetGroup) {
            return SemanticRelationStoreResult.Rejected(
                "semantic claim relation must remain inside one conflict group"
            )
        }
        if (relation.type == SemanticClaimRelationType.SUPERSEDES &&
            source.id == target.id &&
            source.version.value <= target.version.value
        ) {
            return SemanticRelationStoreResult.Rejected(
                "same-claim supersession must point from newer version to older version"
            )
        }

        val encoded = SemanticClaimRelationPersistentCodec.encode(relation)
        when (val existing = loadExactRelation(encoded.id)) {
            RelationLookupResult.Missing -> Unit
            is RelationLookupResult.Found -> {
                return if (existing.relation == relation) {
                    SemanticRelationStoreResult.AlreadyPresent(relation)
                } else {
                    SemanticRelationStoreResult.Rejected(
                        "semantic relation id already exists with different content"
                    )
                }
            }
            is RelationLookupResult.Rejected ->
                return SemanticRelationStoreResult.EncryptionUnavailable(existing.category)
            is RelationLookupResult.Failed ->
                return SemanticRelationStoreResult.Failed(existing.reason, existing.throwable)
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
            is CognitiveEncryptionResult.Success -> SemanticRelationStoreResult.Stored(relation)
            is CognitiveEncryptionResult.Rejected ->
                SemanticRelationStoreResult.EncryptionUnavailable(installed.category)
            is CognitiveEncryptionResult.Failed ->
                SemanticRelationStoreResult.Failed("semantic relation persistence failed", installed.throwable)
        }
    }

    private sealed interface ClaimHistoryResult {
        data class Loaded(val records: List<SemanticClaimRecord>) : ClaimHistoryResult
        data class Rejected(val category: CognitiveEncryptionFailureCategory) : ClaimHistoryResult
        data class Failed(val reason: String, val throwable: Throwable? = null) : ClaimHistoryResult
    }

    private fun loadAllClaims(): ClaimHistoryResult {
        val records = ArrayList<SemanticClaimRecord>()
        var cursor: PersistentBackendPageCursor? = null
        while (true) {
            when (
                val page = encryptedStore.decryptedPageResult(
                    PersistentBackendPageRequest(
                        limit = PersistentBackendPageRequest.MAX_PAGE_SIZE,
                        order = PersistentBackendPageOrder.OLDEST_FIRST,
                        cursorExclusive = cursor,
                        schemaId = SemanticClaimPersistentCodec.schemaId
                    )
                )
            ) {
                EncryptedPersistentRecordPageResult.Empty ->
                    return ClaimHistoryResult.Loaded(records)

                EncryptedPersistentRecordPageResult.Corrupt ->
                    return ClaimHistoryResult.Failed("semantic claim page is corrupt")

                is EncryptedPersistentRecordPageResult.Incompatible ->
                    return ClaimHistoryResult.Failed(page.reason)

                is EncryptedPersistentRecordPageResult.EncryptionUnavailable ->
                    return ClaimHistoryResult.Rejected(page.category)

                is EncryptedPersistentRecordPageResult.Failed ->
                    return ClaimHistoryResult.Failed(page.reason, page.throwable)

                is EncryptedPersistentRecordPageResult.Loaded -> {
                    for (snapshot in page.entries) {
                        when (val decoded = SemanticClaimPersistentCodec.decode(snapshot.record)) {
                            is SemanticClaimPersistentDecodeResult.Decoded -> records += decoded.record
                            SemanticClaimPersistentDecodeResult.Corrupt ->
                                return ClaimHistoryResult.Failed("semantic claim record is corrupt")
                            is SemanticClaimPersistentDecodeResult.Incompatible ->
                                return ClaimHistoryResult.Failed(decoded.reason)
                        }
                    }
                    val next = page.nextCursor ?: return ClaimHistoryResult.Loaded(records)
                    if (page.entries.isEmpty() || next == cursor) {
                        return ClaimHistoryResult.Failed("semantic claim page cursor is non-progressing")
                    }
                    cursor = next
                }
            }
        }
    }

    private sealed interface RelationLookupResult {
        data object Missing : RelationLookupResult
        data class Found(val relation: SemanticClaimRelation) : RelationLookupResult
        data class Rejected(val category: CognitiveEncryptionFailureCategory) : RelationLookupResult
        data class Failed(val reason: String, val throwable: Throwable? = null) : RelationLookupResult
    }

    private fun loadExactRelation(id: PersistentEntityId): RelationLookupResult {
        val snapshot = encryptedStore.inspect(id) ?: return RelationLookupResult.Missing
        if (snapshot.record.schemaId != SemanticClaimRelationPersistentCodec.schemaId) {
            return RelationLookupResult.Failed("semantic relation schema id mismatch")
        }
        val plaintext = when (val opened = encryptedStore.open(id)) {
            is CognitiveEncryptionResult.Success -> opened.value
            is CognitiveEncryptionResult.Rejected -> return RelationLookupResult.Rejected(opened.category)
            is CognitiveEncryptionResult.Failed ->
                return RelationLookupResult.Rejected(opened.category)
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
                RelationLookupResult.Failed("semantic relation record is corrupt")
            is SemanticClaimRelationDecodeResult.Incompatible ->
                RelationLookupResult.Failed(decoded.reason)
        }
    }
}
