package pro.liliya.core.semantic

import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitivePersistentRecordDraft
import pro.liliya.core.encryption.CognitivePlaintext
import pro.liliya.core.encryption.EncryptedPersistentRecordStore
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentRecordLookupResult

sealed interface KnowledgeGraphProjectionStoreOpenResult {
    data class Opened(val store: EncryptedPersistentKnowledgeGraphProjectionStore) :
        KnowledgeGraphProjectionStoreOpenResult
    data class Incompatible(val reason: String) : KnowledgeGraphProjectionStoreOpenResult
}

class EncryptedPersistentKnowledgeGraphProjectionStore private constructor(
    private val encryptedStore: EncryptedPersistentRecordStore,
    private val activeDek: CognitiveDekReference
) : KnowledgeGraphProjectionWriter, KnowledgeGraphProjectionReader {

    override fun readManifest(): KnowledgeGraphProjectionManifestLoadResult {
        val record = when (
            val loaded = openPlaintextRecord(KnowledgeGraphProjectionCodec.manifestEntityId)
        ) {
            PlaintextRecordLoadResult.Missing ->
                return KnowledgeGraphProjectionManifestLoadResult.Missing
            PlaintextRecordLoadResult.Corrupt ->
                return KnowledgeGraphProjectionManifestLoadResult.Corrupt
            is PlaintextRecordLoadResult.Incompatible ->
                return KnowledgeGraphProjectionManifestLoadResult.Incompatible(loaded.reason)
            is PlaintextRecordLoadResult.EncryptionUnavailable ->
                return KnowledgeGraphProjectionManifestLoadResult.EncryptionUnavailable(
                    loaded.category
                )
            is PlaintextRecordLoadResult.Failed ->
                return KnowledgeGraphProjectionManifestLoadResult.Failed(
                    loaded.reason,
                    loaded.throwable
                )
            is PlaintextRecordLoadResult.Loaded -> loaded.record
        }

        return when (val decoded = KnowledgeGraphProjectionCodec.decodeManifest(record)) {
            is KnowledgeGraphProjectionManifestDecodeResult.Decoded ->
                KnowledgeGraphProjectionManifestLoadResult.Loaded(decoded.manifest)
            KnowledgeGraphProjectionManifestDecodeResult.Corrupt ->
                KnowledgeGraphProjectionManifestLoadResult.Corrupt
            is KnowledgeGraphProjectionManifestDecodeResult.Incompatible ->
                KnowledgeGraphProjectionManifestLoadResult.Incompatible(decoded.reason)
        }
    }

    override fun readFragment(
        buildEpoch: String,
        reference: SemanticClaimVersionReference
    ): KnowledgeGraphProjectionFragmentLoadResult {
        val entityId = KnowledgeGraphProjectionCodec.fragmentEntityId(buildEpoch, reference)
        val record = when (val loaded = openPlaintextRecord(entityId)) {
            PlaintextRecordLoadResult.Missing ->
                return KnowledgeGraphProjectionFragmentLoadResult.Missing
            PlaintextRecordLoadResult.Corrupt ->
                return KnowledgeGraphProjectionFragmentLoadResult.Corrupt
            is PlaintextRecordLoadResult.Incompatible ->
                return KnowledgeGraphProjectionFragmentLoadResult.Incompatible(loaded.reason)
            is PlaintextRecordLoadResult.EncryptionUnavailable ->
                return KnowledgeGraphProjectionFragmentLoadResult.EncryptionUnavailable(
                    loaded.category
                )
            is PlaintextRecordLoadResult.Failed ->
                return KnowledgeGraphProjectionFragmentLoadResult.Failed(
                    loaded.reason,
                    loaded.throwable
                )
            is PlaintextRecordLoadResult.Loaded -> loaded.record
        }

        return when (val decoded = KnowledgeGraphProjectionCodec.decodeFragment(record)) {
            is KnowledgeGraphProjectionFragmentDecodeResult.Decoded ->
                if (
                    decoded.fragment.buildEpoch == buildEpoch &&
                    decoded.fragment.sourceClaim == reference
                ) {
                    KnowledgeGraphProjectionFragmentLoadResult.Loaded(decoded.fragment)
                } else {
                    KnowledgeGraphProjectionFragmentLoadResult.Corrupt
                }
            KnowledgeGraphProjectionFragmentDecodeResult.Corrupt ->
                KnowledgeGraphProjectionFragmentLoadResult.Corrupt
            is KnowledgeGraphProjectionFragmentDecodeResult.Incompatible ->
                KnowledgeGraphProjectionFragmentLoadResult.Incompatible(decoded.reason)
        }
    }

    override fun writeManifest(
        manifest: KnowledgeGraphProjectionManifest
    ): KnowledgeGraphProjectionWriteResult =
        writeRecord(KnowledgeGraphProjectionCodec.encodeManifest(manifest))

    override fun writeFragment(
        fragment: KnowledgeGraphStoredFragment
    ): KnowledgeGraphProjectionWriteResult =
        writeRecord(KnowledgeGraphProjectionCodec.encodeFragment(fragment))

    private fun writeRecord(record: PersistentRecord): KnowledgeGraphProjectionWriteResult {
        val existing = when (val inspected = encryptedStore.inspectResult(record.id)) {
            PersistentRecordLookupResult.Missing -> null
            is PersistentRecordLookupResult.Found -> inspected.snapshot
            PersistentRecordLookupResult.Corrupt ->
                return KnowledgeGraphProjectionWriteResult.Failed(
                    "knowledge graph projection persistence is corrupt"
                )
            is PersistentRecordLookupResult.Incompatible ->
                return KnowledgeGraphProjectionWriteResult.Failed(inspected.reason)
            is PersistentRecordLookupResult.Failed ->
                return KnowledgeGraphProjectionWriteResult.Failed(
                    inspected.reason,
                    inspected.throwable
                )
        }

        val bytes = record.payload.copyBytes()
        val result = try {
            val draft = CognitivePersistentRecordDraft(
                id = record.id,
                schemaId = record.schemaId,
                schemaVersion = record.schemaVersion,
                plaintext = CognitivePlaintext(bytes),
                createdAt = record.createdAt,
                dek = activeDek
            )
            if (existing == null) {
                encryptedStore.install(draft)
            } else {
                encryptedStore.transitionExact(
                    sourceId = existing.record.id,
                    sourceGeneration = existing.generation,
                    replacement = draft
                )
            }
        } finally {
            bytes.fill(0)
        }

        return when (result) {
            is CognitiveEncryptionResult.Success ->
                KnowledgeGraphProjectionWriteResult.Written
            is CognitiveEncryptionResult.Rejected ->
                KnowledgeGraphProjectionWriteResult.Rejected(
                    "knowledge graph projection write rejected: " + result.category
                )
            is CognitiveEncryptionResult.Failed ->
                KnowledgeGraphProjectionWriteResult.Failed(
                    "knowledge graph projection write failed: " + result.category,
                    result.throwable
                )
        }
    }

    private sealed interface PlaintextRecordLoadResult {
        data object Missing : PlaintextRecordLoadResult
        data class Loaded(val record: PersistentRecord) : PlaintextRecordLoadResult
        data object Corrupt : PlaintextRecordLoadResult
        data class Incompatible(val reason: String) : PlaintextRecordLoadResult
        data class EncryptionUnavailable(val category: String) : PlaintextRecordLoadResult
        data class Failed(val reason: String, val throwable: Throwable? = null) :
            PlaintextRecordLoadResult
    }

    private fun openPlaintextRecord(id: PersistentEntityId): PlaintextRecordLoadResult {
        val snapshot = when (val inspected = encryptedStore.inspectResult(id)) {
            PersistentRecordLookupResult.Missing -> return PlaintextRecordLoadResult.Missing
            is PersistentRecordLookupResult.Found -> inspected.snapshot
            PersistentRecordLookupResult.Corrupt -> return PlaintextRecordLoadResult.Corrupt
            is PersistentRecordLookupResult.Incompatible ->
                return PlaintextRecordLoadResult.Incompatible(inspected.reason)
            is PersistentRecordLookupResult.Failed ->
                return PlaintextRecordLoadResult.Failed(
                    inspected.reason,
                    inspected.throwable
                )
        }

        val plaintext = when (val opened = encryptedStore.open(id)) {
            is CognitiveEncryptionResult.Success -> opened.value
            is CognitiveEncryptionResult.Rejected ->
                return PlaintextRecordLoadResult.EncryptionUnavailable(
                    opened.category.toString()
                )
            is CognitiveEncryptionResult.Failed ->
                return PlaintextRecordLoadResult.Failed(
                    "knowledge graph projection decryption failed: " + opened.category,
                    opened.throwable
                )
        }

        val bytes = plaintext.copyBytes()
        return try {
            PlaintextRecordLoadResult.Loaded(
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
    }

    companion object {
        fun open(
            encryptedStore: EncryptedPersistentRecordStore,
            activeDek: CognitiveDekReference
        ): KnowledgeGraphProjectionStoreOpenResult =
            if (!encryptedStore.supportsIndexedLazyMode()) {
                KnowledgeGraphProjectionStoreOpenResult.Incompatible(
                    "knowledge graph projection requires indexed lazy persistence"
                )
            } else {
                KnowledgeGraphProjectionStoreOpenResult.Opened(
                    EncryptedPersistentKnowledgeGraphProjectionStore(
                        encryptedStore,
                        activeDek
                    )
                )
            }
    }
}
