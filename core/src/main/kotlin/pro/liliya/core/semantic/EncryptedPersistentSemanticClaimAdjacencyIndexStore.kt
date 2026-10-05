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

sealed interface SemanticClaimAdjacencyIndexOpenResult {
    data class Opened(val store: EncryptedPersistentSemanticClaimAdjacencyIndexStore) :
        SemanticClaimAdjacencyIndexOpenResult
    data class Incompatible(val reason: String) : SemanticClaimAdjacencyIndexOpenResult
}

internal sealed interface SemanticClaimAdjacencyManifestLoadResult {
    data object Missing : SemanticClaimAdjacencyManifestLoadResult
    data class Loaded(val manifest: SemanticClaimAdjacencyManifest) :
        SemanticClaimAdjacencyManifestLoadResult
    data object Corrupt : SemanticClaimAdjacencyManifestLoadResult
    data class Incompatible(val reason: String) : SemanticClaimAdjacencyManifestLoadResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) :
        SemanticClaimAdjacencyManifestLoadResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticClaimAdjacencyManifestLoadResult
}

internal sealed interface SemanticClaimAdjacencyRootLoadResult {
    data object Missing : SemanticClaimAdjacencyRootLoadResult
    data class Loaded(val root: SemanticClaimAdjacencyRoot) :
        SemanticClaimAdjacencyRootLoadResult
    data object Corrupt : SemanticClaimAdjacencyRootLoadResult
    data class Incompatible(val reason: String) : SemanticClaimAdjacencyRootLoadResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) :
        SemanticClaimAdjacencyRootLoadResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticClaimAdjacencyRootLoadResult
}

internal sealed interface SemanticClaimAdjacencyPageLoadResult {
    data object Missing : SemanticClaimAdjacencyPageLoadResult
    data class Loaded(val page: SemanticClaimAdjacencyPage) :
        SemanticClaimAdjacencyPageLoadResult
    data object Corrupt : SemanticClaimAdjacencyPageLoadResult
    data class Incompatible(val reason: String) : SemanticClaimAdjacencyPageLoadResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) :
        SemanticClaimAdjacencyPageLoadResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticClaimAdjacencyPageLoadResult
}

internal sealed interface SemanticClaimAdjacencyWriteResult {
    data object Written : SemanticClaimAdjacencyWriteResult
    data class Rejected(val reason: String) : SemanticClaimAdjacencyWriteResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticClaimAdjacencyWriteResult
}

class EncryptedPersistentSemanticClaimAdjacencyIndexStore private constructor(
    private val encryptedStore: EncryptedPersistentRecordStore,
    private val activeDek: CognitiveDekReference
) {
    internal fun readManifest(): SemanticClaimAdjacencyManifestLoadResult {
        val record = when (val loaded = openPlaintextRecord(SemanticClaimAdjacencyIndexCodec.manifestEntityId)) {
            PlaintextRecordLoadResult.Missing -> return SemanticClaimAdjacencyManifestLoadResult.Missing
            PlaintextRecordLoadResult.Corrupt -> return SemanticClaimAdjacencyManifestLoadResult.Corrupt
            is PlaintextRecordLoadResult.Incompatible ->
                return SemanticClaimAdjacencyManifestLoadResult.Incompatible(loaded.reason)
            is PlaintextRecordLoadResult.EncryptionUnavailable ->
                return SemanticClaimAdjacencyManifestLoadResult.EncryptionUnavailable(loaded.category)
            is PlaintextRecordLoadResult.Failed ->
                return SemanticClaimAdjacencyManifestLoadResult.Failed(loaded.reason, loaded.throwable)
            is PlaintextRecordLoadResult.Loaded -> loaded.record
        }
        return when (val decoded = SemanticClaimAdjacencyIndexCodec.decodeManifest(record)) {
            is SemanticClaimAdjacencyManifestDecodeResult.Decoded ->
                SemanticClaimAdjacencyManifestLoadResult.Loaded(decoded.manifest)
            SemanticClaimAdjacencyManifestDecodeResult.Corrupt ->
                SemanticClaimAdjacencyManifestLoadResult.Corrupt
            is SemanticClaimAdjacencyManifestDecodeResult.Incompatible ->
                SemanticClaimAdjacencyManifestLoadResult.Incompatible(decoded.reason)
        }
    }

    internal fun writeManifest(manifest: SemanticClaimAdjacencyManifest): SemanticClaimAdjacencyWriteResult =
        writeRecord(SemanticClaimAdjacencyIndexCodec.encodeManifest(manifest))

    internal fun readRoot(reference: SemanticClaimVersionReference): SemanticClaimAdjacencyRootLoadResult {
        val record = when (val loaded = openPlaintextRecord(SemanticClaimAdjacencyIndexCodec.rootEntityId(reference))) {
            PlaintextRecordLoadResult.Missing -> return SemanticClaimAdjacencyRootLoadResult.Missing
            PlaintextRecordLoadResult.Corrupt -> return SemanticClaimAdjacencyRootLoadResult.Corrupt
            is PlaintextRecordLoadResult.Incompatible ->
                return SemanticClaimAdjacencyRootLoadResult.Incompatible(loaded.reason)
            is PlaintextRecordLoadResult.EncryptionUnavailable ->
                return SemanticClaimAdjacencyRootLoadResult.EncryptionUnavailable(loaded.category)
            is PlaintextRecordLoadResult.Failed ->
                return SemanticClaimAdjacencyRootLoadResult.Failed(loaded.reason, loaded.throwable)
            is PlaintextRecordLoadResult.Loaded -> loaded.record
        }
        return when (val decoded = SemanticClaimAdjacencyIndexCodec.decodeRoot(record)) {
            is SemanticClaimAdjacencyRootDecodeResult.Decoded ->
                if (decoded.root.reference == reference) SemanticClaimAdjacencyRootLoadResult.Loaded(decoded.root)
                else SemanticClaimAdjacencyRootLoadResult.Corrupt
            SemanticClaimAdjacencyRootDecodeResult.Corrupt -> SemanticClaimAdjacencyRootLoadResult.Corrupt
            is SemanticClaimAdjacencyRootDecodeResult.Incompatible ->
                SemanticClaimAdjacencyRootLoadResult.Incompatible(decoded.reason)
        }
    }

    internal fun writeRoot(root: SemanticClaimAdjacencyRoot): SemanticClaimAdjacencyWriteResult =
        writeRecord(SemanticClaimAdjacencyIndexCodec.encodeRoot(root))

    internal fun readPage(
        reference: SemanticClaimVersionReference,
        ordinal: Long
    ): SemanticClaimAdjacencyPageLoadResult {
        val record = when (
            val loaded = openPlaintextRecord(SemanticClaimAdjacencyIndexCodec.pageEntityId(reference, ordinal))
        ) {
            PlaintextRecordLoadResult.Missing -> return SemanticClaimAdjacencyPageLoadResult.Missing
            PlaintextRecordLoadResult.Corrupt -> return SemanticClaimAdjacencyPageLoadResult.Corrupt
            is PlaintextRecordLoadResult.Incompatible ->
                return SemanticClaimAdjacencyPageLoadResult.Incompatible(loaded.reason)
            is PlaintextRecordLoadResult.EncryptionUnavailable ->
                return SemanticClaimAdjacencyPageLoadResult.EncryptionUnavailable(loaded.category)
            is PlaintextRecordLoadResult.Failed ->
                return SemanticClaimAdjacencyPageLoadResult.Failed(loaded.reason, loaded.throwable)
            is PlaintextRecordLoadResult.Loaded -> loaded.record
        }
        return when (val decoded = SemanticClaimAdjacencyIndexCodec.decodePage(record)) {
            is SemanticClaimAdjacencyPageDecodeResult.Decoded ->
                if (decoded.page.reference == reference && decoded.page.ordinal == ordinal) {
                    SemanticClaimAdjacencyPageLoadResult.Loaded(decoded.page)
                } else {
                    SemanticClaimAdjacencyPageLoadResult.Corrupt
                }
            SemanticClaimAdjacencyPageDecodeResult.Corrupt -> SemanticClaimAdjacencyPageLoadResult.Corrupt
            is SemanticClaimAdjacencyPageDecodeResult.Incompatible ->
                SemanticClaimAdjacencyPageLoadResult.Incompatible(decoded.reason)
        }
    }

    internal fun writePage(page: SemanticClaimAdjacencyPage): SemanticClaimAdjacencyWriteResult =
        writeRecord(SemanticClaimAdjacencyIndexCodec.encodePage(page))

    private fun writeRecord(record: PersistentRecord): SemanticClaimAdjacencyWriteResult {
        val existing = when (val inspected = encryptedStore.inspectResult(record.id)) {
            PersistentRecordLookupResult.Missing -> null
            is PersistentRecordLookupResult.Found -> inspected.snapshot
            PersistentRecordLookupResult.Corrupt ->
                return SemanticClaimAdjacencyWriteResult.Failed("semantic adjacency persistence is corrupt")
            is PersistentRecordLookupResult.Incompatible ->
                return SemanticClaimAdjacencyWriteResult.Failed(inspected.reason)
            is PersistentRecordLookupResult.Failed ->
                return SemanticClaimAdjacencyWriteResult.Failed(inspected.reason, inspected.throwable)
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
            is CognitiveEncryptionResult.Success -> SemanticClaimAdjacencyWriteResult.Written
            is CognitiveEncryptionResult.Rejected ->
                SemanticClaimAdjacencyWriteResult.Rejected(
                    "semantic adjacency write rejected: " + result.category
                )
            is CognitiveEncryptionResult.Failed ->
                SemanticClaimAdjacencyWriteResult.Failed(
                    "semantic adjacency write failed: " + result.category,
                    result.throwable
                )
        }
    }

    private sealed interface PlaintextRecordLoadResult {
        data object Missing : PlaintextRecordLoadResult
        data class Loaded(val record: PersistentRecord) : PlaintextRecordLoadResult
        data object Corrupt : PlaintextRecordLoadResult
        data class Incompatible(val reason: String) : PlaintextRecordLoadResult
        data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) :
            PlaintextRecordLoadResult
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
                return PlaintextRecordLoadResult.Failed(inspected.reason, inspected.throwable)
        }
        val plaintext = when (val opened = encryptedStore.open(id)) {
            is CognitiveEncryptionResult.Success -> opened.value
            is CognitiveEncryptionResult.Rejected ->
                return PlaintextRecordLoadResult.EncryptionUnavailable(opened.category)
            is CognitiveEncryptionResult.Failed ->
                return PlaintextRecordLoadResult.Failed(
                    "semantic adjacency decryption failed: " + opened.category,
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
        ): SemanticClaimAdjacencyIndexOpenResult =
            if (!encryptedStore.supportsIndexedLazyMode()) {
                SemanticClaimAdjacencyIndexOpenResult.Incompatible(
                    "semantic adjacency index requires indexed lazy persistence"
                )
            } else {
                SemanticClaimAdjacencyIndexOpenResult.Opened(
                    EncryptedPersistentSemanticClaimAdjacencyIndexStore(
                        encryptedStore,
                        activeDek
                    )
                )
            }
    }
}