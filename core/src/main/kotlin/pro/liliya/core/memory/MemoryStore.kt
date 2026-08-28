package pro.liliya.core.memory

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal interface MemoryRegistration {
    val record: MemoryRecord
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
        val token: Long,
        val record: MemoryRecord
    )

    private val nextToken = AtomicLong(0)
    private val records = ConcurrentHashMap<MemoryRecordId, Entry>()

    fun register(record: MemoryRecord, context: LogContext): MemoryRegistrationResult {
        val entry = Entry(
            token = nextToken.incrementAndGet(),
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
                metadata = metadata(record) + ("rejectionReason" to reason)
            )
            return MemoryRegistrationResult.Rejected(reason)
        }

        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "MEMORY_REGISTERED",
            message = "memory record registered",
            context = context,
            metadata = metadata(record)
        )

        return MemoryRegistrationResult.Registered(
            registration = object : MemoryRegistration {
                override val record: MemoryRecord = record

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
                        metadata = metadata(record)
                    )
                    return removed
                }
            }
        )
    }

    fun find(id: MemoryRecordId): MemoryRecord? = records[id]?.record

    fun contains(id: MemoryRecordId): Boolean = records.containsKey(id)

    fun snapshot(): List<MemoryRecord> = records.values
        .map { it.record }
        .sortedWith(
            compareBy<MemoryRecord>(
                { it.createdAt },
                { it.id.value }
            )
        )

    private fun metadata(record: MemoryRecord): Map<String, String> = mapOf(
        "memoryRecordId" to record.id.value,
        "memorySourceId" to record.sourceId.value,
        "createdAt" to record.createdAt.toString()
    )
}
