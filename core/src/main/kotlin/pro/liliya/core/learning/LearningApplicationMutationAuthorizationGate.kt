package pro.liliya.core.learning

/** Exact structural reference to one prepared mutation lifecycle generation. */
data class LearningApplicationMutationReference(
    val mutationId: LearningApplicationMutationId,
    val generation: LearningApplicationMutationGeneration
)

/**
 * Evidence that one exact prepared mutation was current both before and after
 * a fresh LearningApplicationAuthorizer check.
 *
 * This receipt is not durable permission to mutate downstream state. A future
 * mutation executor must still claim/serialize the exact mutation and perform
 * the write as one controlled operation.
 */
data class LearningApplicationMutationAuthorizationReceipt(
    val mutation: LearningApplicationMutationReference,
    val applicationAuthorization: LearningApplicationAuthorizationReceipt
)

enum class LearningApplicationMutationAuthorizationRejection {
    MUTATION_MISSING,
    MUTATION_GENERATION_MISMATCH,
    TARGET_MISMATCH,
    MUTATION_CHANGED_DURING_AUTHORIZATION
}

sealed interface LearningApplicationMutationAuthorizationResult {
    data class Ready(
        val receipt: LearningApplicationMutationAuthorizationReceipt
    ) : LearningApplicationMutationAuthorizationResult

    data class MutationRejected(
        val reason: LearningApplicationMutationAuthorizationRejection
    ) : LearningApplicationMutationAuthorizationResult

    data class PreflightRejected(
        val reason: LearningApplicationPreflightRejection
    ) : LearningApplicationMutationAuthorizationResult

    data class AuthorityDenied(
        val reason: String
    ) : LearningApplicationMutationAuthorizationResult
}

class LearningApplicationMutationAuthorizationGate(
    private val mutations: LearningApplicationMutationComposition,
    private val authorizer: LearningApplicationAuthorizer
) {
    fun authorize(
        reference: LearningApplicationMutationReference
    ): LearningApplicationMutationAuthorizationResult {
        val before = mutations.inspect(reference.mutationId)
            ?: return LearningApplicationMutationAuthorizationResult.MutationRejected(
                LearningApplicationMutationAuthorizationRejection.MUTATION_MISSING
            )
        if (before.generation != reference.generation) {
            return LearningApplicationMutationAuthorizationResult.MutationRejected(
                LearningApplicationMutationAuthorizationRejection.MUTATION_GENERATION_MISMATCH
            )
        }

        val plan = before.plan
        val authorization = when (val result = authorizer.authorize(plan.application, plan.principal)) {
            is LearningApplicationAuthorizationResult.Authorized -> result.receipt
            is LearningApplicationAuthorizationResult.PreflightRejected -> {
                return LearningApplicationMutationAuthorizationResult.PreflightRejected(result.reason)
            }
            is LearningApplicationAuthorizationResult.Denied -> {
                return LearningApplicationMutationAuthorizationResult.AuthorityDenied(result.reason)
            }
        }

        if (authorization.preflight.target != plan.target) {
            return LearningApplicationMutationAuthorizationResult.MutationRejected(
                LearningApplicationMutationAuthorizationRejection.TARGET_MISMATCH
            )
        }

        val after = mutations.inspect(reference.mutationId)
            ?: return LearningApplicationMutationAuthorizationResult.MutationRejected(
                LearningApplicationMutationAuthorizationRejection.MUTATION_CHANGED_DURING_AUTHORIZATION
            )
        if (after.generation != reference.generation || after.plan !== plan) {
            return LearningApplicationMutationAuthorizationResult.MutationRejected(
                LearningApplicationMutationAuthorizationRejection.MUTATION_CHANGED_DURING_AUTHORIZATION
            )
        }

        return LearningApplicationMutationAuthorizationResult.Ready(
            LearningApplicationMutationAuthorizationReceipt(
                mutation = reference,
                applicationAuthorization = authorization
            )
        )
    }
}
