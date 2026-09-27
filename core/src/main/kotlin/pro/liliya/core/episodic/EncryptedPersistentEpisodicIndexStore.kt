package pro.liliya.core.episodic

import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.encryption.CognitiveEncryptionFailureCategory
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitivePersistentRecordDraft
import pro.liliya.core.encryption.CognitivePlaintext
import pro.liliya.core.encryption.EncryptedPersistentRecordPageResult
import pro.liliya.core.encryption.EncryptedPersistentRecordStore
import pro.liliya.core.persistence.PersistentBackendMetadata
import pro.liliya.core.persistence.PersistentBackendPageCursor
import pro.liliya.core.persistence.PersistentBackendPageOrder
import pro.liliya.core.persistence.PersistentBackendPageRequest
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentRecordLookupResult

sealed interface EpisodicIndexOpenResult {
    data class Opened(val store: EncryptedPersistentEpisodicIndexStore) : EpisodicIndexOpenResult
    data class Incompatible(val reason: String) : EpisodicIndexOpenResult
}

sealed interface EpisodeIndexWriteResult {
    data class Indexed(val entry: EpisodeIndexEntry, val indexGeneration: Long) : EpisodeIndexWriteResult
    data class AlreadyIndexed(val entry: EpisodeIndexEntry, val indexGeneration: Long) : EpisodeIndexWriteResult
    data class Rejected(val reason: String) : EpisodeIndexWriteResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : EpisodeIndexWriteResult
}

sealed interface EpisodeIndexProjectionResult {
    data class Complete(
        val projected: Int,
        val indexed: Int,
        val alreadyIndexed: Int
    ) : EpisodeIndexProjectionResult

    data class Incomplete(
        val projected: Int,
        val indexed: Int,
        val alreadyIndexed: Int,
        val failedEntryId: EpisodeIndexEntryId,
        val reason: String
    ) : EpisodeIndexProjectionResult
}

sealed interface EpisodeIndexManifestResult {
    data object Missing : EpisodeIndexManifestResult
    data class Loaded(val manifest: EpisodeIndexManifest, val indexGeneration: Long) : EpisodeIndexManifestResult
    data object Corrupt : EpisodeIndexManifestResult
    data class Incompatible(val reason: String) : EpisodeIndexManifestResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : EpisodeIndexManifestResult
}

sealed interface EpisodeIndexPageResult {
    data object Empty : EpisodeIndexPageResult
    data class Loaded(
        val entries: List<EpisodeIndexEntry>,
        val nextCursor: PersistentBackendPageCursor?
    ) : EpisodeIndexPageResult
    data object Corrupt : EpisodeIndexPageResult
    data class Incompatible(val reason: String) : EpisodeIndexPageResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) : EpisodeIndexPageResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : EpisodeIndexPageResult
}

interface EpisodicIndexProjectionStore {
    fun project(snapshot: EpisodeSnapshot): EpisodeIndexProjectionResult
    fun writeManifest(manifest: EpisodeIndexManifest): EpisodeIndexManifestResult
    fun completeness(source: EpisodeIndexSourceCheckpoint): EpisodeIndexCompleteness
}

class EncryptedPersistentEpisodicIndexStore private constructor(
    private val encryptedStore: EncryptedPersistentRecordStore,
    private val activeDek: CognitiveDekReference
) : EpisodicIndexProjectionStore, EpisodicIndexReadStore {
    override fun project(snapshot: EpisodeSnapshot): EpisodeIndexProjectionResult {
        val entries = EpisodeIndexProjector.project(snapshot)
        var indexed = 0
        var already = 0
        for (entry in entries) {
            when (val result = write(entry)) {
                is EpisodeIndexWriteResult.Indexed -> indexed += 1
                is EpisodeIndexWriteResult.AlreadyIndexed -> already += 1
                is EpisodeIndexWriteResult.Rejected -> return EpisodeIndexProjectionResult.Incomplete(
                    projected = entries.size,
                    indexed = indexed,
                    alreadyIndexed = already,
                    failedEntryId = entry.id,
                    reason = result.reason
                )
                is EpisodeIndexWriteResult.Failed -> return EpisodeIndexProjectionResult.Incomplete(
                    projected = entries.size,
                    indexed = indexed,
                    alreadyIndexed = already,
                    failedEntryId = entry.id,
                    reason = result.reason
                )
            }
        }
        return EpisodeIndexProjectionResult.Complete(entries.size, indexed, already)
    }

    fun write(entry: EpisodeIndexEntry): EpisodeIndexWriteResult {
        val encoded = EpisodicIndexPersistentCodec.encode(entry)
        val id = encoded.id
        when (val existing = encryptedStore.inspectResult(id)) {
            PersistentRecordLookupResult.Missing -> Unit
            is PersistentRecordLookupResult.Found -> {
                val decoded = decodeEntry(existing.snapshot.record, existing.snapshot.generation.value)
                return when (decoded) {
                    is DecodedIndexEntry.Success -> {
                        if (decoded.entry == entry) {
                            EpisodeIndexWriteResult.AlreadyIndexed(entry, decoded.indexGeneration)
                        } else {
                            EpisodeIndexWriteResult.Rejected("episodic index entry identity diverged")
                        }
                    }
                    is DecodedIndexEntry.Corrupt -> EpisodeIndexWriteResult.Failed("episodic index entry is corrupt")
                    is DecodedIndexEntry.Incompatible -> EpisodeIndexWriteResult.Failed(decoded.reason)
                    is DecodedIndexEntry.Failed -> EpisodeIndexWriteResult.Failed(decoded.reason, decoded.throwable)
                }
            }
            PersistentRecordLookupResult.Corrupt -> return EpisodeIndexWriteResult.Failed("episodic index persistence is corrupt")
            is PersistentRecordLookupResult.Incompatible -> return EpisodeIndexWriteResult.Failed(existing.reason)
            is PersistentRecordLookupResult.Failed -> return EpisodeIndexWriteResult.Failed(existing.reason, existing.throwable)
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
            is CognitiveEncryptionResult.Success -> EpisodeIndexWriteResult.Indexed(
                entry,
                installed.value.generation.value
            )
            is CognitiveEncryptionResult.Rejected -> EpisodeIndexWriteResult.Rejected(
                "episodic index install rejected: ${installed.category}"
            )
            is CognitiveEncryptionResult.Failed -> EpisodeIndexWriteResult.Failed(
                "episodic index install failed: ${installed.category}",
                installed.throwable
            )
        }
    }

    fun manifest(): EpisodeIndexManifestResult {
        val snapshot = when (val inspected = encryptedStore.inspectResult(EpisodicIndexManifestCodec.entityId)) {
            PersistentRecordLookupResult.Missing -> return EpisodeIndexManifestResult.Missing
            is PersistentRecordLookupResult.Found -> inspected.snapshot
            PersistentRecordLookupResult.Corrupt -> return EpisodeIndexManifestResult.Corrupt
            is PersistentRecordLookupResult.Incompatible -> return EpisodeIndexManifestResult.Incompatible(inspected.reason)
            is PersistentRecordLookupResult.Failed -> return EpisodeIndexManifestResult.Failed(inspected.reason, inspected.throwable)
        }
        val plaintext = when (val opened = encryptedStore.open(EpisodicIndexManifestCodec.entityId)) {
            is CognitiveEncryptionResult.Success -> opened.value
            is CognitiveEncryptionResult.Rejected -> return EpisodeIndexManifestResult.Failed(
                "episodic index manifest encryption unavailable: ${opened.category}"
            )
            is CognitiveEncryptionResult.Failed -> return EpisodeIndexManifestResult.Failed(
                "episodic index manifest open failed: ${opened.category}",
                opened.throwable
            )
        }
        val bytes = plaintext.copyBytes()
        val decoded = try {
            EpisodicIndexManifestCodec.decode(
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
            is EpisodeIndexManifestDecodeResult.Decoded -> EpisodeIndexManifestResult.Loaded(
                decoded.manifest,
                snapshot.generation.value
            )
            EpisodeIndexManifestDecodeResult.Corrupt -> EpisodeIndexManifestResult.Corrupt
            is EpisodeIndexManifestDecodeResult.Incompatible -> EpisodeIndexManifestResult.Incompatible(decoded.reason)
        }
    }

    override fun writeManifest(manifest: EpisodeIndexManifest): EpisodeIndexManifestResult {
        val encoded = EpisodicIndexManifestCodec.encode(manifest)
        val existing = when (val inspected = encryptedStore.inspectResult(EpisodicIndexManifestCodec.entityId)) {
            PersistentRecordLookupResult.Missing -> null
            is PersistentRecordLookupResult.Found -> inspected.snapshot
            PersistentRecordLookupResult.Corrupt -> return EpisodeIndexManifestResult.Corrupt
            is PersistentRecordLookupResult.Incompatible -> return EpisodeIndexManifestResult.Incompatible(inspected.reason)
            is PersistentRecordLookupResult.Failed -> return EpisodeIndexManifestResult.Failed(inspected.reason, inspected.throwable)
        }

        val bytes = encoded.payload.copyBytes()
        val result = try {
            if (existing == null) {
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
            } else {
                encryptedStore.transitionExact(
                    sourceId = existing.record.id,
                    sourceGeneration = existing.generation,
                    replacement = CognitivePersistentRecordDraft(
                        id = encoded.id,
                        schemaId = encoded.schemaId,
                        schemaVersion = encoded.schemaVersion,
                        plaintext = CognitivePlaintext(bytes),
                        createdAt = encoded.createdAt,
                        dek = activeDek
                    )
                )
            }
        } finally {
            bytes.fill(0)
        }
        return when (result) {
            is CognitiveEncryptionResult.Success -> EpisodeIndexManifestResult.Loaded(
                manifest,
                result.value.generation.value
            )
            is CognitiveEncryptionResult.Rejected -> EpisodeIndexManifestResult.Failed(
                "episodic index manifest write rejected: ${result.category}"
            )
            is CognitiveEncryptionResult.Failed -> EpisodeIndexManifestResult.Failed(
                "episodic index manifest write failed: ${result.category}",
                result.throwable
            )
        }
    }

    override fun completeness(source: EpisodeIndexSourceCheckpoint): EpisodeIndexCompleteness =
        when (val current = manifest()) {
            is EpisodeIndexManifestResult.Loaded ->
                if (current.manifest.source == source) EpisodeIndexCompleteness.COMPLETE
                else EpisodeIndexCompleteness.INCOMPLETE
            EpisodeIndexManifestResult.Missing -> EpisodeIndexCompleteness.UNKNOWN
            EpisodeIndexManifestResult.Corrupt,
            is EpisodeIndexManifestResult.Incompatible,
            is EpisodeIndexManifestResult.Failed -> EpisodeIndexCompleteness.INCOMPLETE
        }

    override fun temporalPage(
        axis: EpisodeTemporalAxis,
        limit: Int,
        order: PersistentBackendPageOrder,
        cursorExclusive: PersistentBackendPageCursor?
    ): EpisodeIndexPageResult = page(
        PersistentBackendPageRequest(
            limit = limit,
            order = order,
            cursorExclusive = cursorExclusive,
            schemaId = EpisodicIndexPersistentCodec.temporalSchemaId(axis)
        )
    )

    override fun provenancePage(
        limit: Int,
        order: PersistentBackendPageOrder,
        cursorExclusive: PersistentBackendPageCursor?
    ): EpisodeIndexPageResult = page(
        PersistentBackendPageRequest(
            limit = limit,
            order = order,
            cursorExclusive = cursorExclusive,
            schemaId = EpisodicIndexPersistentCodec.provenanceSchemaId
        )
    )

    private fun page(request: PersistentBackendPageRequest): EpisodeIndexPageResult =
        when (val loaded = encryptedStore.decryptedPageResult(request)) {
            EncryptedPersistentRecordPageResult.Empty -> EpisodeIndexPageResult.Empty
            EncryptedPersistentRecordPageResult.Corrupt -> EpisodeIndexPageResult.Corrupt
            is EncryptedPersistentRecordPageResult.Incompatible -> EpisodeIndexPageResult.Incompatible(loaded.reason)
            is EncryptedPersistentRecordPageResult.EncryptionUnavailable -> EpisodeIndexPageResult.EncryptionUnavailable(loaded.category)
            is EncryptedPersistentRecordPageResult.Failed -> EpisodeIndexPageResult.Failed(loaded.reason, loaded.throwable)
            is EncryptedPersistentRecordPageResult.Loaded -> {
                val entries = ArrayList<EpisodeIndexEntry>(loaded.entries.size)
                for (snapshot in loaded.entries) {
                    when (val decoded = EpisodicIndexPersistentCodec.decode(snapshot.record)) {
                        is EpisodeIndexDecodeResult.Decoded -> entries += decoded.entry
                        EpisodeIndexDecodeResult.Corrupt -> return EpisodeIndexPageResult.Corrupt
                        is EpisodeIndexDecodeResult.Incompatible -> return EpisodeIndexPageResult.Incompatible(decoded.reason)
                    }
                }
                EpisodeIndexPageResult.Loaded(entries, loaded.nextCursor)
            }
        }

    private fun decodeEntry(
        encryptedRecord: PersistentRecord,
        indexGeneration: Long
    ): DecodedIndexEntry {
        val id = encryptedRecord.id
        val plaintext = when (val opened = encryptedStore.open(id)) {
            is CognitiveEncryptionResult.Success -> opened.value
            is CognitiveEncryptionResult.Rejected -> return DecodedIndexEntry.Failed(
                "episodic index entry encryption unavailable: ${opened.category}"
            )
            is CognitiveEncryptionResult.Failed -> return DecodedIndexEntry.Failed(
                "episodic index entry open failed: ${opened.category}",
                opened.throwable
            )
        }
        val bytes = plaintext.copyBytes()
        val decoded = try {
            EpisodicIndexPersistentCodec.decode(
                PersistentRecord(
                    id = encryptedRecord.id,
                    schemaId = encryptedRecord.schemaId,
                    schemaVersion = encryptedRecord.schemaVersion,
                    payload = PersistentPayload(bytes),
                    createdAt = encryptedRecord.createdAt
                )
            )
        } finally {
            bytes.fill(0)
        }
        return when (decoded) {
            is EpisodeIndexDecodeResult.Decoded -> DecodedIndexEntry.Success(decoded.entry, indexGeneration)
            EpisodeIndexDecodeResult.Corrupt -> DecodedIndexEntry.Corrupt
            is EpisodeIndexDecodeResult.Incompatible -> DecodedIndexEntry.Incompatible(decoded.reason)
        }
    }

    private sealed interface DecodedIndexEntry {
        data class Success(val entry: EpisodeIndexEntry, val indexGeneration: Long) : DecodedIndexEntry
        data object Corrupt : DecodedIndexEntry
        data class Incompatible(val reason: String) : DecodedIndexEntry
        data class Failed(val reason: String, val throwable: Throwable? = null) : DecodedIndexEntry
    }

    companion object {
        fun open(
            encryptedStore: EncryptedPersistentRecordStore,
            activeDek: CognitiveDekReference
        ): EpisodicIndexOpenResult =
            if (!encryptedStore.supportsIndexedLazyMode()) {
                EpisodicIndexOpenResult.Incompatible("episodic index requires indexed lazy persistence")
            } else {
                EpisodicIndexOpenResult.Opened(
                    EncryptedPersistentEpisodicIndexStore(encryptedStore, activeDek)
                )
            }
    }
}

internal fun PersistentBackendMetadata.toEpisodeIndexSourceCheckpoint(): EpisodeIndexSourceCheckpoint =
    EpisodeIndexSourceCheckpoint(
        revision = revision,
        highWatermark = highWatermark,
        entryCount = entryCount
    )
