package pro.liliya.core.learning

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal interface LearningApplicationRegistration {
    val intent: LearningApplicationIntent
    val generation: LearningApplicationGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface LearningApplicationRegistrationResult {
    data class Registered(val registration: LearningApplicationRegistration) : LearningApplicationRegistrationResult
    data class Rejected(val reason: String) : LearningApplicationRegistrationResult
}

internal class LearningApplicationStore(
    private val observability: CoreObservability
) {
    private data class Entry(
        val generation: LearningApplicationGeneration,
        val intent: LearningApplicationIntent
    )

    private val nextGeneration = AtomicLong(0)
    private val intents = ConcurrentHashMap<LearningApplicationId, Entry>()

    fun register(
        intent: LearningApplicationIntent,
        context: LogContext
    ): LearningApplicationRegistrationResult {
        val entry = Entry(
            generation = LearningApplicationGeneration(nextGeneration.incrementAndGet()),
            intent = intent
        )
        val previous = intents.putIfAbsent(intent.id, entry)
        if (previous != null) {
            val reason = "learning application id is already registered"
            observability.record(
                severity = DiagnosticSeverity.WARNING,
                code = "LEARNING_APPLICATION_REGISTRATION_REJECTED",
                message = reason,
                context = context,
                metadata = metadata(intent, entry.generation) + ("rejectionReason" to reason)
            )
            return LearningApplicationRegistrationResult.Rejected(reason)
        }

        observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "LEARNING_APPLICATION_REGISTERED",
            message = "learning application intent registered",
            context = context,
            metadata = metadata(intent, entry.generation)
        )

        return LearningApplicationRegistrationResult.Registered(
            registration = object : LearningApplicationRegistration {
                override val intent: LearningApplicationIntent = intent
                override val generation: LearningApplicationGeneration = entry.generation

                override fun remove(context: LogContext): Boolean {
                    val removed = intents.remove(intent.id, entry)
                    observability.record(
                        severity = if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        code = if (removed) {
                            "LEARNING_APPLICATION_REMOVED"
                        } else {
                            "LEARNING_APPLICATION_REMOVAL_REJECTED"
                        },
                        message = if (removed) {
                            "learning application intent removed"
                        } else {
                            "learning application registration is no longer current"
                        },
                        context = context,
                        metadata = metadata(intent, entry.generation)
                    )
                    return removed
                }
            }
        )
    }

    fun find(id: LearningApplicationId): LearningApplicationIntent? = intents[id]?.intent

    fun inspect(id: LearningApplicationId): LearningApplicationSnapshot? = intents[id]?.let { entry ->
        LearningApplicationSnapshot(entry.intent, entry.generation)
    }

    fun contains(id: LearningApplicationId): Boolean = intents.containsKey(id)

    fun snapshot(): List<LearningApplicationIntent> = snapshotEntries().map { it.intent }

    fun snapshotEntries(): List<LearningApplicationSnapshot> = intents.values
        .map { LearningApplicationSnapshot(it.intent, it.generation) }
        .sortedWith(
            compareBy<LearningApplicationSnapshot> { it.intent.createdAt }
                .thenBy { it.intent.id.value }
        )

    private fun metadata(
        intent: LearningApplicationIntent,
        generation: LearningApplicationGeneration
    ): Map<String, String> = mapOf(
        "learningApplicationId" to intent.id.value,
        "learningApplicationGeneration" to generation.value.toString(),
        "learningDecisionId" to intent.decision.decisionId.value,
        "learningDecisionGeneration" to intent.decision.generation.value.toString(),
        "learningPolicyId" to intent.policy.policyId.value,
        "learningPolicyGeneration" to intent.policy.generation.value.toString(),
        "learningApplicationTarget" to intent.target.name.lowercase(),
        "createdAt" to intent.createdAt.toString()
    )
}
