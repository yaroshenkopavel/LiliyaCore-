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

sealed interface SemanticClaimLexicalIndexOpenResult {
    data class Opened(
        val store: EncryptedPersistentSemanticClaimLexicalIndexStore
    ) : SemanticClaimLexicalIndexOpenResult

    data class Incompatible(val reason: String) :
        SemanticClaimLexicalIndexOpenResult
}

internal sealed interface SemanticClaimLexicalManifestLoadResult {
    data object Missing : SemanticClaimLexicalManifestLoadResult
    data class Loaded(val manifest: SemanticClaimLexicalManifest) :
        SemanticClaimLexicalManifestLoadResult
    data object Corrupt : SemanticClaimLexicalManifestLoadResult
    data class Incompatible(val reason: String) :
        SemanticClaimLexicalManifestLoadResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) :
        SemanticClaimLexicalManifestLoadResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticClaimLexicalManifestLoadResult
}

internal sealed interface SemanticClaimLexicalRootLoadResult {
    data object Missing : SemanticClaimLexicalRootLoadResult
    data class Loaded(val root: SemanticClaimLexicalTokenRoot) :
        SemanticClaimLexicalRootLoadResult
    data object Corrupt : SemanticClaimLexicalRootLoadResult
    data class Incompatible(val reason: String) :
        SemanticClaimLexicalRootLoadResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) :
        SemanticClaimLexicalRootLoadResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticClaimLexicalRootLoadResult
}

internal sealed interface SemanticClaimLexicalPageLoadResult {
    data object Missing : SemanticClaimLexicalPageLoadResult
    data class Loaded(val page: SemanticClaimLexicalPostingPage) :
        SemanticClaimLexicalPageLoadResult
    data object Corrupt : SemanticClaimLexicalPageLoadResult
    data class Incompatible(val reason: String) :
        SemanticClaimLexicalPageLoadResult
    data class EncryptionUnavailable(val category: CognitiveEncryptionFailureCategory) :
        SemanticClaimLexicalPageLoadResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticClaimLexicalPageLoadResult
}

internal sealed interface SemanticClaimLexicalWriteResult {
    data object Written : SemanticClaimLexicalWriteResult
    data class Rejected(val reason: String) : SemanticClaimLexicalWriteResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticClaimLexicalWriteResult
}

class EncryptedPersistentSemanticClaimLexicalIndexStore private constructor(
    private val encryptedStore: EncryptedPersistentRecordStore,
    private val activeDek: CognitiveDekReference
) {
    internal fun readManifest(): SemanticClaimLexicalManifestLoadResult {
        val record = when (
            val loaded = openPlaintextRecord(SemanticClaimLexicalIndexCodec.manifestEntityId)
        ) {
            PlaintextRecordLoadResult.Missing ->
                return SemanticClaimLexicalManifestLoadResult.Missing
            PlaintextRecordLoadResult.Corrupt ->
                return SemanticClaimLexicalManifestLoadResult.Corrupt
            is PlaintextRecordLoadResult.Incompatible ->
                return SemanticClaimLexicalManifestLoadResult.Incompatible(loaded.reason)
            is PlaintextRecordLoadResult.EncryptionUnavailable ->
                return SemanticClaimLexicalManifestLoadResult.EncryptionUnavailable(loaded.category)
            is PlaintextRecordLoadResult.Failed ->
                return SemanticClaimLexicalManifestLoadResult.Failed(
                    loaded.reason,
                    loaded.throwable
                )
            is PlaintextRecordLoadResult.Loaded -> loaded.record
        }
        return when (val decoded = SemanticClaimLexicalIndexCodec.decodeManifest(record)) {
            is SemanticClaimLexicalManifestDecodeResult.Decoded ->
                SemanticClaimLexicalManifestLoadResult.Loaded(decoded.manifest)
            SemanticClaimLexicalManifestDecodeResult.Corrupt ->
                SemanticClaimLexicalManifestLoadResult.Corrupt
            is SemanticClaimLexicalManifestDecodeResult.Incompatible ->
                SemanticClaimLexicalManifestLoadResult.Incompatible(decoded.reason)
        }
    }

    internal fun writeManifest(
        manifest: SemanticClaimLexicalManifest
    ): SemanticClaimLexicalWriteResult =
        writeRecord(SemanticClaimLexicalIndexCodec.encodeManifest(manifest))

    internal fun readRoot(token: String): SemanticClaimLexicalRootLoadResult {
        val id = SemanticClaimLexicalIndexCodec.rootEntityId(token)
        val record = when (val loaded = openPlaintextRecord(id)) {
            PlaintextRecordLoadResult.Missing ->
                return SemanticClaimLexicalRootLoadResult.Missing
            PlaintextRecordLoadResult.Corrupt ->
                return SemanticClaimLexicalRootLoadResult.Corrupt
            is PlaintextRecordLoadResult.Incompatible ->
                return SemanticClaimLexicalRootLoadResult.Incompatible(loaded.reason)
            is PlaintextRecordLoadResult.EncryptionUnavailable ->
                return SemanticClaimLexicalRootLoadResult.EncryptionUnavailable(loaded.category)
            is PlaintextRecordLoadResult.Failed ->
                return SemanticClaimLexicalRootLoadResult.Failed(
                    loaded.reason,
                    loaded.throwable
                )
            is PlaintextRecordLoadResult.Loaded -> loaded.record
        }
        return when (val decoded = SemanticClaimLexicalIndexCodec.decodeRoot(record)) {
            is SemanticClaimLexicalRootDecodeResult.Decoded ->
                if (decoded.root.token == token) {
                    SemanticClaimLexicalRootLoadResult.Loaded(decoded.root)
                } else {
                    SemanticClaimLexicalRootLoadResult.Corrupt
                }
            SemanticClaimLexicalRootDecodeResult.Corrupt ->
                SemanticClaimLexicalRootLoadResult.Corrupt
            is SemanticClaimLexicalRootDecodeResult.Incompatible ->
                SemanticClaimLexicalRootLoadResult.Incompatible(decoded.reason)
        }
    }

    internal fun writeRoot(
        root: SemanticClaimLexicalTokenRoot
    ): SemanticClaimLexicalWriteResult =
        writeRecord(SemanticClaimLexicalIndexCodec.encodeRoot(root))

    internal fun readPage(
        token: String,
        ordinal: Long
    ): SemanticClaimLexicalPageLoadResult {
        val id = SemanticClaimLexicalIndexCodec.pageEntityId(token, ordinal)
        val record = when (val loaded = openPlaintextRecord(id)) {
            PlaintextRecordLoadResult.Missing ->
                return SemanticClaimLexicalPageLoadResult.Missing
            PlaintextRecordLoadResult.Corrupt ->
                return SemanticClaimLexicalPageLoadResult.Corrupt
            is PlaintextRecordLoadResult.Incompatible ->
                return SemanticClaimLexicalPageLoadResult.Incompatible(loaded.reason)
            is PlaintextRecordLoadResult.EncryptionUnavailable ->
                return SemanticClaimLexicalPageLoadResult.EncryptionUnavailable(loaded.category)
            is PlaintextRecordLoadResult.Failed ->
                return SemanticClaimLexicalPageLoadResult.Failed(
                    loaded.reason,
                    loaded.throwable
                )
            is PlaintextRecordLoadResult.Loaded -> loaded.record
        }
        return when (val decoded = SemanticClaimLexicalIndexCodec.decodePage(record)) {
            is SemanticClaimLexicalPageDecodeResult.Decoded ->
                if (decoded.page.token == token && decoded.page.ordinal == ordinal) {
                    SemanticClaimLexicalPageLoadResult.Loaded(decoded.page)
                } else {
                    SemanticClaimLexicalPageLoadResult.Corrupt
                }
            SemanticClaimLexicalPageDecodeResult.Corrupt ->
                SemanticClaimLexicalPageLoadResult.Corrupt
            is SemanticClaimLexicalPageDecodeResult.Incompatible ->
                SemanticClaimLexicalPageLoadResult.Incompatible(decoded.reason)
        }
    }

    internal fun writePage(
        page: SemanticClaimLexicalPostingPage
    ): SemanticClaimLexicalWriteResult =
        writeRecord(SemanticClaimLexicalIndexCodec.encodePage(page))

    private fun writeRecord(record: PersistentRecord): SemanticClaimLexicalWriteResult {
        val existing = when (val inspected = encryptedStore.inspectResult(record.id)) {
            PersistentRecordLookupResult.Missing -> null
            is PersistentRecordLookupResult.Found -> inspected.snapshot
            PersistentRecordLookupResult.Corrupt ->
                return SemanticClaimLexicalWriteResult.Failed(
                    "semantic lexical persistence is corrupt"
                )
            is PersistentRecordLookupResult.Incompatible ->
                return SemanticClaimLexicalWriteResult.Failed(inspected.reason)
            is PersistentRecordLookupResult.Failed ->
                return SemanticClaimLexicalWriteResult.Failed(
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
            is CognitiveEncryptionResult.Success -> SemanticClaimLexicalWriteResult.Written
            is CognitiveEncryptionResult.Rejected ->
                SemanticClaimLexicalWriteResult.Rejected(
                    "semantic lexical index write rejected: " + result.category
                )
            is CognitiveEncryptionResult.Failed ->
                SemanticClaimLexicalWriteResult.Failed(
                    "semantic lexical index write failed: " + result.category,
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
                    "semantic lexical index decryption failed: " + opened.category,
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
        ): SemanticClaimLexicalIndexOpenResult =
            if (!encryptedStore.supportsIndexedLazyMode()) {
                SemanticClaimLexicalIndexOpenResult.Incompatible(
                    "semantic lexical index requires indexed lazy persistence"
                )
            } else {
                SemanticClaimLexicalIndexOpenResult.Opened(
                    EncryptedPersistentSemanticClaimLexicalIndexStore(
                        encryptedStore,
                        activeDek
                    )
                )
            }
    }
}
