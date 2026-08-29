package pro.liliya.core.orchestration

import pro.liliya.core.foundation.FoundationComposition

interface OrchestrationOwnership {
    val intent: OrchestrationIntent
    val generation: OrchestrationGeneration
    fun remove(): Boolean
}

sealed interface OrchestrationInstallResult {
    data class Installed(val ownership: OrchestrationOwnership) : OrchestrationInstallResult
    data class Rejected(val reason: String) : OrchestrationInstallResult
}

class OrchestrationComposition(
    private val foundation: FoundationComposition
) {
    private val store = OrchestrationIntentStore(foundation.observability)

    fun install(intent: OrchestrationIntent): OrchestrationInstallResult {
        val installContext = foundation.rootContext(
            operation = "installOrchestrationIntent",
            component = "Orchestration",
            metadata = intentMetadata(intent)
        )
        return when (val result = store.register(intent, installContext)) {
            is OrchestrationRegistrationResult.Registered -> {
                val registration = result.registration
                OrchestrationInstallResult.Installed(
                    ownership = object : OrchestrationOwnership {
                        override val intent: OrchestrationIntent = registration.intent
                        override val generation: OrchestrationGeneration = registration.generation

                        override fun remove(): Boolean = registration.remove(
                            foundation.childContext(
                                parent = installContext,
                                component = "Orchestration",
                                operation = "removeOrchestrationIntent",
                                metadata = intentMetadata(intent) +
                                    ("orchestrationGeneration" to generation.value.toString())
                            )
                        )
                    }
                )
            }

            is OrchestrationRegistrationResult.Rejected ->
                OrchestrationInstallResult.Rejected(result.reason)
        }
    }

    fun find(id: OrchestrationIntentId): OrchestrationIntent? = store.find(id)
    fun inspect(id: OrchestrationIntentId): OrchestrationSnapshot? = store.inspect(id)
    fun contains(id: OrchestrationIntentId): Boolean = store.contains(id)
    fun snapshot(): List<OrchestrationIntent> = store.snapshot()
    fun snapshotEntries(): List<OrchestrationSnapshot> = store.snapshotEntries()

    private fun intentMetadata(intent: OrchestrationIntent): Map<String, String> = buildMap {
        put("orchestrationIntentId", intent.id.value)
        put("decisionId", intent.decision.decisionId.value)
        put("decisionGeneration", intent.decision.generation.value.toString())
        put("selectedDecisionOptionId", intent.decision.selectedOptionId.value)
        put("createdAt", intent.createdAt.toString())
    }
}
