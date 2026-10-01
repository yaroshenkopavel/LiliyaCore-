package pro.liliya.core.memory

import pro.liliya.core.events.CoreEvent
import pro.liliya.core.events.EventEnvelope
import pro.liliya.core.events.EventListener
import pro.liliya.core.logging.LogContext

enum class MemoryConsolidationTriggerType {
    TASK_COMPLETED,
    CONVERSATION_PHASE_CONCLUDED,
    SEMANTIC_CONFLICT_DETECTED,
    RETRIEVAL_INDEX_MAINTENANCE_NEEDED,
    RESOURCE_PRESSURE,
    GOVERNED_MAINTENANCE_REQUEST
}

enum class MemoryConsolidationWorkKind {
    EPISODIC_EXTRACTION_CHECK,
    SEMANTIC_CLAIM_EXTRACTION_CHECK,
    PROVENANCE_LINK_CHECK,
    DUPLICATE_CONFLICT_REVIEW,
    TEMPORAL_SUPERSESSION_REVIEW,
    GRAPH_PROJECTION_MAINTENANCE,
    LEXICAL_VECTOR_INDEX_MAINTENANCE,
    RETRIEVAL_QUALITY_BOOKKEEPING
}

data class MemoryConsolidationTriggerEvent(
    val triggerId: String,
    val triggerType: MemoryConsolidationTriggerType,
    val subjectReference: String? = null,
    val requestedWork: List<MemoryConsolidationWorkKind> = emptyList(),
    override val context: LogContext
) : CoreEvent {
    init {
        require(triggerId.isNotBlank() && triggerId.length <= 128)
        require(subjectReference == null || (subjectReference.isNotBlank() && subjectReference.length <= 256))
        require(requestedWork.size <= MemoryConsolidationPlannerPolicy.MAX_REQUESTED_WORK_ITEMS)
        require(
            triggerType == MemoryConsolidationTriggerType.GOVERNED_MAINTENANCE_REQUEST ||
                requestedWork.isEmpty()
        )
        require(
            triggerType != MemoryConsolidationTriggerType.GOVERNED_MAINTENANCE_REQUEST ||
                requestedWork.isNotEmpty()
        )
    }

    override val type: String = TYPE
    override val metadata: Map<String, String> = buildMap {
        put("triggerId", triggerId)
        put("triggerType", triggerType.name)
        put("requestedWorkCount", requestedWork.size.toString())
        if (subjectReference != null) put("subjectReference", subjectReference)
    }

    companion object {
        const val TYPE = "memory.consolidation.trigger.v1"
    }
}

data class MemoryConsolidationPlannerPolicy(
    val maxWorkItems: Int = DEFAULT_MAX_WORK_ITEMS
) {
    init {
        require(maxWorkItems in 1..MAX_WORK_ITEMS)
    }

    companion object {
        const val DEFAULT_MAX_WORK_ITEMS = 32
        const val MAX_WORK_ITEMS = 32
        const val MAX_REQUESTED_WORK_ITEMS = 128
    }
}

data class MemoryConsolidationWorkItem(
    val triggerId: String,
    val subjectReference: String?,
    val kind: MemoryConsolidationWorkKind
)

enum class MemoryConsolidationPlanStatus {
    PLANNED,
    REJECTED,
    FAILED
}

data class MemoryConsolidationPlanAudit(
    val triggerType: MemoryConsolidationTriggerType,
    val requestedWorkCount: Int,
    val emittedWorkCount: Int,
    val duplicateSuppressed: Int,
    val truncated: Boolean,
    val status: MemoryConsolidationPlanStatus,
    val advisoryOnly: Boolean = true
) {
    init {
        require(requestedWorkCount >= 0)
        require(emittedWorkCount >= 0)
        require(duplicateSuppressed >= 0)
        require(advisoryOnly)
    }
}

sealed interface MemoryConsolidationPlanResult {
    data class Planned(
        val work: List<MemoryConsolidationWorkItem>,
        val audit: MemoryConsolidationPlanAudit
    ) : MemoryConsolidationPlanResult

    data class Rejected(
        val reason: String,
        val audit: MemoryConsolidationPlanAudit
    ) : MemoryConsolidationPlanResult {
        init { require(reason.isNotBlank()) }
    }

    data class Failed(
        val reason: String,
        val throwable: Throwable? = null,
        val audit: MemoryConsolidationPlanAudit
    ) : MemoryConsolidationPlanResult {
        init { require(reason.isNotBlank()) }
    }
}

internal fun interface MemoryConsolidationRuleSource {
    fun workFor(trigger: MemoryConsolidationTriggerEvent): List<MemoryConsolidationWorkKind>
}

private object DefaultMemoryConsolidationRuleSource : MemoryConsolidationRuleSource {
    override fun workFor(trigger: MemoryConsolidationTriggerEvent): List<MemoryConsolidationWorkKind> =
        when (trigger.triggerType) {
            MemoryConsolidationTriggerType.TASK_COMPLETED -> listOf(
                MemoryConsolidationWorkKind.EPISODIC_EXTRACTION_CHECK,
                MemoryConsolidationWorkKind.SEMANTIC_CLAIM_EXTRACTION_CHECK,
                MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK,
                MemoryConsolidationWorkKind.RETRIEVAL_QUALITY_BOOKKEEPING
            )

            MemoryConsolidationTriggerType.CONVERSATION_PHASE_CONCLUDED -> listOf(
                MemoryConsolidationWorkKind.EPISODIC_EXTRACTION_CHECK,
                MemoryConsolidationWorkKind.SEMANTIC_CLAIM_EXTRACTION_CHECK,
                MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK,
                MemoryConsolidationWorkKind.DUPLICATE_CONFLICT_REVIEW,
                MemoryConsolidationWorkKind.LEXICAL_VECTOR_INDEX_MAINTENANCE,
                MemoryConsolidationWorkKind.RETRIEVAL_QUALITY_BOOKKEEPING
            )

            MemoryConsolidationTriggerType.SEMANTIC_CONFLICT_DETECTED -> listOf(
                MemoryConsolidationWorkKind.DUPLICATE_CONFLICT_REVIEW,
                MemoryConsolidationWorkKind.TEMPORAL_SUPERSESSION_REVIEW,
                MemoryConsolidationWorkKind.PROVENANCE_LINK_CHECK,
                MemoryConsolidationWorkKind.RETRIEVAL_QUALITY_BOOKKEEPING
            )

            MemoryConsolidationTriggerType.RETRIEVAL_INDEX_MAINTENANCE_NEEDED -> listOf(
                MemoryConsolidationWorkKind.LEXICAL_VECTOR_INDEX_MAINTENANCE,
                MemoryConsolidationWorkKind.RETRIEVAL_QUALITY_BOOKKEEPING
            )

            MemoryConsolidationTriggerType.RESOURCE_PRESSURE -> listOf(
                MemoryConsolidationWorkKind.LEXICAL_VECTOR_INDEX_MAINTENANCE,
                MemoryConsolidationWorkKind.RETRIEVAL_QUALITY_BOOKKEEPING
            )

            MemoryConsolidationTriggerType.GOVERNED_MAINTENANCE_REQUEST ->
                trigger.requestedWork
        }
}

class MemoryConsolidationTriggerPlanner private constructor(
    private val policy: MemoryConsolidationPlannerPolicy,
    private val rules: MemoryConsolidationRuleSource
) {
    constructor(
        policy: MemoryConsolidationPlannerPolicy = MemoryConsolidationPlannerPolicy()
    ) : this(policy, DefaultMemoryConsolidationRuleSource)

    companion object {
        internal fun testing(
            policy: MemoryConsolidationPlannerPolicy = MemoryConsolidationPlannerPolicy(),
            rules: MemoryConsolidationRuleSource
        ): MemoryConsolidationTriggerPlanner = MemoryConsolidationTriggerPlanner(policy, rules)
    }

    fun plan(trigger: MemoryConsolidationTriggerEvent): MemoryConsolidationPlanResult {
        var requested = 0
        var duplicates = 0
        var truncated = false

        fun audit(status: MemoryConsolidationPlanStatus, emitted: Int) =
            MemoryConsolidationPlanAudit(
                triggerType = trigger.triggerType,
                requestedWorkCount = requested,
                emittedWorkCount = emitted,
                duplicateSuppressed = duplicates,
                truncated = truncated,
                status = status
            )

        val raw = try {
            rules.workFor(trigger)
        } catch (t: Throwable) {
            return MemoryConsolidationPlanResult.Failed(
                reason = "memory consolidation planning failed",
                throwable = t,
                audit = audit(MemoryConsolidationPlanStatus.FAILED, 0)
            )
        }

        requested = raw.size
        if (raw.isEmpty()) {
            return MemoryConsolidationPlanResult.Rejected(
                reason = "memory consolidation trigger produced no work",
                audit = audit(MemoryConsolidationPlanStatus.REJECTED, 0)
            )
        }

        val unique = LinkedHashSet<MemoryConsolidationWorkKind>()
        raw.forEach { kind ->
            if (!unique.add(kind)) duplicates += 1
        }

        val ordered = unique.toList().sortedBy { it.ordinal }
        truncated = ordered.size > policy.maxWorkItems
        val selected = ordered.take(policy.maxWorkItems)
        val items = selected.map { kind ->
            MemoryConsolidationWorkItem(
                triggerId = trigger.triggerId,
                subjectReference = trigger.subjectReference,
                kind = kind
            )
        }

        return MemoryConsolidationPlanResult.Planned(
            work = items,
            audit = audit(MemoryConsolidationPlanStatus.PLANNED, items.size)
        )
    }
}

fun interface MemoryConsolidationPlanSink {
    fun accept(plan: MemoryConsolidationPlanResult.Planned)
}

class MemoryConsolidationEventListener(
    private val planner: MemoryConsolidationTriggerPlanner,
    private val sink: MemoryConsolidationPlanSink
) : EventListener {
    override fun onEvent(envelope: EventEnvelope) {
        val trigger = envelope.event as? MemoryConsolidationTriggerEvent ?: return
        val result = planner.plan(trigger)
        if (result is MemoryConsolidationPlanResult.Planned) {
            sink.accept(result)
        }
    }
}