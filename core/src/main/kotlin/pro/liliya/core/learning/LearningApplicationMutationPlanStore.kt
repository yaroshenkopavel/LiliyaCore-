package pro.liliya.core.learning

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal interface LearningApplicationMutationPlanRegistration {
    val plan: LearningApplicationMutationPlan
    val generation: LearningApplicationMutationPlanGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface LearningApplicationMutationPlanRegistrationResult {
    data class Registered(
        val registration: LearningApplicationMutationPlanRegistration
    ) : LearningApplicationMutationPlanRegistrationResult

    data class Rejected(val reason: String) : LearningApplicationMutationPlanRegistrationResult
}

internal class LearningApplicationMutationPlanStore(
    private val observability: CoreObservability
) {
    private data class Entry(
        val generation: LearningApplicationMutationPlanGeneration,
        val plan: LearningApplicationMutationPlan
    )

    private val nextGeneration = AtomicLong(0)
    private val plans = ConcurrentHashMap<LearningApplicationMutationPlanId, Entry>()

    fun register(
        plan: LearningApplicationMutationPlan,
        context: LogContext
    ): LearningApplicationMutationPlanRegistrationResult {
        val entry = Entry(
            generation = LearningApplicationMutationPlanGeneration(nextGeneration.incrementAndGet()),
            plan = plan
        )
        val previous = plans.putIfAbsent(plan.id, entry)
        if (previous != null) {
            val reason = "learning application mutation plan id is already registered"
            observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "LEARNING_APPLICATION_MUTATION_PLAN_REGISTRATION_REJECTED",
                message = reason,
                context = context,
                metadata = metadata(plan, entry.generation) + ("rejectionReason" to reason)
            )
            return LearningApplicationMutationPlanRegistrationResult.Rejected(reason)
        }

        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "LEARNING_APPLICATION_MUTATION_PLAN_REGISTERED",
            message = "learning application mutation plan registered",
            context = context,
            metadata = metadata(plan, entry.generation)
        )

        return LearningApplicationMutationPlanRegistrationResult.Registered(
            object : LearningApplicationMutationPlanRegistration {
                override val plan: LearningApplicationMutationPlan = plan
                override val generation: LearningApplicationMutationPlanGeneration = entry.generation

                override fun remove(context: LogContext): Boolean {
                    val removed = plans.remove(plan.id, entry)
                    observability.record(
                        severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (removed) {
                            "LEARNING_APPLICATION_MUTATION_PLAN_REMOVED"
                        } else {
                            "LEARNING_APPLICATION_MUTATION_PLAN_REMOVAL_REJECTED"
                        },
                        message = if (removed) {
                            "learning application mutation plan removed"
                        } else {
                            "learning application mutation plan registration is no longer current"
                        },
                        context = context,
                        metadata = metadata(plan, entry.generation)
                    )
                    return removed
                }
            }
        )
    }

    fun find(id: LearningApplicationMutationPlanId): LearningApplicationMutationPlan? = plans[id]?.plan

    fun inspect(id: LearningApplicationMutationPlanId): LearningApplicationMutationPlanSnapshot? =
        plans[id]?.let { entry -> LearningApplicationMutationPlanSnapshot(entry.plan, entry.generation) }

    fun contains(id: LearningApplicationMutationPlanId): Boolean = plans.containsKey(id)

    fun snapshot(): List<LearningApplicationMutationPlan> = snapshotEntries().map { it.plan }

    fun snapshotEntries(): List<LearningApplicationMutationPlanSnapshot> = plans.values
        .map { LearningApplicationMutationPlanSnapshot(it.plan, it.generation) }
        .sortedWith(
            compareBy<LearningApplicationMutationPlanSnapshot> { it.plan.createdAt }
                .thenBy { it.plan.id.value }
        )

    private fun metadata(
        plan: LearningApplicationMutationPlan,
        generation: LearningApplicationMutationPlanGeneration
    ): Map<String, String> = buildMap {
        put("learningApplicationMutationPlanId", plan.id.value)
        put("learningApplicationMutationPlanGeneration", generation.value.toString())
        put("learningApplicationId", plan.application.applicationId.value)
        put("learningApplicationGeneration", plan.application.generation.value.toString())
        put("createdAt", plan.createdAt.toString())
        when (val destination = plan.destination) {
            is LearningApplicationMutationDestination.Memory -> {
                put("learningApplicationMutationDestination", "memory")
                put("memoryRecordId", destination.recordId.value)
            }
            is LearningApplicationMutationDestination.Knowledge -> {
                put("learningApplicationMutationDestination", "knowledge")
                put("knowledgeItemId", destination.itemId.value)
            }
        }
    }
}
