package pro.liliya.core.decision

import pro.liliya.core.foundation.FoundationComposition

interface DecisionOwnership {
    val decision: DecisionRecord
    val generation: DecisionGeneration
    fun remove(): Boolean
}

sealed interface DecisionInstallResult {
    data class Installed(val ownership: DecisionOwnership) : DecisionInstallResult
    data class Rejected(val reason: String) : DecisionInstallResult
}

class DecisionComposition(
    private val foundation: FoundationComposition
) {
    private val store = DecisionStore(foundation.observability)

    fun install(decision: DecisionRecord): DecisionInstallResult {
        val installContext = foundation.rootContext(
            operation = "installDecision",
            component = "Decision",
            metadata = decisionMetadata(decision)
        )
        return when (val result = store.register(decision, installContext)) {
            is DecisionRegistrationResult.Registered -> {
                val registration = result.registration
                DecisionInstallResult.Installed(
                    ownership = object : DecisionOwnership {
                        override val decision: DecisionRecord = registration.decision
                        override val generation: DecisionGeneration = registration.generation

                        override fun remove(): Boolean = registration.remove(
                            foundation.childContext(
                                parent = installContext,
                                component = "Decision",
                                operation = "removeDecision",
                                metadata = decisionMetadata(decision) +
                                    ("decisionGeneration" to generation.value.toString())
                            )
                        )
                    }
                )
            }

            is DecisionRegistrationResult.Rejected ->
                DecisionInstallResult.Rejected(result.reason)
        }
    }

    fun find(id: DecisionId): DecisionRecord? = store.find(id)
    fun inspect(id: DecisionId): DecisionSnapshot? = store.inspect(id)
    fun contains(id: DecisionId): Boolean = store.contains(id)
    fun snapshot(): List<DecisionRecord> = store.snapshot()
    fun snapshotEntries(): List<DecisionSnapshot> = store.snapshotEntries()

    private fun decisionMetadata(decision: DecisionRecord): Map<String, String> = buildMap {
        put("decisionId", decision.id.value)
        put("decisionInputCount", decision.inputs.size.toString())
        put("decisionPlanningInputCount", decision.inputs.count { it is DecisionInputReference.Planning }.toString())
        put("decisionReasoningInputCount", decision.inputs.count { it is DecisionInputReference.Reasoning }.toString())
        put("decisionOptionCount", decision.options.size.toString())
        put("selectedDecisionOptionId", decision.selectedOptionId.value)
        put("createdAt", decision.createdAt.toString())
    }
}
