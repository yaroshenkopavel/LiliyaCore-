package pro.liliya.core.episodic

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

sealed interface EpisodicMemoryOpenResult {
    data class Opened(val store: EncryptedPersistentEpisodicMemoryStore) : EpisodicMemoryOpenResult
    data class Incompatible(val reason: String) : EpisodicMemoryOpenResult
}

sealed interface EpisodeStoreResult {
    data class Stored(val snapshot: EpisodeSnapshot) : EpisodeStoreResult
    data class Rejected(val category: CognitiveEncryptionFailureCategory) : EpisodeStoreResult
    data class Failed(val category: CognitiveEncryptionFailureCategory, val throwable: Throwable? = null) : EpisodeStoreResult
}

sealed interface EpisodeLookupResult {
    data object Missing : EpisodeLookupResult
    data class Found(val snapshot: EpisodeSnapshot) : EpisodeLookupResult
    data object Corrupt : EpisodeLookupResult
    data class Incompatible(val reason: String) : EpisodeLookupResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) : EpisodeLookupResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : EpisodeLookupResult
}

sealed interface EpisodePageResult {
    data object Empty : EpisodePageResult
    data class Loaded(
        val entries: List<EpisodeSnapshot>,
        val nextCursor: PersistentBackendPageCursor?
    ) : EpisodePageResult
    data object Corrupt : EpisodePageResult
    data class Incompatible(val reason: String) : EpisodePageResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) : EpisodePageResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : EpisodePageResult
}

interface EpisodicMemoryRepository {
    fun store(record: EpisodeRecord): EpisodeStoreResult
    fun lookup(id: EpisodeId): EpisodeLookupResult
}

class EncryptedPersistentEpisodicMemoryStore private constructor(
    private val encryptedStore: EncryptedPersistentRecordStore,
    private val activeDek: CognitiveDekReference
) : EpisodicMemoryRepository {
    override fun store(record: EpisodeRecord): EpisodeStoreResult {
        val encoded = EpisodicMemoryPersistentCodec.encode(record)
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
            is CognitiveEncryptionResult.Success -> EpisodeStoreResult.Stored(
                EpisodeSnapshot(record, installed.value.generation.value)
            )
            is CognitiveEncryptionResult.Rejected -> EpisodeStoreResult.Rejected(installed.category)
            is CognitiveEncryptionResult.Failed -> EpisodeStoreResult.Failed(installed.category, installed.throwable)
        }
    }

    override fun lookup(id: EpisodeId): EpisodeLookupResult {
        val persistentId = PersistentEntityId(id.value)
        val snapshot = when (val inspected = encryptedStore.inspectResult(persistentId)) {
            PersistentRecordLookupResult.Missing -> return EpisodeLookupResult.Missing
            is PersistentRecordLookupResult.Found -> inspected.snapshot
            PersistentRecordLookupResult.Corrupt -> return EpisodeLookupResult.Corrupt
            is PersistentRecordLookupResult.Incompatible -> return EpisodeLookupResult.Incompatible(inspected.reason)
            is PersistentRecordLookupResult.Failed -> return EpisodeLookupResult.Failed(inspected.reason, inspected.throwable)
        }
        if (snapshot.record.schemaId != EpisodicMemoryPersistentCodec.schemaId) {
            return EpisodeLookupResult.Incompatible("episodic schema id mismatch")
        }
        val plaintext = when (val opened = encryptedStore.open(persistentId)) {
            is CognitiveEncryptionResult.Success -> opened.value
            is CognitiveEncryptionResult.Rejected -> return EpisodeLookupResult.EncryptionUnavailable(opened.category)
            is CognitiveEncryptionResult.Failed -> return EpisodeLookupResult.EncryptionUnavailable(opened.category)
        }
        val bytes = plaintext.copyBytes()
        val decoded = try {
            EpisodicMemoryPersistentCodec.decode(
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
            is EpisodePersistentDecodeResult.Decoded -> EpisodeLookupResult.Found(
                EpisodeSnapshot(decoded.record, snapshot.generation.value)
            )
            EpisodePersistentDecodeResult.Corrupt -> EpisodeLookupResult.Corrupt
            is EpisodePersistentDecodeResult.Incompatible -> EpisodeLookupResult.Incompatible(decoded.reason)
        }
    }

    fun queryTemporal(
        query: EpisodeTemporalQuery
    ): EpisodeTemporalQueryResult =
        EpisodeTemporalQueryExecutor.execute(
            query = query,
            pageLoader = encryptedStore::decryptedPageResult
        )

    fun page(
        limit: Int,
        order: PersistentBackendPageOrder = PersistentBackendPageOrder.NEWEST_FIRST,
        cursorExclusive: PersistentBackendPageCursor? = null
    ): EpisodePageResult = when (
        val page = encryptedStore.decryptedPageResult(
            PersistentBackendPageRequest(
                limit = limit,
                order = order,
                cursorExclusive = cursorExclusive,
                schemaId = EpisodicMemoryPersistentCodec.schemaId
            )
        )
    ) {
        EncryptedPersistentRecordPageResult.Empty -> EpisodePageResult.Empty
        EncryptedPersistentRecordPageResult.Corrupt -> EpisodePageResult.Corrupt
        is EncryptedPersistentRecordPageResult.Incompatible -> EpisodePageResult.Incompatible(page.reason)
        is EncryptedPersistentRecordPageResult.EncryptionUnavailable -> EpisodePageResult.EncryptionUnavailable(page.category)
        is EncryptedPersistentRecordPageResult.Failed -> EpisodePageResult.Failed(page.reason, page.throwable)
        is EncryptedPersistentRecordPageResult.Loaded -> {
            val decoded = ArrayList<EpisodeSnapshot>(page.entries.size)
            for (snapshot in page.entries) {
                when (val episode = EpisodicMemoryPersistentCodec.decode(snapshot.record)) {
                    is EpisodePersistentDecodeResult.Decoded -> decoded += EpisodeSnapshot(
                        episode.record,
                        snapshot.generation.value
                    )
                    EpisodePersistentDecodeResult.Corrupt -> return EpisodePageResult.Corrupt
                    is EpisodePersistentDecodeResult.Incompatible -> return EpisodePageResult.Incompatible(episode.reason)
                }
            }
            EpisodePageResult.Loaded(decoded, page.nextCursor)
        }
    }

    companion object {
        fun open(
            encryptedStore: EncryptedPersistentRecordStore,
            activeDek: CognitiveDekReference
        ): EpisodicMemoryOpenResult =
            if (!encryptedStore.supportsIndexedLazyMode()) {
                EpisodicMemoryOpenResult.Incompatible(
                    "episodic memory requires indexed lazy persistence"
                )
            } else {
                EpisodicMemoryOpenResult.Opened(
                    EncryptedPersistentEpisodicMemoryStore(encryptedStore, activeDek)
                )
            }
    }
}
