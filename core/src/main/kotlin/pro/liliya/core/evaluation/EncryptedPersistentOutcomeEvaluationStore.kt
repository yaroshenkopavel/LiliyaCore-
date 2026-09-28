package pro.liliya.core.evaluation

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
import pro.liliya.core.persistence.PersistentRecordLookupResult

sealed interface OutcomeEvaluationMemoryOpenResult {
    data class Opened(val store: EncryptedPersistentOutcomeEvaluationStore) :
        OutcomeEvaluationMemoryOpenResult
    data class Incompatible(val reason: String) : OutcomeEvaluationMemoryOpenResult
}

sealed interface OutcomeEvaluationStoreResult {
    data class Stored(val snapshot: OutcomeEvaluationSnapshot) : OutcomeEvaluationStoreResult
    data class Rejected(val category: CognitiveEncryptionFailureCategory) : OutcomeEvaluationStoreResult
    data class Failed(
        val category: CognitiveEncryptionFailureCategory,
        val throwable: Throwable? = null
    ) : OutcomeEvaluationStoreResult
}
sealed interface OutcomeEvaluationLookupResult {
    data object Missing : OutcomeEvaluationLookupResult
    data class Found(val snapshot: OutcomeEvaluationSnapshot) : OutcomeEvaluationLookupResult
    data object Corrupt : OutcomeEvaluationLookupResult
    data class Incompatible(val reason: String) : OutcomeEvaluationLookupResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) :
        OutcomeEvaluationLookupResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        OutcomeEvaluationLookupResult
}

sealed interface OutcomeEvaluationPageResult {
    data object Empty : OutcomeEvaluationPageResult
    data class Loaded(
        val entries: List<OutcomeEvaluationSnapshot>,
        val nextCursor: PersistentBackendPageCursor?
    ) : OutcomeEvaluationPageResult
    data object Corrupt : OutcomeEvaluationPageResult
    data class Incompatible(val reason: String) : OutcomeEvaluationPageResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) :
        OutcomeEvaluationPageResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        OutcomeEvaluationPageResult
}

interface OutcomeEvaluationRepository {
    fun store(record: OutcomeEvaluationRecord): OutcomeEvaluationStoreResult
    fun lookup(id: OutcomeEvaluationId): OutcomeEvaluationLookupResult
    fun page(
        limit: Int,
        order: PersistentBackendPageOrder = PersistentBackendPageOrder.NEWEST_FIRST,
        cursorExclusive: PersistentBackendPageCursor? = null
    ): OutcomeEvaluationPageResult
}
class EncryptedPersistentOutcomeEvaluationStore private constructor(
    private val encryptedStore: EncryptedPersistentRecordStore,
    private val activeDek: CognitiveDekReference
) : OutcomeEvaluationRepository {
    override fun store(record: OutcomeEvaluationRecord): OutcomeEvaluationStoreResult {
        val encoded = OutcomeEvaluationPersistentCodec.encode(record)
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
            is CognitiveEncryptionResult.Success -> OutcomeEvaluationStoreResult.Stored(
                OutcomeEvaluationSnapshot(record, installed.value.generation.value)
            )
            is CognitiveEncryptionResult.Rejected ->
                OutcomeEvaluationStoreResult.Rejected(installed.category)
            is CognitiveEncryptionResult.Failed ->
                OutcomeEvaluationStoreResult.Failed(installed.category, installed.throwable)
        }
    }

    override fun lookup(id: OutcomeEvaluationId): OutcomeEvaluationLookupResult {
        val persistentId = PersistentEntityId(id.value)
        val snapshot = when (val inspected = encryptedStore.inspectResult(persistentId)) {
            PersistentRecordLookupResult.Missing -> return OutcomeEvaluationLookupResult.Missing
            is PersistentRecordLookupResult.Found -> inspected.snapshot
            PersistentRecordLookupResult.Corrupt -> return OutcomeEvaluationLookupResult.Corrupt
            is PersistentRecordLookupResult.Incompatible ->
                return OutcomeEvaluationLookupResult.Incompatible(inspected.reason)
            is PersistentRecordLookupResult.Failed ->
                return OutcomeEvaluationLookupResult.Failed(inspected.reason, inspected.throwable)
        }
        if (snapshot.record.schemaId != OutcomeEvaluationPersistentCodec.schemaId) {
            return OutcomeEvaluationLookupResult.Incompatible("outcome evaluation schema id mismatch")
        }
        val plaintext = when (val opened = encryptedStore.open(persistentId)) {
            is CognitiveEncryptionResult.Success -> opened.value
            is CognitiveEncryptionResult.Rejected ->
                return OutcomeEvaluationLookupResult.EncryptionUnavailable(opened.category)
            is CognitiveEncryptionResult.Failed ->
                return OutcomeEvaluationLookupResult.EncryptionUnavailable(opened.category)
        }
        val bytes = plaintext.copyBytes()
        val decoded = try {
            OutcomeEvaluationPersistentCodec.decode(
                PersistentRecord(
                    snapshot.record.id,
                    snapshot.record.schemaId,
                    snapshot.record.schemaVersion,
                    PersistentPayload(bytes),
                    snapshot.record.createdAt
                )
            )
        } finally {
            bytes.fill(0)
        }
        return when (decoded) {
            is OutcomeEvaluationDecodeResult.Decoded -> OutcomeEvaluationLookupResult.Found(
                OutcomeEvaluationSnapshot(decoded.record, snapshot.generation.value)
            )
            OutcomeEvaluationDecodeResult.Corrupt -> OutcomeEvaluationLookupResult.Corrupt
            is OutcomeEvaluationDecodeResult.Incompatible ->
                OutcomeEvaluationLookupResult.Incompatible(decoded.reason)
        }
    }
    override fun page(
        limit: Int,
        order: PersistentBackendPageOrder,
        cursorExclusive: PersistentBackendPageCursor?
    ): OutcomeEvaluationPageResult = when (
        val page = encryptedStore.decryptedPageResult(
            PersistentBackendPageRequest(
                limit = limit,
                order = order,
                cursorExclusive = cursorExclusive,
                schemaId = OutcomeEvaluationPersistentCodec.schemaId
            )
        )
    ) {
        EncryptedPersistentRecordPageResult.Empty -> OutcomeEvaluationPageResult.Empty
        EncryptedPersistentRecordPageResult.Corrupt -> OutcomeEvaluationPageResult.Corrupt
        is EncryptedPersistentRecordPageResult.Incompatible ->
            OutcomeEvaluationPageResult.Incompatible(page.reason)
        is EncryptedPersistentRecordPageResult.EncryptionUnavailable ->
            OutcomeEvaluationPageResult.EncryptionUnavailable(page.category)
        is EncryptedPersistentRecordPageResult.Failed ->
            OutcomeEvaluationPageResult.Failed(page.reason, page.throwable)
        is EncryptedPersistentRecordPageResult.Loaded -> {
            val decoded = ArrayList<OutcomeEvaluationSnapshot>(page.entries.size)
            for (snapshot in page.entries) {
                when (val evaluation = OutcomeEvaluationPersistentCodec.decode(snapshot.record)) {
                    is OutcomeEvaluationDecodeResult.Decoded -> decoded += OutcomeEvaluationSnapshot(
                        evaluation.record,
                        snapshot.generation.value
                    )
                    OutcomeEvaluationDecodeResult.Corrupt ->
                        return OutcomeEvaluationPageResult.Corrupt
                    is OutcomeEvaluationDecodeResult.Incompatible ->
                        return OutcomeEvaluationPageResult.Incompatible(evaluation.reason)
                }
            }
            OutcomeEvaluationPageResult.Loaded(decoded, page.nextCursor)
        }
    }

    companion object {
        fun open(
            encryptedStore: EncryptedPersistentRecordStore,
            activeDek: CognitiveDekReference
        ): OutcomeEvaluationMemoryOpenResult =
            if (!encryptedStore.supportsIndexedLazyMode()) {
                OutcomeEvaluationMemoryOpenResult.Incompatible(
                    "outcome evaluation memory requires indexed lazy persistence"
                )
            } else {
                OutcomeEvaluationMemoryOpenResult.Opened(
                    EncryptedPersistentOutcomeEvaluationStore(encryptedStore, activeDek)
                )
            }
    }
}
