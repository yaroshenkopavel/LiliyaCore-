package pro.liliya.core.memory

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal interface MemoryRegistration {
    val record: MemoryRecord
    val generation: MemoryGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface MemoryRegistrationResult {
    data class Registered(val registration: MemoryRegistration) : MemoryRegistrationResult
    data class Rejected(val reason: String) : MemoryRegistrationResult
}

internal class MemoryStore(
    private val observability: CoreObservability
) {
    private data class Entry(
        val generation: MemoryGeneration,
        val record: MemoryRecord
    )

    private val nextGeneration = AtomicLong(0)
    private val records = ConcurrentHashMap<MemoryRecordId, Entry>()

    fun register(record: MemoryRecord, context: LogContext): MemoryRegistrationResult {
        val entry = Entry(
            generation = MemoryGeneration(nextGeneration.incrementAndGet()),
            record = record
        )
        val existing = records.putIfAbsent(record.id, entry)
        if (existing != null) {
            val reason = "memory record ${record.id} is already registered"
            observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "MEMORY_REGISTRATION_REJECTED",
                message = reason,
                context = context,
                metadata = metadata(record, entry.generation) + ("rejectionReason" to reason)
            )
            return MemoryRegistrationResult.Rejected(reason)
        }

        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "MEMORY_REGISTERED",
            message = "memory record registered",
            context = context,
            metadata = metadata(record, entry.generation)
        )

        return MemoryRegistrationResult.Registered(
            registration = object : MemoryRegistration {
                override val record: MemoryRecord = record
                override val generation: MemoryGeneration = entry.generation

                override fun remove(context: LogContext): Boolean {
                    val removed = records.remove(record.id, entry)
                    observability.record(
                        severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (removed) "MEMORY_REMOVED" else "MEMORY_REMOVAL_REJECTED",
                        message = if (removed) {
                            "memory record removed"
                        } else {
                            "memory registration is no longer current"
                        },
                        context = context,
                        metadata = metadata(record, entry.generation)
                    )
                    return removed
                }
            }
        )
    }

    fun find(id: MemoryRecordId): MemoryRecord? = records[id]?.record

    fun inspect(id: MemoryRecordId): MemoryRecordSnapshot? = records[id]?.let { entry ->
        MemoryRecordSnapshot(
            record = entry.record,
            generation = entry.generation
        )
    }

    fun contains(id: MemoryRecordId): Boolean = records.containsKey(id)

    fun snapshot(): List<MemoryRecord> = snapshotEntries().map { it.record }

    fun snapshotEntries(): List<MemoryRecordSnapshot> = records.values
        .map { entry ->
            MemoryRecordSnapshot(
                record = entry.record,
                generation = entry.generation
            )
        }
        .sortedWith(
            compareBy<MemoryRecordSnapshot>(
                { it.record.createdAt },
                { it.record.id.value }
            )
        )

    private fun metadata(
        record: MemoryRecord,
        generation: MemoryGeneration
    ): Map<String, String> = buildMap {
        put("memoryRecordId", record.id.value)
        put("memoryGeneration", generation.value.toString())
        put("memorySourceId", record.provenance.sourceId.value)
        record.provenance.sourceReference?.let { reference ->
            put("memorySourceReference", reference.value)
        }
        put("createdAt", record.createdAt.toString())
    }
}
