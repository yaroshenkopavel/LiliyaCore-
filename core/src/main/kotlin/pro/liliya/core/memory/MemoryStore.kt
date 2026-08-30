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

internal sealed interface MemoryRestorationResult {
    data class Restored(val store: MemoryStore) : MemoryRestorationResult
    data class Rejected(val reason: String) : MemoryRestorationResult
}

internal class MemoryStore private constructor(
    private val observability: CoreObservability,
    initialHighWatermark: Long,
    initialEntries: List<MemoryRecordSnapshot>
) {
    private data class Entry(
        val generation: MemoryGeneration,
        val record: MemoryRecord
    )

    constructor(observability: CoreObservability) : this(
        observability = observability,
        initialHighWatermark = 0L,
        initialEntries = emptyList()
    )

    private val nextGeneration = AtomicLong(initialHighWatermark)
    private val records = ConcurrentHashMap<MemoryRecordId, Entry>().apply {
        initialEntries.forEach { snapshot ->
            put(
                snapshot.record.id,
                Entry(snapshot.generation, snapshot.record)
            )
        }
    }

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
            registration = registration(entry)
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

    private fun registration(entry: Entry): MemoryRegistration = object : MemoryRegistration {
        override val record: MemoryRecord = entry.record
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

    companion object {
        fun restore(
            observability: CoreObservability,
            entries: List<MemoryRecordSnapshot>,
            highWatermark: Long
        ): MemoryRestorationResult {
            if (highWatermark < 0L) {
                return MemoryRestorationResult.Rejected("memory generation high watermark is negative")
            }
            if (entries.any { it.generation.value > highWatermark }) {
                return MemoryRestorationResult.Rejected("memory generation exceeds restored high watermark")
            }
            if (entries.map { it.record.id }.toSet().size != entries.size) {
                return MemoryRestorationResult.Rejected("duplicate restored memory record id")
            }
            if (entries.map { it.generation }.toSet().size != entries.size) {
                return MemoryRestorationResult.Rejected("duplicate restored memory generation")
            }

            return MemoryRestorationResult.Restored(
                MemoryStore(
                    observability = observability,
                    initialHighWatermark = highWatermark,
                    initialEntries = entries.map { snapshot ->
                        MemoryRecordSnapshot(
                            record = snapshot.record,
                            generation = snapshot.generation
                        )
                    }
                )
            )
        }
    }
}
