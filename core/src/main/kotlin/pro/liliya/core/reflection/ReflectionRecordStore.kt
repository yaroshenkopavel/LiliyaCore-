package pro.liliya.core.reflection

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal interface ReflectionRecordRegistration {
    val record: ReflectionRecord
    val generation: ReflectionGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface ReflectionRecordRegistrationResult {
    data class Registered(val registration: ReflectionRecordRegistration) : ReflectionRecordRegistrationResult
    data class Rejected(val reason: String) : ReflectionRecordRegistrationResult
}

internal class ReflectionRecordStore(
    private val observability: CoreObservability
) {
    private data class Entry(
        val generation: ReflectionGeneration,
        val record: ReflectionRecord
    )

    private val nextGeneration = AtomicLong(0)
    private val records = ConcurrentHashMap<ReflectionRecordId, Entry>()

    fun register(record: ReflectionRecord, context: LogContext): ReflectionRecordRegistrationResult {
        val entry = Entry(
            generation = ReflectionGeneration(nextGeneration.incrementAndGet()),
            record = record
        )
        val previous = records.putIfAbsent(record.id, entry)
        if (previous != null) {
            val reason = "reflection record id is already registered"
            observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "REFLECTION_RECORD_REGISTRATION_REJECTED",
                message = reason,
                context = context,
                metadata = metadata(record, entry.generation) + ("rejectionReason" to reason)
            )
            return ReflectionRecordRegistrationResult.Rejected(reason)
        }

        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "REFLECTION_RECORD_REGISTERED",
            message = "reflection record registered",
            context = context,
            metadata = metadata(record, entry.generation)
        )

        return ReflectionRecordRegistrationResult.Registered(
            registration = object : ReflectionRecordRegistration {
                override val record: ReflectionRecord = record
                override val generation: ReflectionGeneration = entry.generation

                override fun remove(context: LogContext): Boolean {
                    val removed = records.remove(record.id, entry)
                    observability.record(
                        severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (removed) "REFLECTION_RECORD_REMOVED" else "REFLECTION_RECORD_REMOVAL_REJECTED",
                        message = if (removed) "reflection record removed" else "reflection record registration is no longer current",
                        context = context,
                        metadata = metadata(record, entry.generation)
                    )
                    return removed
                }
            }
        )
    }

    fun find(id: ReflectionRecordId): ReflectionRecord? = records[id]?.record

    fun inspect(id: ReflectionRecordId): ReflectionRecordSnapshot? = records[id]?.let { entry ->
        ReflectionRecordSnapshot(entry.record, entry.generation)
    }

    fun contains(id: ReflectionRecordId): Boolean = records.containsKey(id)

    fun snapshot(): List<ReflectionRecord> = snapshotEntries().map { it.record }

    fun snapshotEntries(): List<ReflectionRecordSnapshot> = records.values
        .map { ReflectionRecordSnapshot(it.record, it.generation) }
        .sortedWith(compareBy<ReflectionRecordSnapshot> { it.record.createdAt }.thenBy { it.record.id.value })

    private fun metadata(
        record: ReflectionRecord,
        generation: ReflectionGeneration
    ): Map<String, String> = buildMap {
        put("reflectionRecordId", record.id.value)
        put("reflectionGeneration", generation.value.toString())
        put("createdAt", record.createdAt.toString())
        when (val origin = record.origin) {
            is ReflectionOrigin.Memory -> {
                put("reflectionOriginType", "memory")
                put("memoryRecordId", origin.recordId.value)
                put("memoryGeneration", origin.generation.value.toString())
            }
            is ReflectionOrigin.Knowledge -> {
                put("reflectionOriginType", "knowledge")
                put("knowledgeItemId", origin.itemId.value)
                put("knowledgeGeneration", origin.generation.value.toString())
            }
            is ReflectionOrigin.Declared -> {
                put("reflectionOriginType", "declared")
                put("reflectionSourceId", origin.sourceId.value)
                origin.sourceReference?.let { put("reflectionSourceReference", it.value) }
            }
        }
    }
}
