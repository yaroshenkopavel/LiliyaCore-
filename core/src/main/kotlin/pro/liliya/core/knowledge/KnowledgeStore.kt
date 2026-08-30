package pro.liliya.core.knowledge

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal interface KnowledgeRegistration {
    val item: KnowledgeItem
    val generation: KnowledgeGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface KnowledgeRegistrationResult {
    data class Registered(val registration: KnowledgeRegistration) : KnowledgeRegistrationResult
    data class Rejected(val reason: String) : KnowledgeRegistrationResult
}

internal sealed interface KnowledgeRestorationResult {
    data class Restored(val store: KnowledgeStore) : KnowledgeRestorationResult
    data class Rejected(val reason: String) : KnowledgeRestorationResult
}

internal class KnowledgeStore private constructor(
    private val observability: CoreObservability,
    initialHighWatermark: Long,
    initialEntries: List<KnowledgeItemSnapshot>
) {
    private data class Entry(
        val generation: KnowledgeGeneration,
        val item: KnowledgeItem
    )

    constructor(observability: CoreObservability) : this(
        observability = observability,
        initialHighWatermark = 0L,
        initialEntries = emptyList()
    )

    private val nextGeneration = AtomicLong(initialHighWatermark)
    private val items = ConcurrentHashMap<KnowledgeItemId, Entry>().apply {
        initialEntries.forEach { snapshot ->
            put(snapshot.item.id, Entry(snapshot.generation, snapshot.item))
        }
    }

    fun register(item: KnowledgeItem, context: LogContext): KnowledgeRegistrationResult {
        val entry = Entry(
            generation = KnowledgeGeneration(nextGeneration.incrementAndGet()),
            item = item
        )
        val existing = items.putIfAbsent(item.id, entry)
        if (existing != null) {
            val reason = "knowledge item ${item.id} is already registered"
            observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "KNOWLEDGE_REGISTRATION_REJECTED",
                message = reason,
                context = context,
                metadata = metadata(item, entry.generation) + ("rejectionReason" to reason)
            )
            return KnowledgeRegistrationResult.Rejected(reason)
        }

        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "KNOWLEDGE_REGISTERED",
            message = "knowledge item registered",
            context = context,
            metadata = metadata(item, entry.generation)
        )

        return KnowledgeRegistrationResult.Registered(
            registration = registration(entry)
        )
    }

    fun find(id: KnowledgeItemId): KnowledgeItem? = items[id]?.item

    fun inspect(id: KnowledgeItemId): KnowledgeItemSnapshot? = items[id]?.let { entry ->
        KnowledgeItemSnapshot(item = entry.item, generation = entry.generation)
    }

    fun contains(id: KnowledgeItemId): Boolean = items.containsKey(id)

    fun snapshot(): List<KnowledgeItem> = snapshotEntries().map { it.item }

    fun snapshotEntries(): List<KnowledgeItemSnapshot> = items.values
        .map { entry -> KnowledgeItemSnapshot(item = entry.item, generation = entry.generation) }
        .sortedWith(
            compareBy<KnowledgeItemSnapshot>(
                { it.item.createdAt },
                { it.item.id.value }
            )
        )

    private fun registration(entry: Entry): KnowledgeRegistration = object : KnowledgeRegistration {
        override val item: KnowledgeItem = entry.item
        override val generation: KnowledgeGeneration = entry.generation

        override fun remove(context: LogContext): Boolean {
            val removed = items.remove(item.id, entry)
            observability.record(
                severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                code = if (removed) "KNOWLEDGE_REMOVED" else "KNOWLEDGE_REMOVAL_REJECTED",
                message = if (removed) {
                    "knowledge item removed"
                } else {
                    "knowledge registration is no longer current"
                },
                context = context,
                metadata = metadata(item, entry.generation)
            )
            return removed
        }
    }

    private fun metadata(
        item: KnowledgeItem,
        generation: KnowledgeGeneration
    ): Map<String, String> = buildMap {
        put("knowledgeItemId", item.id.value)
        put("knowledgeGeneration", generation.value.toString())
        put("createdAt", item.createdAt.toString())
        when (val origin = item.origin) {
            is KnowledgeOrigin.Memory -> {
                put("knowledgeOriginType", "memory")
                put("memoryRecordId", origin.recordId.value)
                put("memoryGeneration", origin.generation.value.toString())
            }

            is KnowledgeOrigin.Declared -> {
                put("knowledgeOriginType", "declared")
                put("knowledgeSourceId", origin.sourceId.value)
                origin.sourceReference?.let { reference ->
                    put("knowledgeSourceReference", reference.value)
                }
            }
        }
    }

    companion object {
        fun restore(
            observability: CoreObservability,
            entries: List<KnowledgeItemSnapshot>,
            highWatermark: Long
        ): KnowledgeRestorationResult {
            if (highWatermark < 0L) {
                return KnowledgeRestorationResult.Rejected("knowledge generation high watermark is negative")
            }
            if (entries.any { it.generation.value > highWatermark }) {
                return KnowledgeRestorationResult.Rejected(
                    "knowledge generation exceeds restored high watermark"
                )
            }
            if (entries.map { it.item.id }.toSet().size != entries.size) {
                return KnowledgeRestorationResult.Rejected("duplicate restored knowledge item id")
            }
            if (entries.map { it.generation }.toSet().size != entries.size) {
                return KnowledgeRestorationResult.Rejected("duplicate restored knowledge generation")
            }

            return KnowledgeRestorationResult.Restored(
                KnowledgeStore(
                    observability = observability,
                    initialHighWatermark = highWatermark,
                    initialEntries = entries.map { snapshot ->
                        KnowledgeItemSnapshot(
                            item = snapshot.item,
                            generation = snapshot.generation
                        )
                    }
                )
            )
        }
    }
}
