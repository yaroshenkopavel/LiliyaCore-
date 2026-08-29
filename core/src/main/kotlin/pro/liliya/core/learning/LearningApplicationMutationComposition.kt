package pro.liliya.core.learning

import pro.liliya.core.foundation.FoundationComposition

interface LearningApplicationMutationOwnership {
    val plan: LearningApplicationMutationPlan
    val generation: LearningApplicationMutationGeneration
    fun remove(): Boolean
}

interface LearningApplicationMutationClaim {
    val plan: LearningApplicationMutationPlan
    val reference: LearningApplicationMutationReference
    fun release(): Boolean
    fun complete(): Boolean
}

sealed interface LearningApplicationMutationPrepareResult {
    data class Prepared(
        val ownership: LearningApplicationMutationOwnership
    ) : LearningApplicationMutationPrepareResult

    data class Rejected(
        val reason: String
    ) : LearningApplicationMutationPrepareResult
}

sealed interface LearningApplicationMutationClaimResult {
    data class Claimed(
        val claim: LearningApplicationMutationClaim
    ) : LearningApplicationMutationClaimResult

    data class Rejected(
        val reason: LearningApplicationMutationClaimRejection
    ) : LearningApplicationMutationClaimResult
}

class LearningApplicationMutationComposition(
    private val foundation: FoundationComposition
) {
    private val store = LearningApplicationMutationStore(foundation.observability)

    fun prepare(plan: LearningApplicationMutationPlan): LearningApplicationMutationPrepareResult {
        val context = foundation.rootContext(
            operation = "prepareLearningApplicationMutation",
            component = "LearningApplicationMutation",
            metadata = mutationMetadata(plan)
        )

        return when (val result = store.register(plan, context)) {
            is LearningApplicationMutationRegistrationResult.Registered -> {
                val registration = result.registration
                LearningApplicationMutationPrepareResult.Prepared(
                    ownership = object : LearningApplicationMutationOwnership {
                        override val plan: LearningApplicationMutationPlan = registration.plan
                        override val generation: LearningApplicationMutationGeneration = registration.generation

                        override fun remove(): Boolean = registration.remove(
                            foundation.rootContext(
                                operation = "removeLearningApplicationMutation",
                                component = "LearningApplicationMutation",
                                metadata = mutationMetadata(plan) +
                                    ("learningApplicationMutationGeneration" to generation.value.toString())
                            )
                        )
                    }
                )
            }

            is LearningApplicationMutationRegistrationResult.Rejected ->
                LearningApplicationMutationPrepareResult.Rejected(result.reason)
        }
    }

    fun claim(reference: LearningApplicationMutationReference): LearningApplicationMutationClaimResult {
        val context = foundation.rootContext(
            operation = "claimLearningApplicationMutation",
            component = "LearningApplicationMutation",
            metadata = mapOf(
                "learningApplicationMutationId" to reference.mutationId.value,
                "learningApplicationMutationGeneration" to reference.generation.value.toString()
            )
        )
        return when (val result = store.claim(reference, context)) {
            is LearningApplicationMutationClaimRegistrationResult.Claimed -> {
                val registration = result.claim
                LearningApplicationMutationClaimResult.Claimed(
                    claim = object : LearningApplicationMutationClaim {
                        override val plan: LearningApplicationMutationPlan = registration.plan
                        override val reference: LearningApplicationMutationReference =
                            LearningApplicationMutationReference(plan.id, registration.generation)

                        override fun release(): Boolean = registration.release(
                            foundation.rootContext(
                                operation = "releaseLearningApplicationMutationClaim",
                                component = "LearningApplicationMutation",
                                metadata = mutationMetadata(plan) +
                                    ("learningApplicationMutationGeneration" to reference.generation.value.toString())
                            )
                        )

                        override fun complete(): Boolean = registration.complete(
                            foundation.rootContext(
                                operation = "completeLearningApplicationMutation",
                                component = "LearningApplicationMutation",
                                metadata = mutationMetadata(plan) +
                                    ("learningApplicationMutationGeneration" to reference.generation.value.toString())
                            )
                        )
                    }
                )
            }

            is LearningApplicationMutationClaimRegistrationResult.Rejected ->
                LearningApplicationMutationClaimResult.Rejected(result.reason)
        }
    }

    fun find(id: LearningApplicationMutationId): LearningApplicationMutationPlan? = store.find(id)

    fun inspect(id: LearningApplicationMutationId): LearningApplicationMutationSnapshot? = store.inspect(id)

    fun contains(id: LearningApplicationMutationId): Boolean = store.contains(id)

    fun findByIdempotencyKey(key: LearningApplicationIdempotencyKey): LearningApplicationMutationPlan? =
        store.findByIdempotencyKey(key)

    fun isCompletedIdempotencyKey(key: LearningApplicationIdempotencyKey): Boolean =
        store.isCompletedIdempotencyKey(key)

    fun snapshot(): List<LearningApplicationMutationPlan> = store.snapshot()

    fun snapshotEntries(): List<LearningApplicationMutationSnapshot> = store.snapshotEntries()

    private fun mutationMetadata(plan: LearningApplicationMutationPlan): Map<String, String> = buildMap {
        put("learningApplicationMutationId", plan.id.value)
        put("learningApplicationId", plan.application.applicationId.value)
        put("learningApplicationGeneration", plan.application.generation.value.toString())
        put("authorityPrincipal", plan.principal.value)
        put("learningApplicationTarget", plan.target.name.lowercase())
        put("idempotencyKey", plan.idempotencyKey.value)
        put("createdAt", plan.createdAt.toString())
        when (val payload = plan.payload) {
            is LearningApplicationMutationPayload.Memory -> put("memoryRecordId", payload.record.id.value)
            is LearningApplicationMutationPayload.Knowledge -> put("knowledgeItemId", payload.item.id.value)
        }
    }
}
