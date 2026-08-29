package pro.liliya.core.orchestration

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal interface OrchestrationRegistration {
    val intent: OrchestrationIntent
    val generation: OrchestrationGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface OrchestrationRegistrationResult {
    data class Registered(val registration: OrchestrationRegistration) : OrchestrationRegistrationResult
    data class Rejected(val reason: String) : OrchestrationRegistrationResult
}

internal class OrchestrationIntentStore(
    private val observability: CoreObservability
) {
    private data class Entry(
        val generation: OrchestrationGeneration,
        val intent: OrchestrationIntent
    )

    private val nextGeneration = AtomicLong(0)
    private val intents = ConcurrentHashMap<OrchestrationIntentId, Entry>()

    fun register(
        intent: OrchestrationIntent,
        context: LogContext
    ): OrchestrationRegistrationResult {
        val entry = Entry(
            generation = OrchestrationGeneration(nextGeneration.incrementAndGet()),
            intent = intent
        )
        val previous = intents.putIfAbsent(intent.id, entry)
        if (previous != null) {
            val reason = "orchestration intent id is already registered"
            observability.record(
                DiagnosticSeverity.WARNING,
                "ORCHESTRATION_INTENT_REGISTRATION_REJECTED",
                reason,
                context,
                metadata(intent, previous.generation) + ("rejectionReason" to reason)
            )
            return OrchestrationRegistrationResult.Rejected(reason)
        }

        observability.record(
            DiagnosticSeverity.INFO,
            "ORCHESTRATION_INTENT_REGISTERED",
            "orchestration intent registered",
            context,
            metadata(intent, entry.generation)
        )

        return OrchestrationRegistrationResult.Registered(
            object : OrchestrationRegistration {
                override val intent: OrchestrationIntent = intent
                override val generation: OrchestrationGeneration = entry.generation

                override fun remove(context: LogContext): Boolean {
                    val removed = intents.remove(intent.id, entry)
                    observability.record(
                        if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        if (removed) "ORCHESTRATION_INTENT_REMOVED" else "ORCHESTRATION_INTENT_REMOVAL_REJECTED",
                        if (removed) "orchestration intent removed" else "orchestration intent registration is no longer current",
                        context,
                        metadata(intent, entry.generation)
                    )
                    return removed
                }
            }
        )
    }

    fun find(id: OrchestrationIntentId): OrchestrationIntent? = intents[id]?.intent

    fun inspect(id: OrchestrationIntentId): OrchestrationSnapshot? = intents[id]?.let { entry ->
        OrchestrationSnapshot(entry.intent, entry.generation)
    }

    fun contains(id: OrchestrationIntentId): Boolean = intents.containsKey(id)

    fun snapshot(): List<OrchestrationIntent> = snapshotEntries().map { it.intent }

    fun snapshotEntries(): List<OrchestrationSnapshot> = intents.values
        .map { OrchestrationSnapshot(it.intent, it.generation) }
        .sortedWith(compareBy<OrchestrationSnapshot> { it.intent.createdAt }.thenBy { it.intent.id.value })

    private fun metadata(
        intent: OrchestrationIntent,
        generation: OrchestrationGeneration
    ): Map<String, String> = buildMap {
        put("orchestrationIntentId", intent.id.value)
        put("orchestrationGeneration", generation.value.toString())
        put("decisionId", intent.decision.decisionId.value)
        put("decisionGeneration", intent.decision.generation.value.toString())
        put("selectedDecisionOptionId", intent.decision.selectedOptionId.value)
        put("createdAt", intent.createdAt.toString())
    }
}
