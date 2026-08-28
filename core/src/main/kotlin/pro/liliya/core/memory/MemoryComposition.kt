package pro.liliya.core.memory

import pro.liliya.core.foundation.FoundationComposition

interface MemoryOwnership {
    val record: MemoryRecord
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

    fun remember(record: MemoryRecord): MemoryRememberResult {
        val context = foundation.rootContext(
            operation = "remember",
            component = "Memory",
            metadata = provenanceMetadata(record)
        )
        return when (val result = store.register(record, context)) {
            is MemoryRegistrationResult.Registered -> MemoryRememberResult.Remembered(
                ownership = object : MemoryOwnership {
                    override val record: MemoryRecord = result.registration.record

                    override fun remove(): Boolean = result.registration.remove(
                        foundation.rootContext(
                            operation = "removeMemory",
                            component = "Memory",
                            metadata = provenanceMetadata(record)
                        )
                    )
                }
            )

            is MemoryRegistrationResult.Rejected -> MemoryRememberResult.Rejected(result.reason)
        }
    }

    fun find(id: MemoryRecordId): MemoryRecord? = store.find(id)

    fun contains(id: MemoryRecordId): Boolean = store.contains(id)

    fun snapshot(): List<MemoryRecord> = store.snapshot()

    private fun provenanceMetadata(record: MemoryRecord): Map<String, String> = buildMap {
        put("memoryRecordId", record.id.value)
        put("memorySourceId", record.provenance.sourceId.value)
        record.provenance.sourceReference?.let { reference ->
            put("memorySourceReference", reference.value)
        }
    }
}
