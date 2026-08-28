package pro.liliya.core.learning

import pro.liliya.core.foundation.FoundationComposition

interface LearningPolicyOwnership {
    val policy: LearningPolicy
    val generation: LearningPolicyGeneration
    fun remove(): Boolean
}

sealed interface LearningPolicyInstallResult {
    data class Installed(val ownership: LearningPolicyOwnership) : LearningPolicyInstallResult
    data class Rejected(val reason: String) : LearningPolicyInstallResult
}

class LearningPolicyComposition(
    private val foundation: FoundationComposition
) {
    private val store = LearningPolicyStore(foundation.observability)

    fun install(policy: LearningPolicy): LearningPolicyInstallResult {
        val context = foundation.rootContext(
            operation = "installLearningPolicy",
            component = "LearningPolicy",
            metadata = policyMetadata(policy)
        )
        return when (val result = store.register(policy, context)) {
            is LearningPolicyRegistrationResult.Registered -> {
                val registration = result.registration
                LearningPolicyInstallResult.Installed(
                    ownership = object : LearningPolicyOwnership {
                        override val policy: LearningPolicy = registration.policy
                        override val generation: LearningPolicyGeneration = registration.generation

                        override fun remove(): Boolean = registration.remove(
                            foundation.rootContext(
                                operation = "removeLearningPolicy",
                                component = "LearningPolicy",
                                metadata = policyMetadata(policy) +
                                    ("learningPolicyGeneration" to generation.value.toString())
                            )
                        )
                    }
                )
            }

            is LearningPolicyRegistrationResult.Rejected ->
                LearningPolicyInstallResult.Rejected(result.reason)
        }
    }

    fun find(id: LearningPolicyId): LearningPolicy? = store.find(id)

    fun inspect(id: LearningPolicyId): LearningPolicySnapshot? = store.inspect(id)

    fun contains(id: LearningPolicyId): Boolean = store.contains(id)

    fun snapshot(): List<LearningPolicy> = store.snapshot()

    fun snapshotEntries(): List<LearningPolicySnapshot> = store.snapshotEntries()

    private fun policyMetadata(policy: LearningPolicy): Map<String, String> = mapOf(
        "learningPolicyId" to policy.id.value,
        "createdAt" to policy.createdAt.toString()
    )
}
