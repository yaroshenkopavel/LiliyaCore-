package pro.liliya.core.learning

import pro.liliya.core.knowledge.KnowledgeComposition
import pro.liliya.core.knowledge.KnowledgeCreateResult
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.memory.MemoryComposition
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemoryRememberResult

sealed interface LearningApplicationDownstreamReference {
    data class Memory(
        val recordId: MemoryRecordId,
        val generation: MemoryGeneration
    ) : LearningApplicationDownstreamReference

    data class Knowledge(
        val itemId: KnowledgeItemId,
        val generation: KnowledgeGeneration
    ) : LearningApplicationDownstreamReference
}

data class LearningApplicationMutationApplicationReceipt(
    val mutation: LearningApplicationMutationReference,
    val target: LearningApplicationTarget,
    val downstream: LearningApplicationDownstreamReference
)

sealed interface LearningApplicationMutationApplicationResult {
    data class Applied(
        val receipt: LearningApplicationMutationApplicationReceipt
    ) : LearningApplicationMutationApplicationResult

    data class ClaimRejected(
        val reason: LearningApplicationMutationClaimRejection
    ) : LearningApplicationMutationApplicationResult

    data class AuthorizationRejected(
        val result: LearningApplicationMutationAuthorizationResult
    ) : LearningApplicationMutationApplicationResult

    data class DownstreamRejected(
        val reason: String
    ) : LearningApplicationMutationApplicationResult

    data class CompletionFailedCompensated(
        val mutation: LearningApplicationMutationReference,
        val target: LearningApplicationTarget
    ) : LearningApplicationMutationApplicationResult

    data class PartialFailure(
        val mutation: LearningApplicationMutationReference,
        val target: LearningApplicationTarget,
        val downstream: LearningApplicationDownstreamReference
    ) : LearningApplicationMutationApplicationResult
}

class LearningApplicationMutationApplier(
    private val mutations: LearningApplicationMutationComposition,
    private val authorizationGate: LearningApplicationMutationAuthorizationGate,
    private val memory: MemoryComposition,
    private val knowledge: KnowledgeComposition
) {
    fun apply(
        reference: LearningApplicationMutationReference
    ): LearningApplicationMutationApplicationResult {
        val claim = when (val result = mutations.claim(reference)) {
            is LearningApplicationMutationClaimResult.Claimed -> result.claim
            is LearningApplicationMutationClaimResult.Rejected -> {
                return LearningApplicationMutationApplicationResult.ClaimRejected(result.reason)
            }
        }

        val authorization = authorizationGate.authorize(reference)
        if (authorization !is LearningApplicationMutationAuthorizationResult.Ready) {
            claim.release()
            return LearningApplicationMutationApplicationResult.AuthorizationRejected(authorization)
        }

        return when (val payload = claim.plan.payload) {
            is LearningApplicationMutationPayload.Memory -> applyMemory(reference, claim, payload)
            is LearningApplicationMutationPayload.Knowledge -> applyKnowledge(reference, claim, payload)
        }
    }

    private fun applyMemory(
        reference: LearningApplicationMutationReference,
        claim: LearningApplicationMutationClaim,
        payload: LearningApplicationMutationPayload.Memory
    ): LearningApplicationMutationApplicationResult = when (val result = memory.remember(payload.record)) {
        is MemoryRememberResult.Rejected -> {
            claim.release()
            LearningApplicationMutationApplicationResult.DownstreamRejected(result.reason)
        }

        is MemoryRememberResult.Remembered -> {
            val downstream = LearningApplicationDownstreamReference.Memory(
                recordId = result.ownership.record.id,
                generation = result.ownership.generation
            )
            finish(reference, claim, LearningApplicationTarget.MEMORY, downstream) {
                result.ownership.remove()
            }
        }
    }

    private fun applyKnowledge(
        reference: LearningApplicationMutationReference,
        claim: LearningApplicationMutationClaim,
        payload: LearningApplicationMutationPayload.Knowledge
    ): LearningApplicationMutationApplicationResult = when (val result = knowledge.create(payload.item)) {
        is KnowledgeCreateResult.Rejected -> {
            claim.release()
            LearningApplicationMutationApplicationResult.DownstreamRejected(result.reason)
        }

        is KnowledgeCreateResult.Created -> {
            val downstream = LearningApplicationDownstreamReference.Knowledge(
                itemId = result.ownership.item.id,
                generation = result.ownership.generation
            )
            finish(reference, claim, LearningApplicationTarget.KNOWLEDGE, downstream) {
                result.ownership.remove()
            }
        }
    }

    private fun finish(
        reference: LearningApplicationMutationReference,
        claim: LearningApplicationMutationClaim,
        target: LearningApplicationTarget,
        downstream: LearningApplicationDownstreamReference,
        compensate: () -> Boolean
    ): LearningApplicationMutationApplicationResult {
        if (claim.complete()) {
            return LearningApplicationMutationApplicationResult.Applied(
                LearningApplicationMutationApplicationReceipt(
                    mutation = reference,
                    target = target,
                    downstream = downstream
                )
            )
        }

        return if (compensate()) {
            LearningApplicationMutationApplicationResult.CompletionFailedCompensated(reference, target)
        } else {
            LearningApplicationMutationApplicationResult.PartialFailure(reference, target, downstream)
        }
    }
}
