package pro.liliya.core.strategy

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

sealed interface StrategyAdaptationMemoryOpenResult {
    data class Opened(val store: EncryptedPersistentStrategyAdaptationStore) :
        StrategyAdaptationMemoryOpenResult
    data class Incompatible(val reason: String) : StrategyAdaptationMemoryOpenResult
}

sealed interface StrategyAdaptationStoreResult {
    data class Stored(val snapshot: StrategyAdaptationSnapshot) : StrategyAdaptationStoreResult
    data class Rejected(val category: CognitiveEncryptionFailureCategory) :
        StrategyAdaptationStoreResult
    data class Failed(
        val category: CognitiveEncryptionFailureCategory,
        val throwable: Throwable? = null
    ) : StrategyAdaptationStoreResult
}

sealed interface StrategyAdaptationLookupResult {
    data object Missing : StrategyAdaptationLookupResult
    data class Found(val snapshot: StrategyAdaptationSnapshot) : StrategyAdaptationLookupResult
    data object Corrupt : StrategyAdaptationLookupResult
    data class Incompatible(val reason: String) : StrategyAdaptationLookupResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) :
        StrategyAdaptationLookupResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        StrategyAdaptationLookupResult
}

sealed interface StrategyAdaptationPageResult {
    data object Empty : StrategyAdaptationPageResult
    data class Loaded(
        val entries: List<StrategyAdaptationSnapshot>,
        val nextCursor: PersistentBackendPageCursor?
    ) : StrategyAdaptationPageResult
    data object Corrupt : StrategyAdaptationPageResult
    data class Incompatible(val reason: String) : StrategyAdaptationPageResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) :
        StrategyAdaptationPageResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        StrategyAdaptationPageResult
}

interface StrategyAdaptationRepository {
    fun store(record: StrategyAdaptationRecord): StrategyAdaptationStoreResult
    fun lookup(id: StrategyCandidateId): StrategyAdaptationLookupResult
    fun page(
        limit: Int,
        order: PersistentBackendPageOrder = PersistentBackendPageOrder.NEWEST_FIRST,
        cursorExclusive: PersistentBackendPageCursor? = null
    ): StrategyAdaptationPageResult
}

class EncryptedPersistentStrategyAdaptationStore private constructor(
    private val encryptedStore: EncryptedPersistentRecordStore,
    private val activeDek: CognitiveDekReference
) : StrategyAdaptationRepository {
    override fun store(record: StrategyAdaptationRecord): StrategyAdaptationStoreResult {
        val encoded = StrategyAdaptationPersistentCodec.encode(record)
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
                StrategyAdaptationStoreResult.Stored(
                    StrategyAdaptationSnapshot(record, installed.value.generation.value)
                )
            is CognitiveEncryptionResult.Rejected ->
                StrategyAdaptationStoreResult.Rejected(installed.category)
            is CognitiveEncryptionResult.Failed ->
                StrategyAdaptationStoreResult.Failed(installed.category, installed.throwable)
        }
    }

    override fun lookup(id: StrategyCandidateId): StrategyAdaptationLookupResult {
        val persistentId = PersistentEntityId(id.value)
        val snapshot = when (val inspected = encryptedStore.inspectResult(persistentId)) {
            PersistentRecordLookupResult.Missing -> return StrategyAdaptationLookupResult.Missing
            is PersistentRecordLookupResult.Found -> inspected.snapshot
            PersistentRecordLookupResult.Corrupt -> return StrategyAdaptationLookupResult.Corrupt
            is PersistentRecordLookupResult.Incompatible ->
                return StrategyAdaptationLookupResult.Incompatible(inspected.reason)
            is PersistentRecordLookupResult.Failed ->
                return StrategyAdaptationLookupResult.Failed(inspected.reason, inspected.throwable)
        }
        if (snapshot.record.schemaId != StrategyAdaptationPersistentCodec.schemaId) {
            return StrategyAdaptationLookupResult.Incompatible(
                "strategy adaptation schema id mismatch"
            )
        }

        val plaintext = when (val opened = encryptedStore.open(persistentId)) {
            is CognitiveEncryptionResult.Success -> opened.value
            is CognitiveEncryptionResult.Rejected ->
                return StrategyAdaptationLookupResult.EncryptionUnavailable(opened.category)
            is CognitiveEncryptionResult.Failed ->
                return StrategyAdaptationLookupResult.EncryptionUnavailable(opened.category)
        }
        val bytes = plaintext.copyBytes()
        val decoded = try {
            StrategyAdaptationPersistentCodec.decode(
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
            is StrategyAdaptationDecodeResult.Decoded ->
                StrategyAdaptationLookupResult.Found(
                    StrategyAdaptationSnapshot(decoded.record, snapshot.generation.value)
                )
            StrategyAdaptationDecodeResult.Corrupt -> StrategyAdaptationLookupResult.Corrupt
            is StrategyAdaptationDecodeResult.Incompatible ->
                StrategyAdaptationLookupResult.Incompatible(decoded.reason)
        }
    }

    override fun page(
        limit: Int,
        order: PersistentBackendPageOrder,
        cursorExclusive: PersistentBackendPageCursor?
    ): StrategyAdaptationPageResult = when (
        val page = encryptedStore.decryptedPageResult(
            PersistentBackendPageRequest(
                limit = limit,
                order = order,
                cursorExclusive = cursorExclusive,
                schemaId = StrategyAdaptationPersistentCodec.schemaId
            )
        )
    ) {
        EncryptedPersistentRecordPageResult.Empty -> StrategyAdaptationPageResult.Empty
        EncryptedPersistentRecordPageResult.Corrupt -> StrategyAdaptationPageResult.Corrupt
        is EncryptedPersistentRecordPageResult.Incompatible ->
            StrategyAdaptationPageResult.Incompatible(page.reason)
        is EncryptedPersistentRecordPageResult.EncryptionUnavailable ->
            StrategyAdaptationPageResult.EncryptionUnavailable(page.category)
        is EncryptedPersistentRecordPageResult.Failed ->
            StrategyAdaptationPageResult.Failed(page.reason, page.throwable)
        is EncryptedPersistentRecordPageResult.Loaded -> {
            val decoded = ArrayList<StrategyAdaptationSnapshot>(page.entries.size)
            for (snapshot in page.entries) {
                when (val result = StrategyAdaptationPersistentCodec.decode(snapshot.record)) {
                    is StrategyAdaptationDecodeResult.Decoded ->
                        decoded += StrategyAdaptationSnapshot(
                            result.record,
                            snapshot.generation.value
                        )
                    StrategyAdaptationDecodeResult.Corrupt ->
                        return StrategyAdaptationPageResult.Corrupt
                    is StrategyAdaptationDecodeResult.Incompatible ->
                        return StrategyAdaptationPageResult.Incompatible(result.reason)
                }
            }
            StrategyAdaptationPageResult.Loaded(decoded, page.nextCursor)
        }
    }

    companion object {
        fun open(
            encryptedStore: EncryptedPersistentRecordStore,
            activeDek: CognitiveDekReference
        ): StrategyAdaptationMemoryOpenResult =
            if (!encryptedStore.supportsIndexedLazyMode()) {
                StrategyAdaptationMemoryOpenResult.Incompatible(
                    "strategy adaptation requires indexed lazy persistence"
                )
            } else {
                StrategyAdaptationMemoryOpenResult.Opened(
                    EncryptedPersistentStrategyAdaptationStore(encryptedStore, activeDek)
                )
            }
    }
}
