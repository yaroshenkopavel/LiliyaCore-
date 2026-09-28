package pro.liliya.core.reflection

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

sealed interface BoundedReflectionMemoryOpenResult {
    data class Opened(val store: EncryptedPersistentBoundedReflectionStore) : BoundedReflectionMemoryOpenResult
    data class Incompatible(val reason: String) : BoundedReflectionMemoryOpenResult
}

sealed interface BoundedReflectionStoreResult {
    data class Stored(val snapshot: BoundedReflectionSnapshot) : BoundedReflectionStoreResult
    data class Rejected(val category: CognitiveEncryptionFailureCategory) : BoundedReflectionStoreResult
    data class Failed(
        val category: CognitiveEncryptionFailureCategory,
        val throwable: Throwable? = null
    ) : BoundedReflectionStoreResult
}

sealed interface BoundedReflectionLookupResult {
    data object Missing : BoundedReflectionLookupResult
    data class Found(val snapshot: BoundedReflectionSnapshot) : BoundedReflectionLookupResult
    data object Corrupt : BoundedReflectionLookupResult
    data class Incompatible(val reason: String) : BoundedReflectionLookupResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) :
        BoundedReflectionLookupResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        BoundedReflectionLookupResult
}

sealed interface BoundedReflectionPageResult {
    data object Empty : BoundedReflectionPageResult
    data class Loaded(
        val entries: List<BoundedReflectionSnapshot>,
        val nextCursor: PersistentBackendPageCursor?
    ) : BoundedReflectionPageResult
    data object Corrupt : BoundedReflectionPageResult
    data class Incompatible(val reason: String) : BoundedReflectionPageResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) :
        BoundedReflectionPageResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        BoundedReflectionPageResult
}

interface BoundedReflectionRepository {
    fun store(record: BoundedReflectionRecord): BoundedReflectionStoreResult
    fun lookup(id: ReflectionResultId): BoundedReflectionLookupResult
    fun page(
        limit: Int,
        order: PersistentBackendPageOrder = PersistentBackendPageOrder.NEWEST_FIRST,
        cursorExclusive: PersistentBackendPageCursor? = null
    ): BoundedReflectionPageResult
}

class EncryptedPersistentBoundedReflectionStore private constructor(
    private val encryptedStore: EncryptedPersistentRecordStore,
    private val activeDek: CognitiveDekReference
) : BoundedReflectionRepository {
    override fun store(record: BoundedReflectionRecord): BoundedReflectionStoreResult {
        val encoded = BoundedReflectionPersistentCodec.encode(record)
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
            is CognitiveEncryptionResult.Success -> BoundedReflectionStoreResult.Stored(
                BoundedReflectionSnapshot(record, installed.value.generation.value)
            )
            is CognitiveEncryptionResult.Rejected ->
                BoundedReflectionStoreResult.Rejected(installed.category)
            is CognitiveEncryptionResult.Failed ->
                BoundedReflectionStoreResult.Failed(installed.category, installed.throwable)
        }
    }

    override fun lookup(id: ReflectionResultId): BoundedReflectionLookupResult {
        val persistentId = PersistentEntityId(id.value)
        val snapshot = when (val inspected = encryptedStore.inspectResult(persistentId)) {
            PersistentRecordLookupResult.Missing -> return BoundedReflectionLookupResult.Missing
            is PersistentRecordLookupResult.Found -> inspected.snapshot
            PersistentRecordLookupResult.Corrupt -> return BoundedReflectionLookupResult.Corrupt
            is PersistentRecordLookupResult.Incompatible ->
                return BoundedReflectionLookupResult.Incompatible(inspected.reason)
            is PersistentRecordLookupResult.Failed ->
                return BoundedReflectionLookupResult.Failed(inspected.reason, inspected.throwable)
        }
        if (snapshot.record.schemaId != BoundedReflectionPersistentCodec.schemaId) {
            return BoundedReflectionLookupResult.Incompatible("bounded reflection schema id mismatch")
        }
        val plaintext = when (val opened = encryptedStore.open(persistentId)) {
            is CognitiveEncryptionResult.Success -> opened.value
            is CognitiveEncryptionResult.Rejected ->
                return BoundedReflectionLookupResult.EncryptionUnavailable(opened.category)
            is CognitiveEncryptionResult.Failed ->
                return BoundedReflectionLookupResult.EncryptionUnavailable(opened.category)
        }
        val bytes = plaintext.copyBytes()
        val decoded = try {
            BoundedReflectionPersistentCodec.decode(
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
            is BoundedReflectionDecodeResult.Decoded -> BoundedReflectionLookupResult.Found(
                BoundedReflectionSnapshot(decoded.record, snapshot.generation.value)
            )
            BoundedReflectionDecodeResult.Corrupt -> BoundedReflectionLookupResult.Corrupt
            is BoundedReflectionDecodeResult.Incompatible ->
                BoundedReflectionLookupResult.Incompatible(decoded.reason)
        }
    }

    override fun page(
        limit: Int,
        order: PersistentBackendPageOrder,
        cursorExclusive: PersistentBackendPageCursor?
    ): BoundedReflectionPageResult = when (
        val page = encryptedStore.decryptedPageResult(
            PersistentBackendPageRequest(
                limit = limit,
                order = order,
                cursorExclusive = cursorExclusive,
                schemaId = BoundedReflectionPersistentCodec.schemaId
            )
        )
    ) {
        EncryptedPersistentRecordPageResult.Empty -> BoundedReflectionPageResult.Empty
        EncryptedPersistentRecordPageResult.Corrupt -> BoundedReflectionPageResult.Corrupt
        is EncryptedPersistentRecordPageResult.Incompatible ->
            BoundedReflectionPageResult.Incompatible(page.reason)
        is EncryptedPersistentRecordPageResult.EncryptionUnavailable ->
            BoundedReflectionPageResult.EncryptionUnavailable(page.category)
        is EncryptedPersistentRecordPageResult.Failed ->
            BoundedReflectionPageResult.Failed(page.reason, page.throwable)
        is EncryptedPersistentRecordPageResult.Loaded -> {
            val decoded = ArrayList<BoundedReflectionSnapshot>(page.entries.size)
            for (snapshot in page.entries) {
                when (val result = BoundedReflectionPersistentCodec.decode(snapshot.record)) {
                    is BoundedReflectionDecodeResult.Decoded ->
                        decoded += BoundedReflectionSnapshot(result.record, snapshot.generation.value)
                    BoundedReflectionDecodeResult.Corrupt ->
                        return BoundedReflectionPageResult.Corrupt
                    is BoundedReflectionDecodeResult.Incompatible ->
                        return BoundedReflectionPageResult.Incompatible(result.reason)
                }
            }
            BoundedReflectionPageResult.Loaded(decoded, page.nextCursor)
        }
    }

    companion object {
        fun open(
            encryptedStore: EncryptedPersistentRecordStore,
            activeDek: CognitiveDekReference
        ): BoundedReflectionMemoryOpenResult =
            if (!encryptedStore.supportsIndexedLazyMode()) {
                BoundedReflectionMemoryOpenResult.Incompatible(
                    "bounded reflection requires indexed lazy persistence"
                )
            } else {
                BoundedReflectionMemoryOpenResult.Opened(
                    EncryptedPersistentBoundedReflectionStore(encryptedStore, activeDek)
                )
            }
    }
}
