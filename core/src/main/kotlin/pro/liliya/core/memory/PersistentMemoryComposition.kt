package pro.liliya.core.memory

import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.persistence.PersistentInstallResult
import pro.liliya.core.persistence.PersistentMutationResult
import pro.liliya.core.persistence.PersistentRecordBackend
import pro.liliya.core.persistence.PersistentRecordOwnership
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult

interface PersistentMemoryOwnership {
    val record: MemoryRecord
    val generation: MemoryGeneration
    fun remove(): PersistentMemoryMutationResult
}

sealed interface PersistentMemoryRememberResult {
    data class Remembered(val ownership: PersistentMemoryOwnership) : PersistentMemoryRememberResult
    data class Rejected(val reason: String) : PersistentMemoryRememberResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : PersistentMemoryRememberResult
}

sealed interface PersistentMemoryMutationResult {
    data object Committed : PersistentMemoryMutationResult
    data class Rejected(val reason: String) : PersistentMemoryMutationResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : PersistentMemoryMutationResult
}

sealed interface PersistentMemoryOpenResult {
    data class Opened(val composition: PersistentMemoryComposition) : PersistentMemoryOpenResult
    data object Corrupt : PersistentMemoryOpenResult
    data class Incompatible(val reason: String) : PersistentMemoryOpenResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : PersistentMemoryOpenResult
    data class RestorationFailed(val reason: String) : PersistentMemoryOpenResult
}

class PersistentMemoryComposition private constructor(
    private val foundation: FoundationComposition,
    private val persistentStore: PersistentRecordStore,
    private val memoryStore: MemoryStore
) {
    fun remember(record: MemoryRecord): PersistentMemoryRememberResult {
        val persistentRecord = MemoryPersistentRecordCodec.encode(record)
        return when (val installed = persistentStore.install(persistentRecord)) {
            is PersistentInstallResult.Installed -> installCommittedMemory(record, installed.ownership)
            is PersistentInstallResult.Rejected -> PersistentMemoryRememberResult.Rejected(installed.reason)
            is PersistentInstallResult.Failed ->
                PersistentMemoryRememberResult.Failed(installed.reason, installed.throwable)
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
            operation = "rememberPersistedMemory",
            component = "Memory",
            metadata = memoryMetadata(record, generation)
        )

        return when (
            val local = memoryStore.installCommitted(
                record = record,
                generation = generation,
                highWatermark = persistentStore.generationHighWatermark(),
                context = context
            )
        ) {
            is MemoryRegistrationResult.Registered -> PersistentMemoryRememberResult.Remembered(
                ownership = ownership(
                    persistentOwnership = persistentOwnership,
                    localRegistration = local.registration,
                    operationContext = context
                )
            )

            is MemoryRegistrationResult.Rejected -> {
                val compensated = persistentOwnership.remove()
                val reason = if (compensated is PersistentMutationResult.Committed) {
                    "local committed memory install rejected after durable commit; durable candidate compensated"
                } else {
                    "local committed memory install rejected after durable commit; durable compensation failed"
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

        override fun remove(): PersistentMemoryMutationResult {
            return when (val durable = persistentOwnership.remove()) {
                PersistentMutationResult.Committed -> {
                    val removedLocally = localRegistration.remove(
                        foundation.childContext(
                            parent = operationContext,
                            component = "Memory",
                            operation = "removePersistedMemory",
                            metadata = mapOf(
                                "memoryGeneration" to generation.value.toString()
                            )
                        )
                    )
                    if (removedLocally) {
                        PersistentMemoryMutationResult.Committed
                    } else {
                        PersistentMemoryMutationResult.Failed(
                            "durable memory removal committed but local exact removal failed"
                        )
                    }
                }

                is PersistentMutationResult.Rejected ->
                    PersistentMemoryMutationResult.Rejected(durable.reason)

                is PersistentMutationResult.Failed ->
                    PersistentMemoryMutationResult.Failed(durable.reason, durable.throwable)
            }
        }
    }

    private fun memoryMetadata(
        record: MemoryRecord,
        generation: MemoryGeneration
    ): Map<String, String> = buildMap {
        put("memoryRecordId", record.id.value)
        put("memoryGeneration", generation.value.toString())
        put("memorySourceId", record.provenance.sourceId.value)
        record.provenance.sourceReference?.let { put("memorySourceReference", it.value) }
        put("createdAt", record.createdAt.toString())
    }

    companion object {
        fun open(
            foundation: FoundationComposition,
            storeId: PersistentStoreId,
            backend: PersistentRecordBackend
        ): PersistentMemoryOpenResult = when (
            val opened = PersistentRecordStore.open(foundation, storeId, backend)
        ) {
            is PersistentStoreOpenResult.Opened -> restoreOpened(foundation, opened.store)
            PersistentStoreOpenResult.Corrupt -> PersistentMemoryOpenResult.Corrupt
            is PersistentStoreOpenResult.Incompatible ->
                PersistentMemoryOpenResult.Incompatible(opened.reason)
            is PersistentStoreOpenResult.Failed ->
                PersistentMemoryOpenResult.Failed(opened.reason, opened.throwable)
        }

        private fun restoreOpened(
            foundation: FoundationComposition,
            persistentStore: PersistentRecordStore
        ): PersistentMemoryOpenResult {
            val restoredEntries = mutableListOf<MemoryRecordSnapshot>()
            for (snapshot in persistentStore.snapshotEntries()) {
                when (val decoded = MemoryPersistentRecordCodec.decode(snapshot.record)) {
                    is MemoryPersistentDecodeResult.Decoded -> restoredEntries += MemoryRecordSnapshot(
                        record = decoded.record,
                        generation = MemoryGeneration(snapshot.generation.value)
                    )

                    MemoryPersistentDecodeResult.Corrupt -> return PersistentMemoryOpenResult.Corrupt
                    is MemoryPersistentDecodeResult.Incompatible ->
                        return PersistentMemoryOpenResult.Incompatible(decoded.reason)
                }
            }

            return when (
                val restored = MemoryStore.restore(
                    observability = foundation.observability,
                    entries = restoredEntries,
                    highWatermark = persistentStore.generationHighWatermark()
                )
            ) {
                is MemoryRestorationResult.Restored -> PersistentMemoryOpenResult.Opened(
                    PersistentMemoryComposition(
                        foundation = foundation,
                        persistentStore = persistentStore,
                        memoryStore = restored.store
                    )
                )

                is MemoryRestorationResult.Rejected ->
                    PersistentMemoryOpenResult.RestorationFailed(restored.reason)
            }
        }
    }
}
