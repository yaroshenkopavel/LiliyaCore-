package pro.liliya.core.memory

import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentInstallResult
import pro.liliya.core.persistence.PersistentMutationResult
import pro.liliya.core.persistence.PersistentRecordBackend
import pro.liliya.core.persistence.PersistentRecordLookupResult
import pro.liliya.core.persistence.PersistentRecordOwnership
import pro.liliya.core.persistence.PersistentRecordSnapshotEntriesResult
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
    data class Failed(val reason: String, val throwable: Throwable? = null) : PersistentMemoryRememberResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

sealed interface PersistentMemoryMutationResult {
    data object Committed : PersistentMemoryMutationResult
    data class Rejected(val reason: String) : PersistentMemoryMutationResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : PersistentMemoryMutationResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

sealed interface PersistentMemoryInspectResult {
    data object Missing : PersistentMemoryInspectResult
    data class Found(val snapshot: MemoryRecordSnapshot) : PersistentMemoryInspectResult
    data object Corrupt : PersistentMemoryInspectResult
    data class Incompatible(val reason: String) : PersistentMemoryInspectResult
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : PersistentMemoryInspectResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

sealed interface PersistentMemoryOpenResult {
    data class Opened(val composition: PersistentMemoryComposition) : PersistentMemoryOpenResult
    data object Corrupt : PersistentMemoryOpenResult
    data class Incompatible(val reason: String) : PersistentMemoryOpenResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : PersistentMemoryOpenResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
    data class RestorationFailed(val reason: String) : PersistentMemoryOpenResult
}

class PersistentMemoryComposition private constructor(
    private val foundation: FoundationComposition,
    private val persistentStore: PersistentRecordStore,
    private val memoryStore: MemoryStore,
    private val indexedLazyMode: Boolean
) {
    @Synchronized
    fun remember(record: MemoryRecord): PersistentMemoryRememberResult {
        val persistentRecord = MemoryPersistentRecordCodec.encode(record)
        return when (val installed = persistentStore.install(persistentRecord)) {
            is PersistentInstallResult.Installed -> installCommittedMemory(record, installed.ownership)
            is PersistentInstallResult.Rejected -> PersistentMemoryRememberResult.Rejected(installed.reason)
            is PersistentInstallResult.Failed -> PersistentMemoryRememberResult.Failed(
                reason = "persistent memory durable install failed",
                throwable = installed.throwable
            )
        }
    }

    fun find(id: MemoryRecordId): MemoryRecord? = inspect(id)?.record

    fun inspect(id: MemoryRecordId): MemoryRecordSnapshot? {
        if (!indexedLazyMode) return memoryStore.inspect(id)
        return when (val inspected = inspectResult(id)) {
            PersistentMemoryInspectResult.Missing -> null
            is PersistentMemoryInspectResult.Found -> inspected.snapshot
            PersistentMemoryInspectResult.Corrupt ->
                throw IllegalStateException("persistent indexed Memory exact read is corrupt")
            is PersistentMemoryInspectResult.Incompatible ->
                throw IllegalStateException(inspected.reason)
            is PersistentMemoryInspectResult.Failed ->
                throw IllegalStateException(inspected.reason, inspected.throwable)
        }
    }

    fun inspectResult(id: MemoryRecordId): PersistentMemoryInspectResult =
        when (
            val inspected = persistentStore.inspectResult(PersistentEntityId(id.value))
        ) {
            PersistentRecordLookupResult.Missing -> PersistentMemoryInspectResult.Missing
            is PersistentRecordLookupResult.Found ->
                when (val decoded = MemoryPersistentRecordCodec.decode(inspected.snapshot.record)) {
                    is MemoryPersistentDecodeResult.Decoded ->
                        PersistentMemoryInspectResult.Found(
                            MemoryRecordSnapshot(
                                record = decoded.record,
                                generation = MemoryGeneration(inspected.snapshot.generation.value)
                            )
                        )
                    MemoryPersistentDecodeResult.Corrupt ->
                        PersistentMemoryInspectResult.Corrupt
                    is MemoryPersistentDecodeResult.Incompatible ->
                        PersistentMemoryInspectResult.Incompatible(decoded.reason)
                }
            PersistentRecordLookupResult.Corrupt -> PersistentMemoryInspectResult.Corrupt
            is PersistentRecordLookupResult.Incompatible ->
                PersistentMemoryInspectResult.Incompatible(inspected.reason)
            is PersistentRecordLookupResult.Failed ->
                PersistentMemoryInspectResult.Failed(
                    inspected.reason,
                    inspected.throwable
                )
        }

    fun contains(id: MemoryRecordId): Boolean = inspect(id) != null

    fun snapshot(): List<MemoryRecord> = snapshotEntries().map { it.record }

    fun snapshotEntries(): List<MemoryRecordSnapshot> {
        if (!indexedLazyMode) return memoryStore.snapshotEntries()
        val listed = when (val result = persistentStore.snapshotEntriesResult()) {
            PersistentRecordSnapshotEntriesResult.Empty -> return emptyList()
            is PersistentRecordSnapshotEntriesResult.Loaded -> result.entries
            PersistentRecordSnapshotEntriesResult.Corrupt ->
                throw IllegalStateException("persistent indexed Memory snapshot is corrupt")
            is PersistentRecordSnapshotEntriesResult.Incompatible ->
                throw IllegalStateException(result.reason)
            is PersistentRecordSnapshotEntriesResult.Failed ->
                throw IllegalStateException(result.reason, result.throwable)
        }
        return listed.map { snapshot ->
            when (val decoded = MemoryPersistentRecordCodec.decode(snapshot.record)) {
                is MemoryPersistentDecodeResult.Decoded ->
                    MemoryRecordSnapshot(
                        decoded.record,
                        MemoryGeneration(snapshot.generation.value)
                    )
                MemoryPersistentDecodeResult.Corrupt ->
                    throw IllegalStateException("persistent indexed Memory snapshot is corrupt")
                is MemoryPersistentDecodeResult.Incompatible ->
                    throw IllegalStateException(decoded.reason)
            }
        }
    }

    @Synchronized
    internal fun removeExact(snapshot: MemoryRecordSnapshot): PersistentMemoryMutationResult {
        val context = foundation.rootContext(
            operation = "removeExactPersistedMemory",
            component = "Memory",
            metadata = mapOf(
                "memoryRecordId" to snapshot.record.id.value,
                "memoryGeneration" to snapshot.generation.value.toString()
            )
        )
        return when (
            val durable = persistentStore.removeExact(
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
                        "durable exact memory removal committed but local exact removal failed"
                    )
                }
            }
            is PersistentMutationResult.Rejected -> PersistentMemoryMutationResult.Rejected(durable.reason)
            is PersistentMutationResult.Failed -> PersistentMemoryMutationResult.Failed(
                reason = "persistent exact memory durable removal failed",
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

        override fun remove(): PersistentMemoryMutationResult = synchronized(this@PersistentMemoryComposition) {
            when (val durable = persistentOwnership.remove()) {
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
                    PersistentMemoryMutationResult.Failed(
                        reason = "persistent memory durable removal failed",
                        throwable = durable.throwable
                    )
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
                PersistentMemoryOpenResult.Failed(
                    reason = "persistent memory backend open failed",
                    throwable = opened.throwable
                )
        }

        private fun restoreOpened(
            foundation: FoundationComposition,
            persistentStore: PersistentRecordStore
        ): PersistentMemoryOpenResult {
            if (persistentStore.supportsIndexedLazyMode()) {
                return when (
                    val restored = MemoryStore.restore(
                        observability = foundation.observability,
                        entries = emptyList(),
                        highWatermark = persistentStore.generationHighWatermark()
                    )
                ) {
                    is MemoryRestorationResult.Restored -> PersistentMemoryOpenResult.Opened(
                        PersistentMemoryComposition(
                            foundation = foundation,
                            persistentStore = persistentStore,
                            memoryStore = restored.store,
                            indexedLazyMode = true
                        )
                    )
                    is MemoryRestorationResult.Rejected ->
                        PersistentMemoryOpenResult.RestorationFailed(restored.reason)
                }
            }

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
                        memoryStore = restored.store,
                        indexedLazyMode = false
                    )
                )

                is MemoryRestorationResult.Rejected ->
                    PersistentMemoryOpenResult.RestorationFailed(restored.reason)
            }
        }
    }
}
