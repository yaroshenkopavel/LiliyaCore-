package pro.liliya.core.memory

import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.encryption.CognitiveEncryptionFailureCategory
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitivePersistentRecordDraft
import pro.liliya.core.encryption.CognitivePlaintext
import pro.liliya.core.encryption.EncryptedPersistentRecordStore
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.persistence.PersistentMutationResult
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentRecordOwnership

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
    private val activeDek: CognitiveDekReference
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

    fun find(id: MemoryRecordId): MemoryRecord? = memoryStore.find(id)
    fun inspect(id: MemoryRecordId): MemoryRecordSnapshot? = memoryStore.inspect(id)
    fun contains(id: MemoryRecordId): Boolean = memoryStore.contains(id)
    fun snapshot(): List<MemoryRecord> = memoryStore.snapshot()
    fun snapshotEntries(): List<MemoryRecordSnapshot> = memoryStore.snapshotEntries()

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
                            activeDek
                        )
                    )
                is MemoryRestorationResult.Rejected ->
                    EncryptedPersistentMemoryOpenResult.RestorationFailed(restored.reason)
            }
        }
    }
}
