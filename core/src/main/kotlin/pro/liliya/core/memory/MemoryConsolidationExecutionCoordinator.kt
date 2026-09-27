package pro.liliya.core.memory

enum class MemoryConsolidationProcessorOutcomeStatus {
    COMPLETED,
    SKIPPED
}

data class MemoryConsolidationProcessorOutcome(
    val item: MemoryConsolidationWorkItem,
    val status: MemoryConsolidationProcessorOutcomeStatus,
    val detail: String? = null
) {
    init {
        require(detail == null || detail.isNotBlank())
    }
}

sealed interface MemoryConsolidationProcessorResult {
    data class Completed(val detail: String? = null) : MemoryConsolidationProcessorResult {
        init { require(detail == null || detail.isNotBlank()) }
    }

    data class Skipped(val reason: String) : MemoryConsolidationProcessorResult {
        init { require(reason.isNotBlank()) }
    }

    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : MemoryConsolidationProcessorResult {
        init { require(reason.isNotBlank()) }
    }
}

fun interface MemoryConsolidationWorkProcessor {
    fun process(item: MemoryConsolidationWorkItem): MemoryConsolidationProcessorResult
}

class MemoryConsolidationProcessorRegistry private constructor(
    private val processors: Map<MemoryConsolidationWorkKind, MemoryConsolidationWorkProcessor>
) {
    fun processor(kind: MemoryConsolidationWorkKind): MemoryConsolidationWorkProcessor? =
        processors[kind]

    fun contains(kind: MemoryConsolidationWorkKind): Boolean = kind in processors

    companion object {
        fun of(
            processors: List<Pair<MemoryConsolidationWorkKind, MemoryConsolidationWorkProcessor>>
        ): MemoryConsolidationProcessorRegistry {
            require(processors.map { it.first }.distinct().size == processors.size) {
                "memory consolidation processor kinds must be unique"
            }
            return MemoryConsolidationProcessorRegistry(processors.toMap())
        }
    }
}

enum class MemoryConsolidationExecutionStatus {
    COMPLETED,
    REJECTED,
    FAILED,
    PARTIAL_FAILURE
}

data class MemoryConsolidationExecutionAudit(
    val triggerId: String?,
    val plannedCount: Int,
    val executedCount: Int,
    val completedCount: Int,
    val skippedCount: Int,
    val failedKind: MemoryConsolidationWorkKind?,
    val status: MemoryConsolidationExecutionStatus,
    val truncated: Boolean = false,
    val advisoryOrchestrationOnly: Boolean = true
) {
    init {
        require(triggerId == null || triggerId.isNotBlank())
        require(plannedCount >= 0)
        require(executedCount >= 0)
        require(completedCount >= 0)
        require(skippedCount >= 0)
        require(executedCount <= plannedCount)
        require(completedCount + skippedCount <= executedCount)
        require(!truncated)
        require(advisoryOrchestrationOnly)
    }
}

sealed interface MemoryConsolidationExecutionResult {
    data class Completed(
        val outcomes: List<MemoryConsolidationProcessorOutcome>,
        val audit: MemoryConsolidationExecutionAudit
    ) : MemoryConsolidationExecutionResult

    data class Rejected(
        val reason: String,
        val audit: MemoryConsolidationExecutionAudit
    ) : MemoryConsolidationExecutionResult {
        init { require(reason.isNotBlank()) }
    }

    data class Failed(
        val failedItem: MemoryConsolidationWorkItem,
        val reason: String,
        val throwable: Throwable? = null,
        val audit: MemoryConsolidationExecutionAudit
    ) : MemoryConsolidationExecutionResult {
        init { require(reason.isNotBlank()) }
    }

    data class PartialFailure(
        val completedOutcomes: List<MemoryConsolidationProcessorOutcome>,
        val failedItem: MemoryConsolidationWorkItem,
        val reason: String,
        val throwable: Throwable? = null,
        val audit: MemoryConsolidationExecutionAudit
    ) : MemoryConsolidationExecutionResult {
        init {
            require(reason.isNotBlank())
            require(completedOutcomes.isNotEmpty())
        }
    }
}

class MemoryConsolidationExecutionCoordinator(
    private val registry: MemoryConsolidationProcessorRegistry
) {
    fun execute(
        plan: MemoryConsolidationPlanResult.Planned
    ): MemoryConsolidationExecutionResult {
        val work = plan.work
        val triggerId = work.firstOrNull()?.triggerId
        var executed = 0
        var completed = 0
        var skipped = 0
        var failedKind: MemoryConsolidationWorkKind? = null
        val outcomes = ArrayList<MemoryConsolidationProcessorOutcome>(work.size)

        fun audit(status: MemoryConsolidationExecutionStatus) =
            MemoryConsolidationExecutionAudit(
                triggerId = triggerId,
                plannedCount = work.size,
                executedCount = executed,
                completedCount = completed,
                skippedCount = skipped,
                failedKind = failedKind,
                status = status
            )

        fun rejected(reason: String) =
            MemoryConsolidationExecutionResult.Rejected(
                reason = reason,
                audit = audit(MemoryConsolidationExecutionStatus.REJECTED)
            )

        if (work.isEmpty()) {
            return rejected("memory consolidation execution plan must not be empty")
        }
        if (work.size > MemoryConsolidationPlannerPolicy.MAX_WORK_ITEMS) {
            return rejected("memory consolidation execution plan exceeds hard work bound")
        }
        if (work.map { it.kind }.distinct().size != work.size) {
            return rejected("memory consolidation execution plan contains duplicate work kinds")
        }
        if (work.map { it.triggerId }.distinct().size != 1) {
            return rejected("memory consolidation execution plan mixes trigger ids")
        }

        val missing = work.firstOrNull { !registry.contains(it.kind) }
        if (missing != null) {
            return rejected(
                "memory consolidation processor missing for " + missing.kind.name
            )
        }

        for (item in work) {
            val processor = checkNotNull(registry.processor(item.kind))
            val processorResult = try {
                processor.process(item)
            } catch (t: Throwable) {
                MemoryConsolidationProcessorResult.Failed(
                    reason = "memory consolidation processor threw",
                    throwable = t
                )
            }

            executed += 1
            when (processorResult) {
                is MemoryConsolidationProcessorResult.Completed -> {
                    completed += 1
                    outcomes += MemoryConsolidationProcessorOutcome(
                        item = item,
                        status = MemoryConsolidationProcessorOutcomeStatus.COMPLETED,
                        detail = processorResult.detail
                    )
                }

                is MemoryConsolidationProcessorResult.Skipped -> {
                    skipped += 1
                    outcomes += MemoryConsolidationProcessorOutcome(
                        item = item,
                        status = MemoryConsolidationProcessorOutcomeStatus.SKIPPED,
                        detail = processorResult.reason
                    )
                }

                is MemoryConsolidationProcessorResult.Failed -> {
                    failedKind = item.kind
                    return if (outcomes.isEmpty()) {
                        MemoryConsolidationExecutionResult.Failed(
                            failedItem = item,
                            reason = processorResult.reason,
                            throwable = processorResult.throwable,
                            audit = audit(MemoryConsolidationExecutionStatus.FAILED)
                        )
                    } else {
                        MemoryConsolidationExecutionResult.PartialFailure(
                            completedOutcomes = outcomes.toList(),
                            failedItem = item,
                            reason = processorResult.reason,
                            throwable = processorResult.throwable,
                            audit = audit(MemoryConsolidationExecutionStatus.PARTIAL_FAILURE)
                        )
                    }
                }
            }
        }

        return MemoryConsolidationExecutionResult.Completed(
            outcomes = outcomes.toList(),
            audit = audit(MemoryConsolidationExecutionStatus.COMPLETED)
        )
    }
}