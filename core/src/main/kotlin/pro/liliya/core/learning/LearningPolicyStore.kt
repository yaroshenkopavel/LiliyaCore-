package pro.liliya.core.learning

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal interface LearningPolicyRegistration {
    val policy: LearningPolicy
    val generation: LearningPolicyGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface LearningPolicyRegistrationResult {
    data class Registered(val registration: LearningPolicyRegistration) : LearningPolicyRegistrationResult
    data class Rejected(val reason: String) : LearningPolicyRegistrationResult
}

internal class LearningPolicyStore(
    private val observability: CoreObservability
) {
    private data class Entry(
        val generation: LearningPolicyGeneration,
        val policy: LearningPolicy
    )

    private val nextGeneration = AtomicLong(0)
    private val policies = ConcurrentHashMap<LearningPolicyId, Entry>()

    fun register(policy: LearningPolicy, context: LogContext): LearningPolicyRegistrationResult {
        val entry = Entry(
            generation = LearningPolicyGeneration(nextGeneration.incrementAndGet()),
            policy = policy
        )
        val previous = policies.putIfAbsent(policy.id, entry)
        if (previous != null) {
            val reason = "learning policy id is already registered"
            observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "LEARNING_POLICY_REGISTRATION_REJECTED",
                message = reason,
                context = context,
                metadata = metadata(policy, entry.generation) + ("rejectionReason" to reason)
            )
            return LearningPolicyRegistrationResult.Rejected(reason)
        }

        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "LEARNING_POLICY_REGISTERED",
            message = "learning policy registered",
            context = context,
            metadata = metadata(policy, entry.generation)
        )

        return LearningPolicyRegistrationResult.Registered(
            registration = object : LearningPolicyRegistration {
                override val policy: LearningPolicy = policy
                override val generation: LearningPolicyGeneration = entry.generation

                override fun remove(context: LogContext): Boolean {
                    val removed = policies.remove(policy.id, entry)
                    observability.record(
                        severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (removed) "LEARNING_POLICY_REMOVED" else "LEARNING_POLICY_REMOVAL_REJECTED",
                        message = if (removed) "learning policy removed" else "learning policy registration is no longer current",
                        context = context,
                        metadata = metadata(policy, entry.generation)
                    )
                    return removed
                }
            }
        )
    }

    fun find(id: LearningPolicyId): LearningPolicy? = policies[id]?.policy

    fun inspect(id: LearningPolicyId): LearningPolicySnapshot? = policies[id]?.let { entry ->
        LearningPolicySnapshot(entry.policy, entry.generation)
    }

    fun contains(id: LearningPolicyId): Boolean = policies.containsKey(id)

    fun snapshot(): List<LearningPolicy> = snapshotEntries().map { it.policy }

    fun snapshotEntries(): List<LearningPolicySnapshot> = policies.values
        .map { LearningPolicySnapshot(it.policy, it.generation) }
        .sortedWith(compareBy<LearningPolicySnapshot> { it.policy.createdAt }.thenBy { it.policy.id.value })

    private fun metadata(
        policy: LearningPolicy,
        generation: LearningPolicyGeneration
    ): Map<String, String> = mapOf(
        "learningPolicyId" to policy.id.value,
        "learningPolicyGeneration" to generation.value.toString(),
        "createdAt" to policy.createdAt.toString()
    )
}
