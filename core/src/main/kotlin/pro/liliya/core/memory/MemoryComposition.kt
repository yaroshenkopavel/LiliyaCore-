package pro.liliya.core.memory

import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.LogContext

interface MemoryOwnership {
    val record: MemoryRecord
    val generation: MemoryGeneration
    fun remove(): Boolean
}

sealed interface MemoryRememberResult {
    data class Remembered(val ownership: MemoryOwnership) : MemoryRememberResult
    data class Rejected(val reason: String) : MemoryRememberResult
}

class MemoryComposition(
    private val foundation: FoundationComposition
) {
    private val store = MemoryStore(foundation.observability)

    fun remember(record: MemoryRecord): MemoryRememberResult = remember(
        record = record,
        context = foundation.rootContext(
            operation = "remember",
            component = "Memory",
            metadata = provenanceMetadata(record)
        )
    )

    internal fun remember(
        record: MemoryRecord,
        context: LogContext
    ): MemoryRememberResult {
        val operationContext = context.copy(
            metadata = (context.metadata + provenanceMetadata(record)).toMap()
        )
        return when (val result = store.register(record, operationContext)) {
            is MemoryRegistrationResult.Registered -> MemoryRememberResult.Remembered(
                ownership = object : MemoryOwnership {
                    override val record: MemoryRecord = result.registration.record
                    override val generation: MemoryGeneration = result.registration.generation

                    override fun remove(): Boolean = result.registration.remove(
                        foundation.childContext(
                            parent = operationContext,
                            component = "Memory",
                            operation = "removeMemory",
                            metadata = mapOf(
                                "memoryGeneration" to generation.value.toString()
                            )
                        )
                    )
                }
            )

            is MemoryRegistrationResult.Rejected -> MemoryRememberResult.Rejected(result.reason)
        }
    }

    fun find(id: MemoryRecordId): MemoryRecord? = store.find(id)

    fun inspect(id: MemoryRecordId): MemoryRecordSnapshot? = store.inspect(id)

    fun contains(id: MemoryRecordId): Boolean = store.contains(id)

    fun snapshot(): List<MemoryRecord> = store.snapshot()

    fun snapshotEntries(): List<MemoryRecordSnapshot> = store.snapshotEntries()

    private fun provenanceMetadata(record: MemoryRecord): Map<String, String> = buildMap {
        put("memoryRecordId", record.id.value)
        put("memorySourceId", record.provenance.sourceId.value)
        record.provenance.sourceReference?.let { reference ->
            put("memorySourceReference", reference.value)
        }
    }
}
