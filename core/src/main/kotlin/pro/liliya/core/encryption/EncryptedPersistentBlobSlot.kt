package pro.liliya.core.encryption

import java.time.Instant
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentMutationResult
import pro.liliya.core.persistence.PersistentRecordLookupResult
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion

class EncryptedPersistentBlob(bytes: ByteArray) {
    private val value = bytes.copyOf()

    fun copyBytes(): ByteArray = value.copyOf()
    val size: Int get() = value.size

    override fun toString(): String =
        "EncryptedPersistentBlob(<redacted:" + value.size + " bytes>)"
}

sealed interface EncryptedPersistentBlobReadResult {
    data object Missing : EncryptedPersistentBlobReadResult
    data class Found(val blob: EncryptedPersistentBlob) : EncryptedPersistentBlobReadResult
    data object Corrupt : EncryptedPersistentBlobReadResult
    data class Incompatible(val reason: String) : EncryptedPersistentBlobReadResult
    data class EncryptionUnavailable(
        val category: CognitiveEncryptionFailureCategory,
        val throwable: Throwable? = null
    ) : EncryptedPersistentBlobReadResult {
        override fun toString(): String =
            "EncryptionUnavailable(category=$category, throwable=" +
                (throwable?.javaClass?.name ?: "null") + ")"
    }
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : EncryptedPersistentBlobReadResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=" +
                (throwable?.javaClass?.name ?: "null") + ")"
    }
}

sealed interface EncryptedPersistentBlobWriteResult {
    data object Written : EncryptedPersistentBlobWriteResult
    data class Rejected(val reason: String) : EncryptedPersistentBlobWriteResult
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : EncryptedPersistentBlobWriteResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=" +
                (throwable?.javaClass?.name ?: "null") + ")"
    }
}

sealed interface EncryptedPersistentBlobDeleteResult {
    data object Deleted : EncryptedPersistentBlobDeleteResult
    data object Missing : EncryptedPersistentBlobDeleteResult
    data class Rejected(val reason: String) : EncryptedPersistentBlobDeleteResult
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : EncryptedPersistentBlobDeleteResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=" +
                (throwable?.javaClass?.name ?: "null") + ")"
    }
}

/**
 * One fixed encrypted opaque blob over the reviewed record-level cognitive encryption boundary.
 *
 * The slot has no truth/authority semantics. It is intended for rebuildable derived state such as
 * semantic checkpoints. Exact replacement is atomic at the PersistentRecordStore mutation boundary.
 */
class EncryptedPersistentBlobSlot(
    private val encryptedStore: EncryptedPersistentRecordStore,
    private val entityId: PersistentEntityId,
    private val schemaId: PersistentSchemaId,
    private val schemaVersion: PersistentSchemaVersion,
    private val activeDek: CognitiveDekReference,
    private val maxBytes: Int
) {
    init {
        require(maxBytes > 0)
    }

    fun read(): EncryptedPersistentBlobReadResult {
        val snapshot = when (val inspected = encryptedStore.inspectResult(entityId)) {
            PersistentRecordLookupResult.Missing ->
                return EncryptedPersistentBlobReadResult.Missing
            is PersistentRecordLookupResult.Found -> inspected.snapshot
            PersistentRecordLookupResult.Corrupt ->
                return EncryptedPersistentBlobReadResult.Corrupt
            is PersistentRecordLookupResult.Incompatible ->
                return EncryptedPersistentBlobReadResult.Incompatible(inspected.reason)
            is PersistentRecordLookupResult.Failed ->
                return EncryptedPersistentBlobReadResult.Failed(
                    inspected.reason,
                    inspected.throwable
                )
        }
        if (
            snapshot.record.id != entityId ||
            snapshot.record.schemaId != schemaId ||
            snapshot.record.schemaVersion != schemaVersion
        ) {
            return EncryptedPersistentBlobReadResult.Incompatible(
                "encrypted blob slot schema identity mismatch"
            )
        }

        val opened = when (val result = encryptedStore.open(snapshot)) {
            is CognitiveEncryptionResult.Success -> result.value
            is CognitiveEncryptionResult.Rejected ->
                return EncryptedPersistentBlobReadResult.EncryptionUnavailable(
                    result.category
                )
            is CognitiveEncryptionResult.Failed ->
                return EncryptedPersistentBlobReadResult.EncryptionUnavailable(
                    result.category,
                    result.throwable
                )
        }
        if (opened.size > maxBytes) {
            return EncryptedPersistentBlobReadResult.Corrupt
        }
        val bytes = opened.copyBytes()
        return try {
            EncryptedPersistentBlobReadResult.Found(
                EncryptedPersistentBlob(bytes)
            )
        } finally {
            bytes.fill(0)
        }
    }

    @Synchronized
    fun write(
        blob: EncryptedPersistentBlob,
        createdAt: Instant
    ): EncryptedPersistentBlobWriteResult {
        if (blob.size > maxBytes) {
            return EncryptedPersistentBlobWriteResult.Rejected(
                "encrypted blob exceeds configured size bound"
            )
        }
        val bytes = blob.copyBytes()
        val draft = try {
            CognitivePersistentRecordDraft(
                id = entityId,
                schemaId = schemaId,
                schemaVersion = schemaVersion,
                plaintext = CognitivePlaintext(bytes),
                createdAt = createdAt,
                dek = activeDek
            )
        } finally {
            bytes.fill(0)
        }

        return when (val inspected = encryptedStore.inspectResult(entityId)) {
            PersistentRecordLookupResult.Missing ->
                mapWrite(encryptedStore.install(draft))
            is PersistentRecordLookupResult.Found -> {
                val snapshot = inspected.snapshot
                if (
                    snapshot.record.schemaId != schemaId ||
                    snapshot.record.schemaVersion != schemaVersion
                ) {
                    EncryptedPersistentBlobWriteResult.Rejected(
                        "encrypted blob slot schema identity mismatch"
                    )
                } else {
                    mapWrite(
                        encryptedStore.transitionExact(
                            sourceId = entityId,
                            sourceGeneration = snapshot.generation,
                            replacement = draft
                        )
                    )
                }
            }
            PersistentRecordLookupResult.Corrupt ->
                EncryptedPersistentBlobWriteResult.Failed(
                    "encrypted blob slot current record is corrupt"
                )
            is PersistentRecordLookupResult.Incompatible ->
                EncryptedPersistentBlobWriteResult.Rejected(inspected.reason)
            is PersistentRecordLookupResult.Failed ->
                EncryptedPersistentBlobWriteResult.Failed(
                    inspected.reason,
                    inspected.throwable
                )
        }
    }

    @Synchronized
    fun delete(): EncryptedPersistentBlobDeleteResult {
        val snapshot = when (val inspected = encryptedStore.inspectResult(entityId)) {
            PersistentRecordLookupResult.Missing ->
                return EncryptedPersistentBlobDeleteResult.Missing
            is PersistentRecordLookupResult.Found -> inspected.snapshot
            PersistentRecordLookupResult.Corrupt ->
                return EncryptedPersistentBlobDeleteResult.Failed(
                    "encrypted blob slot current record is corrupt"
                )
            is PersistentRecordLookupResult.Incompatible ->
                return EncryptedPersistentBlobDeleteResult.Rejected(inspected.reason)
            is PersistentRecordLookupResult.Failed ->
                return EncryptedPersistentBlobDeleteResult.Failed(
                    inspected.reason,
                    inspected.throwable
                )
        }

        if (
            snapshot.record.id != entityId ||
            snapshot.record.schemaId != schemaId ||
            snapshot.record.schemaVersion != schemaVersion
        ) {
            return EncryptedPersistentBlobDeleteResult.Rejected(
                "encrypted blob slot schema identity mismatch"
            )
        }

        return when (val removed = encryptedStore.removeExact(entityId, snapshot.generation)) {
            PersistentMutationResult.Committed ->
                EncryptedPersistentBlobDeleteResult.Deleted
            is PersistentMutationResult.Rejected ->
                EncryptedPersistentBlobDeleteResult.Rejected(removed.reason)
            is PersistentMutationResult.Failed ->
                EncryptedPersistentBlobDeleteResult.Failed(
                    removed.reason,
                    removed.throwable
                )
        }
    }

    private fun mapWrite(
        result: CognitiveEncryptionResult<pro.liliya.core.persistence.PersistentRecordOwnership>
    ): EncryptedPersistentBlobWriteResult =
        when (result) {
            is CognitiveEncryptionResult.Success ->
                EncryptedPersistentBlobWriteResult.Written
            is CognitiveEncryptionResult.Rejected ->
                EncryptedPersistentBlobWriteResult.Rejected(
                    "encrypted blob write rejected: " + result.category
                )
            is CognitiveEncryptionResult.Failed ->
                EncryptedPersistentBlobWriteResult.Failed(
                    "encrypted blob write failed: " + result.category,
                    result.throwable
                )
        }
}
