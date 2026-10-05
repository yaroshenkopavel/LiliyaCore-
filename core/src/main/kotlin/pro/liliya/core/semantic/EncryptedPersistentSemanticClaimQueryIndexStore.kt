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

sealed interface SemanticClaimQueryIndexOpenResult {
    data class Opened(
        val store: EncryptedPersistentSemanticClaimQueryIndexStore
    ) : SemanticClaimQueryIndexOpenResult
    data class Incompatible(val reason: String) : SemanticClaimQueryIndexOpenResult
}

internal sealed interface SemanticClaimQueryManifestLoadResult {
    data object Missing : SemanticClaimQueryManifestLoadResult
    data class Loaded(val manifest: SemanticClaimQueryIndexManifest) :
        SemanticClaimQueryManifestLoadResult
    data object Corrupt : SemanticClaimQueryManifestLoadResult
    data class Incompatible(val reason: String) : SemanticClaimQueryManifestLoadResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) :
        SemanticClaimQueryManifestLoadResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticClaimQueryManifestLoadResult
}

internal sealed interface SemanticClaimQueryRootLoadResult {
    data object Missing : SemanticClaimQueryRootLoadResult
    data class Loaded(val root: SemanticClaimQueryGroupRoot) :
        SemanticClaimQueryRootLoadResult
    data object Corrupt : SemanticClaimQueryRootLoadResult
    data class Incompatible(val reason: String) : SemanticClaimQueryRootLoadResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) :
        SemanticClaimQueryRootLoadResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticClaimQueryRootLoadResult
}

internal sealed interface SemanticClaimQueryPageLoadResult {
    data object Missing : SemanticClaimQueryPageLoadResult
    data class Loaded(val page: SemanticClaimQueryPage) :
        SemanticClaimQueryPageLoadResult
    data object Corrupt : SemanticClaimQueryPageLoadResult
    data class Incompatible(val reason: String) : SemanticClaimQueryPageLoadResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) :
        SemanticClaimQueryPageLoadResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticClaimQueryPageLoadResult
}

internal sealed interface SemanticClaimQueryIndexWriteResult {
    data object Written : SemanticClaimQueryIndexWriteResult
    data class Rejected(val reason: String) : SemanticClaimQueryIndexWriteResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticClaimQueryIndexWriteResult
}

class EncryptedPersistentSemanticClaimQueryIndexStore private constructor(
    private val encryptedStore: EncryptedPersistentRecordStore,
    private val activeDek: CognitiveDekReference
) {
    internal fun readManifest(): SemanticClaimQueryManifestLoadResult {
        val record = when (
            val loaded = openPlaintextRecord(SemanticClaimQueryIndexCodec.manifestEntityId)
        ) {
            PlaintextRecordLoadResult.Missing -> return SemanticClaimQueryManifestLoadResult.Missing
            PlaintextRecordLoadResult.Corrupt -> return SemanticClaimQueryManifestLoadResult.Corrupt
            is PlaintextRecordLoadResult.Incompatible ->
                return SemanticClaimQueryManifestLoadResult.Incompatible(loaded.reason)
            is PlaintextRecordLoadResult.EncryptionUnavailable ->
                return SemanticClaimQueryManifestLoadResult.EncryptionUnavailable(loaded.category)
            is PlaintextRecordLoadResult.Failed ->
                return SemanticClaimQueryManifestLoadResult.Failed(loaded.reason, loaded.throwable)
            is PlaintextRecordLoadResult.Loaded -> loaded.record
        }
        return when (val decoded = SemanticClaimQueryIndexCodec.decodeManifest(record)) {
            is SemanticClaimQueryManifestDecodeResult.Decoded ->
                SemanticClaimQueryManifestLoadResult.Loaded(decoded.manifest)
            SemanticClaimQueryManifestDecodeResult.Corrupt ->
                SemanticClaimQueryManifestLoadResult.Corrupt
            is SemanticClaimQueryManifestDecodeResult.Incompatible ->
                SemanticClaimQueryManifestLoadResult.Incompatible(decoded.reason)
        }
    }

    internal fun writeManifest(
        manifest: SemanticClaimQueryIndexManifest
    ): SemanticClaimQueryIndexWriteResult =
        writeRecord(SemanticClaimQueryIndexCodec.encodeManifest(manifest))

    internal fun readRoot(
        conflictGroupId: SemanticClaimConflictGroupId
    ): SemanticClaimQueryRootLoadResult {
        val id = SemanticClaimQueryIndexCodec.rootEntityId(conflictGroupId)
        val record = when (val loaded = openPlaintextRecord(id)) {
            PlaintextRecordLoadResult.Missing -> return SemanticClaimQueryRootLoadResult.Missing
            PlaintextRecordLoadResult.Corrupt -> return SemanticClaimQueryRootLoadResult.Corrupt
            is PlaintextRecordLoadResult.Incompatible ->
                return SemanticClaimQueryRootLoadResult.Incompatible(loaded.reason)
            is PlaintextRecordLoadResult.EncryptionUnavailable ->
                return SemanticClaimQueryRootLoadResult.EncryptionUnavailable(loaded.category)
            is PlaintextRecordLoadResult.Failed ->
                return SemanticClaimQueryRootLoadResult.Failed(loaded.reason, loaded.throwable)
            is PlaintextRecordLoadResult.Loaded -> loaded.record
        }
        return when (val decoded = SemanticClaimQueryIndexCodec.decodeRoot(record)) {
            is SemanticClaimQueryRootDecodeResult.Decoded ->
                if (decoded.root.conflictGroupId == conflictGroupId) {
                    SemanticClaimQueryRootLoadResult.Loaded(decoded.root)
                } else {
                    SemanticClaimQueryRootLoadResult.Corrupt
                }
            SemanticClaimQueryRootDecodeResult.Corrupt ->
                SemanticClaimQueryRootLoadResult.Corrupt
            is SemanticClaimQueryRootDecodeResult.Incompatible ->
                SemanticClaimQueryRootLoadResult.Incompatible(decoded.reason)
        }
    }

    internal fun writeRoot(
        root: SemanticClaimQueryGroupRoot
    ): SemanticClaimQueryIndexWriteResult =
        writeRecord(SemanticClaimQueryIndexCodec.encodeRoot(root))

    internal fun readPage(
        conflictGroupId: SemanticClaimConflictGroupId,
        ordinal: Long
    ): SemanticClaimQueryPageLoadResult {
        val id = SemanticClaimQueryIndexCodec.pageEntityId(conflictGroupId, ordinal)
        val record = when (val loaded = openPlaintextRecord(id)) {
            PlaintextRecordLoadResult.Missing -> return SemanticClaimQueryPageLoadResult.Missing
            PlaintextRecordLoadResult.Corrupt -> return SemanticClaimQueryPageLoadResult.Corrupt
            is PlaintextRecordLoadResult.Incompatible ->
                return SemanticClaimQueryPageLoadResult.Incompatible(loaded.reason)
            is PlaintextRecordLoadResult.EncryptionUnavailable ->
                return SemanticClaimQueryPageLoadResult.EncryptionUnavailable(loaded.category)
            is PlaintextRecordLoadResult.Failed ->
                return SemanticClaimQueryPageLoadResult.Failed(loaded.reason, loaded.throwable)
            is PlaintextRecordLoadResult.Loaded -> loaded.record
        }
        return when (val decoded = SemanticClaimQueryIndexCodec.decodePage(record)) {
            is SemanticClaimQueryPageDecodeResult.Decoded ->
                if (
                    decoded.page.conflictGroupId == conflictGroupId &&
                    decoded.page.ordinal == ordinal
                ) {
                    SemanticClaimQueryPageLoadResult.Loaded(decoded.page)
                } else {
                    SemanticClaimQueryPageLoadResult.Corrupt
                }
            SemanticClaimQueryPageDecodeResult.Corrupt ->
                SemanticClaimQueryPageLoadResult.Corrupt
            is SemanticClaimQueryPageDecodeResult.Incompatible ->
                SemanticClaimQueryPageLoadResult.Incompatible(decoded.reason)
        }
    }

    internal fun writePage(
        page: SemanticClaimQueryPage
    ): SemanticClaimQueryIndexWriteResult =
        writeRecord(SemanticClaimQueryIndexCodec.encodePage(page))

    private fun writeRecord(
        record: PersistentRecord
    ): SemanticClaimQueryIndexWriteResult {
        val existing = when (val inspected = encryptedStore.inspectResult(record.id)) {
            PersistentRecordLookupResult.Missing -> null
            is PersistentRecordLookupResult.Found -> inspected.snapshot
            PersistentRecordLookupResult.Corrupt ->
                return SemanticClaimQueryIndexWriteResult.Failed(
                    "semantic claim query index persistence is corrupt"
                )
            is PersistentRecordLookupResult.Incompatible ->
                return SemanticClaimQueryIndexWriteResult.Failed(inspected.reason)
            is PersistentRecordLookupResult.Failed ->
                return SemanticClaimQueryIndexWriteResult.Failed(
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
            is CognitiveEncryptionResult.Success -> SemanticClaimQueryIndexWriteResult.Written
            is CognitiveEncryptionResult.Rejected ->
                SemanticClaimQueryIndexWriteResult.Rejected(
                    "semantic claim query index write rejected: " + result.category
                )
            is CognitiveEncryptionResult.Failed ->
                SemanticClaimQueryIndexWriteResult.Failed(
                    "semantic claim query index write failed: " + result.category,
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
                return PlaintextRecordLoadResult.Failed(
                    inspected.reason,
                    inspected.throwable
                )
        }

        val plaintext = when (val opened = encryptedStore.open(id)) {
            is CognitiveEncryptionResult.Success -> opened.value
            is CognitiveEncryptionResult.Rejected ->
                return PlaintextRecordLoadResult.EncryptionUnavailable(opened.category)
            is CognitiveEncryptionResult.Failed ->
                return PlaintextRecordLoadResult.Failed(
                    "semantic claim query index decryption failed: " + opened.category,
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
        ): SemanticClaimQueryIndexOpenResult =
            if (!encryptedStore.supportsIndexedLazyMode()) {
                SemanticClaimQueryIndexOpenResult.Incompatible(
                    "semantic claim query index requires indexed lazy persistence"
                )
            } else {
                SemanticClaimQueryIndexOpenResult.Opened(
                    EncryptedPersistentSemanticClaimQueryIndexStore(
                        encryptedStore,
                        activeDek
                    )
                )
            }
    }
}
