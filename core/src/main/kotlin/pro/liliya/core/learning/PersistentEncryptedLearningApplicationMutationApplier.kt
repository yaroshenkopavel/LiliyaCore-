package pro.liliya.core.learning

import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.knowledge.EncryptedPersistentKnowledgeComposition
import pro.liliya.core.knowledge.PersistentKnowledgeCreateResult
import pro.liliya.core.knowledge.PersistentKnowledgeMutationResult
import pro.liliya.core.logging.LogContext
import pro.liliya.core.memory.EncryptedPersistentMemoryComposition
import pro.liliya.core.memory.PersistentMemoryMutationResult
import pro.liliya.core.memory.PersistentMemoryRememberResult

/**
 * Durable/encrypted governed-learning mutation executor.
 *
 * The mutation plan lifecycle remains owned by PersistentLearningApplicationMutationComposition.
 * Authoritative downstream state is written only through encrypted persistent Memory/Knowledge.
 * If mutation completion cannot commit after a downstream write, exact downstream ownership is
 * compensated. A failed compensation is surfaced as PartialFailure.
 */
class PersistentEncryptedLearningApplicationMutationApplier(
    private val foundation: FoundationComposition,
    private val mutations: PersistentLearningApplicationMutationClaimPort,
    private val authorizationGate: LearningApplicationMutationAuthorizationGate,
    private val memory: EncryptedPersistentMemoryComposition,
    private val knowledge: EncryptedPersistentKnowledgeComposition
) : LearningApplicationMutationApplicationPort {
    constructor(
        foundation: FoundationComposition,
        mutations: PersistentLearningApplicationMutationComposition,
        authorizationGate: LearningApplicationMutationAuthorizationGate,
        memory: EncryptedPersistentMemoryComposition,
        knowledge: EncryptedPersistentKnowledgeComposition
    ) : this(
        foundation = foundation,
        mutations = mutations.claimPort(),
        authorizationGate = authorizationGate,
        memory = memory,
        knowledge = knowledge
    )

    constructor(
        foundation: FoundationComposition,
        mutations: EncryptedPersistentLearningApplicationMutationComposition,
        authorizationGate: LearningApplicationMutationAuthorizationGate,
        memory: EncryptedPersistentMemoryComposition,
        knowledge: EncryptedPersistentKnowledgeComposition
    ) : this(
        foundation = foundation,
        mutations = mutations.claimPort(),
        authorizationGate = authorizationGate,
        memory = memory,
        knowledge = knowledge
    )


    override fun apply(
        reference: LearningApplicationMutationReference
    ): LearningApplicationMutationApplicationResult {
        val root = foundation.rootContext(
            operation = "applyPersistentEncryptedLearningApplicationMutation",
            component = "LearningApplicationMutation",
            metadata = referenceMetadata(reference)
        )
        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "PERSISTENT_ENCRYPTED_LEARNING_MUTATION_APPLY_STARTED",
            message = "persistent encrypted controlled learning mutation started",
            context = root
        )

        val claim = when (val result = mutations.claim(reference)) {
            is PersistentLearningApplicationMutationClaimResult.Claimed -> result.claim
            is PersistentLearningApplicationMutationClaimResult.Rejected ->
                return observeResult(
                    root,
                    LearningApplicationMutationApplicationResult.ClaimRejected(result.reason)
                )
        }

        val authorization = authorizationGate.authorize(
            reference = reference,
            context = child(
                root,
                component = "CapabilityAuthority",
                operation = "authorizePersistentEncryptedLearningMutation"
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
            is LearningApplicationMutationPayload.Memory ->
                applyMemory(reference, claim, payload)
            is LearningApplicationMutationPayload.Knowledge ->
                applyKnowledge(reference, claim, payload)
        }
        return observeResult(root, result)
    }

    private fun applyMemory(
        reference: LearningApplicationMutationReference,
        claim: PersistentLearningApplicationMutationClaim,
        payload: LearningApplicationMutationPayload.Memory
    ): LearningApplicationMutationApplicationResult =
        when (val result = memory.remember(payload.record)) {
            is PersistentMemoryRememberResult.Rejected -> {
                claim.release()
                LearningApplicationMutationApplicationResult.DownstreamRejected(result.reason)
            }

            is PersistentMemoryRememberResult.Failed -> {
                claim.release()
                LearningApplicationMutationApplicationResult.DownstreamRejected(
                    "persistent encrypted Memory mutation failed"
                )
            }

            is PersistentMemoryRememberResult.Remembered -> {
                val downstream = LearningApplicationDownstreamReference.Memory(
                    recordId = result.ownership.record.id,
                    generation = result.ownership.generation
                )
                finish(
                    reference = reference,
                    claim = claim,
                    target = LearningApplicationTarget.MEMORY,
                    downstream = downstream,
                    compensate = {
                        result.ownership.remove() is PersistentMemoryMutationResult.Committed
                    }
                )
            }
        }

    private fun applyKnowledge(
        reference: LearningApplicationMutationReference,
        claim: PersistentLearningApplicationMutationClaim,
        payload: LearningApplicationMutationPayload.Knowledge
    ): LearningApplicationMutationApplicationResult =
        when (val result = knowledge.create(payload.item)) {
            is PersistentKnowledgeCreateResult.Rejected -> {
                claim.release()
                LearningApplicationMutationApplicationResult.DownstreamRejected(result.reason)
            }

            is PersistentKnowledgeCreateResult.Failed -> {
                claim.release()
                LearningApplicationMutationApplicationResult.DownstreamRejected(
                    "persistent encrypted Knowledge mutation failed"
                )
            }

            is PersistentKnowledgeCreateResult.Created -> {
                val downstream = LearningApplicationDownstreamReference.Knowledge(
                    itemId = result.ownership.item.id,
                    generation = result.ownership.generation
                )
                finish(
                    reference = reference,
                    claim = claim,
                    target = LearningApplicationTarget.KNOWLEDGE,
                    downstream = downstream,
                    compensate = {
                        result.ownership.remove() is PersistentKnowledgeMutationResult.Committed
                    }
                )
            }
        }

    private fun finish(
        reference: LearningApplicationMutationReference,
        claim: PersistentLearningApplicationMutationClaim,
        target: LearningApplicationTarget,
        downstream: LearningApplicationDownstreamReference,
        compensate: () -> Boolean
    ): LearningApplicationMutationApplicationResult {
        val receipt = LearningApplicationMutationApplicationReceipt(
            mutation = reference,
            target = target,
            downstream = downstream
        )
        return when (claim.complete(receipt)) {
            PersistentLearningApplicationMutationResult.Committed ->
                LearningApplicationMutationApplicationResult.Applied(receipt)

            is PersistentLearningApplicationMutationResult.Rejected,
            is PersistentLearningApplicationMutationResult.Failed ->
                if (safeCompensate(compensate)) {
                    LearningApplicationMutationApplicationResult.CompletionFailedCompensated(
                        mutation = reference,
                        target = target
                    )
                } else {
                    LearningApplicationMutationApplicationResult.PartialFailure(
                        mutation = reference,
                        target = target,
                        downstream = downstream
                    )
                }
        }
    }

    private fun safeCompensate(compensate: () -> Boolean): Boolean =
        try {
            compensate()
        } catch (_: Exception) {
            false
        }

    private fun child(
        root: LogContext,
        component: String,
        operation: String
    ): LogContext = foundation.childContext(
        parent = root,
        component = component,
        operation = operation
    )

    private fun observeResult(
        root: LogContext,
        result: LearningApplicationMutationApplicationResult
    ): LearningApplicationMutationApplicationResult {
        val metadata = when (result) {
            is LearningApplicationMutationApplicationResult.Applied ->
                downstreamMetadata(result.receipt.downstream) + ("resultType" to "applied")
            is LearningApplicationMutationApplicationResult.ClaimRejected ->
                mapOf("resultType" to "claim_rejected")
            is LearningApplicationMutationApplicationResult.AuthorizationRejected ->
                mapOf("resultType" to "authorization_rejected")
            is LearningApplicationMutationApplicationResult.DownstreamRejected ->
                mapOf("resultType" to "downstream_rejected")
            is LearningApplicationMutationApplicationResult.CompletionFailedCompensated ->
                mapOf(
                    "resultType" to "completion_compensated",
                    "target" to result.target.name.lowercase()
                )
            is LearningApplicationMutationApplicationResult.PartialFailure ->
                downstreamMetadata(result.downstream) + ("resultType" to "partial_failure")
        }
        val severity = when (result) {
            is LearningApplicationMutationApplicationResult.Applied -> DiagnosticSeverity.INFO
            is LearningApplicationMutationApplicationResult.PartialFailure -> DiagnosticSeverity.ERROR
            else -> DiagnosticSeverity.WARNING
        }
        foundation.observability.record(
            severity = severity,
            code = when (result) {
                is LearningApplicationMutationApplicationResult.Applied ->
                    "PERSISTENT_ENCRYPTED_LEARNING_MUTATION_APPLIED"
                is LearningApplicationMutationApplicationResult.PartialFailure ->
                    "PERSISTENT_ENCRYPTED_LEARNING_MUTATION_PARTIAL_FAILURE"
                else -> "PERSISTENT_ENCRYPTED_LEARNING_MUTATION_NOT_APPLIED"
            },
            message = when (result) {
                is LearningApplicationMutationApplicationResult.Applied ->
                    "persistent encrypted controlled learning mutation applied"
                is LearningApplicationMutationApplicationResult.PartialFailure ->
                    "persistent encrypted controlled learning mutation ended in partial failure"
                else -> "persistent encrypted controlled learning mutation was not applied"
            },
            context = root,
            metadata = metadata
        )
        return result
    }

    private fun downstreamMetadata(
        downstream: LearningApplicationDownstreamReference
    ): Map<String, String> = when (downstream) {
        is LearningApplicationDownstreamReference.Memory -> mapOf(
            "downstreamType" to "memory",
            "memoryGeneration" to downstream.generation.value.toString()
        )
        is LearningApplicationDownstreamReference.Knowledge -> mapOf(
            "downstreamType" to "knowledge",
            "knowledgeGeneration" to downstream.generation.value.toString()
        )
    }

    private fun referenceMetadata(
        reference: LearningApplicationMutationReference
    ): Map<String, String> = mapOf(
        "learningApplicationMutationGeneration" to reference.generation.value.toString()
    )
}
