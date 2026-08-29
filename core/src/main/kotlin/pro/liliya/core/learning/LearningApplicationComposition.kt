package pro.liliya.core.learning

import pro.liliya.core.foundation.FoundationComposition

interface LearningApplicationOwnership {
    val intent: LearningApplicationIntent
    val generation: LearningApplicationGeneration
    fun remove(): Boolean
}

sealed interface LearningApplicationInstallResult {
    data class Installed(val ownership: LearningApplicationOwnership) : LearningApplicationInstallResult
    data class Rejected(val reason: String) : LearningApplicationInstallResult
}

class LearningApplicationComposition(
    private val foundation: FoundationComposition
) {
    private val store = LearningApplicationStore(foundation.observability)

    fun install(intent: LearningApplicationIntent): LearningApplicationInstallResult {
        val context = foundation.rootContext(
            operation = "installLearningApplicationIntent",
            component = "LearningApplication",
            metadata = applicationMetadata(intent)
        )
        return when (val result = store.register(intent, context)) {
            is LearningApplicationRegistrationResult.Registered -> {
                val registration = result.registration
                LearningApplicationInstallResult.Installed(
                    ownership = object : LearningApplicationOwnership {
                        override val intent: LearningApplicationIntent = registration.intent
                        override val generation: LearningApplicationGeneration = registration.generation

                        override fun remove(): Boolean = registration.remove(
                            foundation.rootContext(
                                operation = "removeLearningApplicationIntent",
                                component = "LearningApplication",
                                metadata = applicationMetadata(intent) +
                                    ("learningApplicationGeneration" to generation.value.toString())
                            )
                        )
                    }
                )
            }

            is LearningApplicationRegistrationResult.Rejected ->
                LearningApplicationInstallResult.Rejected(result.reason)
        }
    }

    fun find(id: LearningApplicationId): LearningApplicationIntent? = store.find(id)

    fun inspect(id: LearningApplicationId): LearningApplicationSnapshot? = store.inspect(id)

    fun contains(id: LearningApplicationId): Boolean = store.contains(id)

    fun snapshot(): List<LearningApplicationIntent> = store.snapshot()

    fun snapshotEntries(): List<LearningApplicationSnapshot> = store.snapshotEntries()

    private fun applicationMetadata(intent: LearningApplicationIntent): Map<String, String> = mapOf(
        "learningApplicationId" to intent.id.value,
        "learningDecisionId" to intent.decision.decisionId.value,
        "learningDecisionGeneration" to intent.decision.generation.value.toString(),
        "learningPolicyId" to intent.policy.policyId.value,
        "learningPolicyGeneration" to intent.policy.generation.value.toString(),
        "learningApplicationTarget" to intent.target.name.lowercase(),
        "createdAt" to intent.createdAt.toString()
    )
}
