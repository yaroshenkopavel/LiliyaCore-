package pro.liliya.core.learning

import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.knowledge.KnowledgeComposition
import pro.liliya.core.knowledge.KnowledgeCreateResult
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.logging.LogContext
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
) : LearningApplicationMutationApplicationPort {
    private val foundation = mutations.foundation

    override fun apply(
        reference: LearningApplicationMutationReference
    ): LearningApplicationMutationApplicationResult {
        val root = foundation.rootContext(
            operation = "applyLearningApplicationMutation",
            component = "LearningApplicationMutation",
            metadata = referenceMetadata(reference)
        )
        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "LEARNING_APPLICATION_MUTATION_APPLY_STARTED",
            message = "controlled learning application mutation started",
            context = root
        )

        val claim = when (
            val result = mutations.claim(
                reference = reference,
                context = child(
                    root,
                    component = "LearningApplicationMutation",
                    operation = "claimLearningApplicationMutation"
                )
            )
        ) {
            is LearningApplicationMutationClaimResult.Claimed -> result.claim
            is LearningApplicationMutationClaimResult.Rejected -> {
                return observeResult(
                    root,
                    LearningApplicationMutationApplicationResult.ClaimRejected(result.reason)
                )
            }
        }

        val authorization = authorizationGate.authorize(
            reference = reference,
            context = child(
                root,
                component = "CapabilityAuthority",
                operation = "authorizeLearningApplicationMutation"
            )
        )
        if (authorization !is LearningApplicationMutationAuthorizationResult.Ready) {
            claim.release()
            return observeResult(
                root,
                LearningApplicationMutationApplicationResult.AuthorizationRejected(authorization)
            )
        }

        val result = when (val payload = claim.plan.payload) {
            is LearningApplicationMutationPayload.Memory -> applyMemory(root, reference, claim, payload)
            is LearningApplicationMutationPayload.Knowledge -> applyKnowledge(root, reference, claim, payload)
        }
        return observeResult(root, result)
    }

    private fun applyMemory(
        root: LogContext,
        reference: LearningApplicationMutationReference,
        claim: LearningApplicationMutationClaim,
        payload: LearningApplicationMutationPayload.Memory
    ): LearningApplicationMutationApplicationResult = when (
        val result = memory.remember(
            record = payload.record,
            context = child(
                root,
                component = "Memory",
                operation = "rememberLearningApplicationMutation",
                metadata = mapOf("memoryRecordId" to payload.record.id.value)
            )
        )
    ) {
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
        root: LogContext,
        reference: LearningApplicationMutationReference,
        claim: LearningApplicationMutationClaim,
        payload: LearningApplicationMutationPayload.Knowledge
    ): LearningApplicationMutationApplicationResult = when (
        val result = knowledge.create(
            item = payload.item,
            context = child(
                root,
                component = "Knowledge",
                operation = "createKnowledgeLearningApplicationMutation",
                metadata = mapOf("knowledgeItemId" to payload.item.id.value)
            )
        )
    ) {
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
        val receipt = LearningApplicationMutationApplicationReceipt(
            mutation = reference,
            target = target,
            downstream = downstream
        )
        if (claim.complete(receipt)) {
            return LearningApplicationMutationApplicationResult.Applied(receipt)
        }

        return if (compensate()) {
            LearningApplicationMutationApplicationResult.CompletionFailedCompensated(reference, target)
        } else {
            LearningApplicationMutationApplicationResult.PartialFailure(reference, target, downstream)
        }
    }

    private fun child(
        root: LogContext,
        component: String,
        operation: String,
        metadata: Map<String, String> = emptyMap()
    ): LogContext = foundation.childContext(
        parent = root,
        component = component,
        operation = operation,
        metadata = metadata
    )

    private fun observeResult(
        root: LogContext,
        result: LearningApplicationMutationApplicationResult
    ): LearningApplicationMutationApplicationResult {
        val observation = when (result) {
            is LearningApplicationMutationApplicationResult.Applied -> Observation(
                DiagnosticSeverity.INFO,
                "LEARNING_APPLICATION_MUTATION_APPLIED",
                "controlled learning application mutation applied",
                resultMetadata(result)
            )
            is LearningApplicationMutationApplicationResult.ClaimRejected -> Observation(
                DiagnosticSeverity.WARNING,
                "LEARNING_APPLICATION_MUTATION_APPLY_CLAIM_REJECTED",
                "controlled learning application mutation claim rejected",
                mapOf("resultType" to "claim_rejected", "claimRejection" to result.reason.name.lowercase())
            )
            is LearningApplicationMutationApplicationResult.AuthorizationRejected -> Observation(
                DiagnosticSeverity.WARNING,
                "LEARNING_APPLICATION_MUTATION_APPLY_AUTHORIZATION_REJECTED",
                "controlled learning application mutation authorization rejected",
                mapOf("resultType" to "authorization_rejected", "authorizationResult" to authorizationType(result.result))
            )
            is LearningApplicationMutationApplicationResult.DownstreamRejected -> Observation(
                DiagnosticSeverity.WARNING,
                "LEARNING_APPLICATION_MUTATION_APPLY_DOWNSTREAM_REJECTED",
                "controlled learning application downstream mutation rejected",
                mapOf("resultType" to "downstream_rejected")
            )
            is LearningApplicationMutationApplicationResult.CompletionFailedCompensated -> Observation(
                DiagnosticSeverity.WARNING,
                "LEARNING_APPLICATION_MUTATION_APPLY_COMPLETION_COMPENSATED",
                "controlled learning application completion failed and downstream mutation was compensated",
                mapOf("resultType" to "completion_compensated", "target" to result.target.name.lowercase())
            )
            is LearningApplicationMutationApplicationResult.PartialFailure -> Observation(
                DiagnosticSeverity.ERROR,
                "LEARNING_APPLICATION_MUTATION_APPLY_PARTIAL_FAILURE",
                "controlled learning application mutation ended in partial failure",
                resultMetadata(result)
            )
        }
        foundation.observability.record(
            severity = observation.severity,
            code = observation.code,
            message = observation.message,
            context = root,
            metadata = observation.metadata
        )
        return result
    }

    private fun resultMetadata(
        result: LearningApplicationMutationApplicationResult.Applied
    ): Map<String, String> = downstreamMetadata(
        target = result.receipt.target,
        downstream = result.receipt.downstream
    ) + ("resultType" to "applied")

    private fun resultMetadata(
        result: LearningApplicationMutationApplicationResult.PartialFailure
    ): Map<String, String> = downstreamMetadata(
        target = result.target,
        downstream = result.downstream
    ) + ("resultType" to "partial_failure")

    private fun downstreamMetadata(
        target: LearningApplicationTarget,
        downstream: LearningApplicationDownstreamReference
    ): Map<String, String> = buildMap {
        put("target", target.name.lowercase())
        when (downstream) {
            is LearningApplicationDownstreamReference.Memory -> {
                put("memoryRecordId", downstream.recordId.value)
                put("memoryGeneration", downstream.generation.value.toString())
            }
            is LearningApplicationDownstreamReference.Knowledge -> {
                put("knowledgeItemId", downstream.itemId.value)
                put("knowledgeGeneration", downstream.generation.value.toString())
            }
        }
    }

    private fun authorizationType(
        result: LearningApplicationMutationAuthorizationResult
    ): String = when (result) {
        is LearningApplicationMutationAuthorizationResult.Ready -> "ready"
        is LearningApplicationMutationAuthorizationResult.MutationRejected ->
            "mutation_${result.reason.name.lowercase()}"
        is LearningApplicationMutationAuthorizationResult.PreflightRejected ->
            "preflight_${result.reason.name.lowercase()}"
        is LearningApplicationMutationAuthorizationResult.AuthorityDenied -> "authority_denied"
    }

    private fun referenceMetadata(reference: LearningApplicationMutationReference): Map<String, String> = mapOf(
        "learningApplicationMutationId" to reference.mutationId.value,
        "learningApplicationMutationGeneration" to reference.generation.value.toString()
    )

    private data class Observation(
        val severity: DiagnosticSeverity,
        val code: String,
        val message: String,
        val metadata: Map<String, String>
    )
}
