package pro.liliya.core.memory

import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.encryption.CognitiveEncryptionFailureCategory
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitivePersistentRecordDraft
import pro.liliya.core.encryption.CognitivePlaintext
import pro.liliya.core.encryption.EncryptedPersistentRecordStore
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.persistence.PersistentBackendMetadata
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentMutationResult
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentRecordLookupResult
import pro.liliya.core.persistence.PersistentRecordOwnership

sealed interface EncryptedPersistentMemoryInspectResult {
    data object Missing : EncryptedPersistentMemoryInspectResult
    data class Found(val snapshot: MemoryRecordSnapshot) : EncryptedPersistentMemoryInspectResult
    data object Corrupt : EncryptedPersistentMemoryInspectResult
    data class Incompatible(val reason: String) : EncryptedPersistentMemoryInspectResult
    data class EncryptionUnavailable(
        val category: CognitiveEncryptionFailureCategory,
        val throwable: Throwable? = null
    ) : EncryptedPersistentMemoryInspectResult {
        override fun toString(): String =
            "EncryptionUnavailable(category=$category, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : EncryptedPersistentMemoryInspectResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

sealed interface EncryptedPersistentMemoryOpenResult {
    data class Opened(
        val composition: EncryptedPersistentMemoryComposition
    ) : EncryptedPersistentMemoryOpenResult

    data object Corrupt : EncryptedPersistentMemoryOpenResult
    data class Incompatible(val reason: String) : EncryptedPersistentMemoryOpenResult
    data class EncryptionUnavailable(
        val category: CognitiveEncryptionFailureCategory
    ) : EncryptedPersistentMemoryOpenResult
    data class RestorationFailed(val reason: String) : EncryptedPersistentMemoryOpenResult
}

/**
 * Authoritative Memory composition over the existing record-level encrypted persistence boundary.
 *
 * New records use the explicitly supplied active DEK. Existing records always resolve the exact
 * DEK bound into their own authenticated envelope during reopen.
 */
class EncryptedPersistentMemoryComposition private constructor(
    private val foundation: FoundationComposition,
    private val encryptedStore: EncryptedPersistentRecordStore,
    private val memoryStore: MemoryStore,
    private val activeDek: CognitiveDekReference,
    private val indexedLazyMode: Boolean
) {
    @Synchronized
    fun remember(record: MemoryRecord): PersistentMemoryRememberResult {
        val encoded = MemoryPersistentRecordCodec.encode(record)
        val plaintext = encoded.payload.copyBytes()
        val result = try {
            encryptedStore.install(
                CognitivePersistentRecordDraft(
                    id = encoded.id,
                    schemaId = encoded.schemaId,
                    schemaVersion = encoded.schemaVersion,
                    plaintext = CognitivePlaintext(plaintext),
                    createdAt = encoded.createdAt,
                    dek = activeDek
                )
            )
        } finally {
            plaintext.fill(0)
        }

        return when (result) {
            is CognitiveEncryptionResult.Success ->
                installCommittedMemory(record, result.value)
            is CognitiveEncryptionResult.Rejected ->
                PersistentMemoryRememberResult.Rejected(
                    "encrypted persistent memory rejected: ${result.category}"
                )
            is CognitiveEncryptionResult.Failed ->
                PersistentMemoryRememberResult.Failed(
                    "encrypted persistent memory durable install failed",
                    result.throwable
                )
        }
    }

    fun find(id: MemoryRecordId): MemoryRecord? = inspect(id)?.record

    fun inspect(id: MemoryRecordId): MemoryRecordSnapshot? {
        if (!indexedLazyMode) return memoryStore.inspect(id)
        return when (val inspected = inspectResult(id)) {
            EncryptedPersistentMemoryInspectResult.Missing -> null
            is EncryptedPersistentMemoryInspectResult.Found -> inspected.snapshot
            EncryptedPersistentMemoryInspectResult.Corrupt ->
                throw IllegalStateException("encrypted persistent Memory exact read is corrupt")
            is EncryptedPersistentMemoryInspectResult.Incompatible ->
                throw IllegalStateException(inspected.reason)
            is EncryptedPersistentMemoryInspectResult.EncryptionUnavailable ->
                throw IllegalStateException(
                    "encrypted persistent Memory exact read unavailable: ${inspected.category}",
                    inspected.throwable
                )
            is EncryptedPersistentMemoryInspectResult.Failed ->
                throw IllegalStateException(inspected.reason, inspected.throwable)
        }
    }

    fun inspectResult(id: MemoryRecordId): EncryptedPersistentMemoryInspectResult {
        val entityId = PersistentEntityId(id.value)
        val snapshot = when (val inspected = encryptedStore.inspectResult(entityId)) {
            PersistentRecordLookupResult.Missing ->
                return EncryptedPersistentMemoryInspectResult.Missing
            is PersistentRecordLookupResult.Found -> inspected.snapshot
            PersistentRecordLookupResult.Corrupt ->
                return EncryptedPersistentMemoryInspectResult.Corrupt
            is PersistentRecordLookupResult.Incompatible ->
                return EncryptedPersistentMemoryInspectResult.Incompatible(inspected.reason)
            is PersistentRecordLookupResult.Failed ->
                return EncryptedPersistentMemoryInspectResult.Failed(
                    inspected.reason,
                    inspected.throwable
                )
        }

        val plaintext = when (val opened = encryptedStore.open(snapshot)) {
            is CognitiveEncryptionResult.Success -> opened.value
            is CognitiveEncryptionResult.Rejected ->
                return EncryptedPersistentMemoryInspectResult.EncryptionUnavailable(
                    opened.category
                )
            is CognitiveEncryptionResult.Failed ->
                return EncryptedPersistentMemoryInspectResult.EncryptionUnavailable(
                    opened.category,
                    opened.throwable
                )
        }
        val bytes = plaintext.copyBytes()
        return try {
            when (
                val decoded = MemoryPersistentRecordCodec.decode(
                    PersistentRecord(
                        id = snapshot.record.id,
                        schemaId = snapshot.record.schemaId,
                        schemaVersion = snapshot.record.schemaVersion,
                        payload = PersistentPayload(bytes),
                        createdAt = snapshot.record.createdAt
                    )
                )
            ) {
                is MemoryPersistentDecodeResult.Decoded ->
                    EncryptedPersistentMemoryInspectResult.Found(
                        MemoryRecordSnapshot(
                            decoded.record,
                            MemoryGeneration(snapshot.generation.value)
                        )
                    )
                MemoryPersistentDecodeResult.Corrupt ->
                    EncryptedPersistentMemoryInspectResult.Corrupt
                is MemoryPersistentDecodeResult.Incompatible ->
                    EncryptedPersistentMemoryInspectResult.Incompatible(decoded.reason)
            }
        } finally {
            bytes.fill(0)
        }
    }

    fun contains(id: MemoryRecordId): Boolean = inspect(id) != null

    fun durableMetadataSnapshot(): PersistentBackendMetadata? =
        encryptedStore.indexedMetadataSnapshot()

    fun snapshot(): List<MemoryRecord> = snapshotEntries().map { it.record }

    fun snapshotEntries(): List<MemoryRecordSnapshot> {
        if (!indexedLazyMode) return memoryStore.snapshotEntries()
        val snapshots = when (val result = encryptedStore.decryptedSnapshotEntries()) {
            is CognitiveEncryptionResult.Success -> result.value
            is CognitiveEncryptionResult.Rejected ->
                throw IllegalStateException(
                    "encrypted persistent Memory snapshot unavailable: ${result.category}"
                )
            is CognitiveEncryptionResult.Failed ->
                throw IllegalStateException(
                    "encrypted persistent Memory snapshot unavailable: ${result.category}",
                    result.throwable
                )
        }
        return snapshots.map { snapshot ->
            when (val decoded = MemoryPersistentRecordCodec.decode(snapshot.record)) {
                is MemoryPersistentDecodeResult.Decoded ->
                    MemoryRecordSnapshot(
                        decoded.record,
                        MemoryGeneration(snapshot.generation.value)
                    )
                MemoryPersistentDecodeResult.Corrupt ->
                    throw IllegalStateException("encrypted persistent Memory snapshot is corrupt")
                is MemoryPersistentDecodeResult.Incompatible ->
                    throw IllegalStateException(decoded.reason)
            }
        }
    }

    @Synchronized
    internal fun removeExact(snapshot: MemoryRecordSnapshot): PersistentMemoryMutationResult {
        val context = foundation.rootContext(
            operation = "removeExactEncryptedPersistedMemory",
            component = "Memory",
            metadata = mapOf(
                "memoryRecordId" to snapshot.record.id.value,
                "memoryGeneration" to snapshot.generation.value.toString()
            )
        )
        return when (
            val durable = encryptedStore.removeExact(
                id = PersistentEntityId(snapshot.record.id.value),
                generation = PersistentGeneration(snapshot.generation.value)
            )
        ) {
            PersistentMutationResult.Committed -> {
                val cached = memoryStore.inspect(snapshot.record.id)
                if (indexedLazyMode && cached == null) {
                    PersistentMemoryMutationResult.Committed
                } else {
                    val removedLocally = memoryStore.removeExact(
                        id = snapshot.record.id,
                        generation = snapshot.generation,
                        context = context
                    )
                    if (removedLocally) PersistentMemoryMutationResult.Committed
                    else PersistentMemoryMutationResult.Failed(
                        "durable encrypted exact memory removal committed but local exact removal failed"
                    )
                }
            }
            is PersistentMutationResult.Rejected -> PersistentMemoryMutationResult.Rejected(durable.reason)
            is PersistentMutationResult.Failed -> PersistentMemoryMutationResult.Failed(
                reason = "encrypted persistent exact memory durable removal failed",
                throwable = durable.throwable
            )
        }
    }

    private fun installCommittedMemory(
        record: MemoryRecord,
        persistentOwnership: PersistentRecordOwnership
    ): PersistentMemoryRememberResult {
        val generation = MemoryGeneration(persistentOwnership.generation.value)
        val context = foundation.rootContext(
            operation = "rememberEncryptedPersistedMemory",
            component = "Memory",
            metadata = mapOf("memoryGeneration" to generation.value.toString())
        )
        return when (
            val local = memoryStore.installCommitted(
                record = record,
                generation = generation,
                highWatermark = encryptedStore.generationHighWatermark(),
                context = context
            )
        ) {
            is MemoryRegistrationResult.Registered ->
                PersistentMemoryRememberResult.Remembered(
                    ownership(
                        persistentOwnership = persistentOwnership,
                        localRegistration = local.registration,
                        operationContext = context
                    )
                )

            is MemoryRegistrationResult.Rejected -> {
                val compensated = persistentOwnership.remove()
                val reason = if (compensated is PersistentMutationResult.Committed) {
                    "local encrypted memory install rejected after durable commit; durable candidate compensated"
                } else {
                    "local encrypted memory install rejected after durable commit; durable compensation failed"
                }
                PersistentMemoryRememberResult.Failed(reason)
            }
        }
    }

    private fun ownership(
        persistentOwnership: PersistentRecordOwnership,
        localRegistration: MemoryRegistration,
        operationContext: pro.liliya.core.logging.LogContext
    ): PersistentMemoryOwnership = object : PersistentMemoryOwnership {
        override val record: MemoryRecord = localRegistration.record
        override val generation: MemoryGeneration = localRegistration.generation

        override fun remove(): PersistentMemoryMutationResult =
            synchronized(this@EncryptedPersistentMemoryComposition) {
                when (val durable = persistentOwnership.remove()) {
                    PersistentMutationResult.Committed -> {
                        val removedLocally = localRegistration.remove(
                            foundation.childContext(
                                parent = operationContext,
                                component = "Memory",
                                operation = "removeEncryptedPersistedMemory",
                                metadata = mapOf(
                                    "memoryGeneration" to generation.value.toString()
                                )
                            )
                        )
                        if (removedLocally) PersistentMemoryMutationResult.Committed
                        else PersistentMemoryMutationResult.Failed(
                            "durable encrypted memory removal committed but local exact removal failed"
                        )
                    }

                    is PersistentMutationResult.Rejected ->
                        PersistentMemoryMutationResult.Rejected(durable.reason)
                    is PersistentMutationResult.Failed ->
                        PersistentMemoryMutationResult.Failed(
                            "encrypted persistent memory durable removal failed",
                            durable.throwable
                        )
                }
            }
    }

    companion object {
        fun open(
            foundation: FoundationComposition,
            encryptedStore: EncryptedPersistentRecordStore,
            activeDek: CognitiveDekReference
        ): EncryptedPersistentMemoryOpenResult {
            if (encryptedStore.supportsIndexedLazyMode()) {
                return when (
                    val restored = MemoryStore.restore(
                        observability = foundation.observability,
                        entries = emptyList(),
                        highWatermark = encryptedStore.generationHighWatermark()
                    )
                ) {
                    is MemoryRestorationResult.Restored ->
                        EncryptedPersistentMemoryOpenResult.Opened(
                            EncryptedPersistentMemoryComposition(
                                foundation,
                                encryptedStore,
                                restored.store,
                                activeDek,
                                indexedLazyMode = true
                            )
                        )
                    is MemoryRestorationResult.Rejected ->
                        EncryptedPersistentMemoryOpenResult.RestorationFailed(restored.reason)
                }
            }

            val restoredEntries = mutableListOf<MemoryRecordSnapshot>()

            for (snapshot in encryptedStore.snapshotEntries()) {
                val plaintext = when (val opened = encryptedStore.open(snapshot.record.id)) {
                    is CognitiveEncryptionResult.Success -> opened.value
                    is CognitiveEncryptionResult.Rejected ->
                        return EncryptedPersistentMemoryOpenResult.EncryptionUnavailable(
                            opened.category
                        )
                    is CognitiveEncryptionResult.Failed ->
                        return EncryptedPersistentMemoryOpenResult.EncryptionUnavailable(
                            opened.category
                        )
                }
                val bytes = plaintext.copyBytes()
                val decoded = try {
                    MemoryPersistentRecordCodec.decode(
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

                when (decoded) {
                    is MemoryPersistentDecodeResult.Decoded ->
                        restoredEntries += MemoryRecordSnapshot(
                            decoded.record,
                            MemoryGeneration(snapshot.generation.value)
                        )
                    MemoryPersistentDecodeResult.Corrupt ->
                        return EncryptedPersistentMemoryOpenResult.Corrupt
                    is MemoryPersistentDecodeResult.Incompatible ->
                        return EncryptedPersistentMemoryOpenResult.Incompatible(decoded.reason)
                }
            }

            return when (
                val restored = MemoryStore.restore(
                    observability = foundation.observability,
                    entries = restoredEntries,
                    highWatermark = encryptedStore.generationHighWatermark()
                )
            ) {
                is MemoryRestorationResult.Restored ->
                    EncryptedPersistentMemoryOpenResult.Opened(
                        EncryptedPersistentMemoryComposition(
                            foundation,
                            encryptedStore,
                            restored.store,
                            activeDek,
                            indexedLazyMode = false
                        )
                    )
                is MemoryRestorationResult.Rejected ->
                    EncryptedPersistentMemoryOpenResult.RestorationFailed(restored.reason)
            }
        }
    }
}
