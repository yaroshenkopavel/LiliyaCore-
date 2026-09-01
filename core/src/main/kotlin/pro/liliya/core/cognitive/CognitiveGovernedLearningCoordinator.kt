package pro.liliya.core.cognitive

import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.knowledge.KnowledgeItem
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeOrigin
import pro.liliya.core.knowledge.KnowledgeSourceId
import pro.liliya.core.knowledge.KnowledgeSourceReference
import pro.liliya.core.learning.LearningApplicationComposition
import pro.liliya.core.learning.LearningApplicationId
import pro.liliya.core.learning.LearningApplicationInstallResult
import pro.liliya.core.learning.LearningApplicationIntent
import pro.liliya.core.learning.LearningApplicationIntentReference
import pro.liliya.core.learning.LearningApplicationMutationApplier
import pro.liliya.core.learning.LearningApplicationMutationApplicationResult
import pro.liliya.core.learning.LearningApplicationMutationComposition
import pro.liliya.core.learning.LearningApplicationMutationId
import pro.liliya.core.learning.LearningApplicationMutationPayload
import pro.liliya.core.learning.LearningApplicationMutationPlan
import pro.liliya.core.learning.LearningApplicationMutationPrepareResult
import pro.liliya.core.learning.LearningApplicationMutationReference
import pro.liliya.core.learning.LearningApplicationIdempotencyKey
import pro.liliya.core.learning.LearningApplicationTarget
import pro.liliya.core.learning.LearningCandidateReference
import pro.liliya.core.learning.LearningCandidateSnapshot
import pro.liliya.core.learning.LearningComposition
import pro.liliya.core.learning.LearningDecision
import pro.liliya.core.learning.LearningDecisionComposition
import pro.liliya.core.learning.LearningDecisionDisposition
import pro.liliya.core.learning.LearningDecisionId
import pro.liliya.core.learning.LearningDecisionInstallResult
import pro.liliya.core.learning.LearningDecisionReference
import pro.liliya.core.learning.LearningPolicyComposition
import pro.liliya.core.learning.LearningPolicyReference
import pro.liliya.core.learning.LearningPolicySnapshot
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.memory.MemorySourceReference

enum class CognitiveGovernedLearningFailure {
    ATTEMPT_IN_PROGRESS,
    CANDIDATE_MISSING_OR_MISMATCH,
    POLICY_MISSING_OR_MISMATCH,
    GOVERNANCE_FAILED,
    GOVERNANCE_LIMIT_REJECTED,
    GOVERNANCE_TARGET_REJECTED,
    MATERIALIZER_FAILED,
    MATERIALIZER_REJECTED,
    MATERIALIZER_LIMIT_REJECTED,
    ARTIFACT_ID_OR_TIME_FAILED,
    ARTIFACT_ID_COLLISION,
    DECISION_INSTALL_FAILED,
    APPLICATION_INSTALL_FAILED,
    MUTATION_PREPARE_FAILED,
    MUTATION_APPLY_REJECTED,
    COMPENSATION_FAILED,
    COORDINATOR_FAILED
}

enum class CognitiveGovernedLearningTerminalStatus {
    GOVERNANCE_REJECTED,
    APPLIED,
    COMPLETION_COMPENSATED,
    REJECTED,
    PARTIAL_FAILURE
}

sealed interface CognitiveGovernedLearningResult {
    data class GovernanceRejected(
        val decision: LearningDecisionReference
    ) : CognitiveGovernedLearningResult

    data class Applied(
        val decision: LearningDecisionReference,
        val application: LearningApplicationIntentReference,
        val mutation: LearningApplicationMutationReference,
        val receipt: pro.liliya.core.learning.LearningApplicationMutationApplicationReceipt
    ) : CognitiveGovernedLearningResult

    data class CompletionCompensated(
        val decision: LearningDecisionReference,
        val application: LearningApplicationIntentReference,
        val mutation: LearningApplicationMutationReference,
        val target: LearningApplicationTarget
    ) : CognitiveGovernedLearningResult

    data class PartialFailure(
        val decision: LearningDecisionReference,
        val application: LearningApplicationIntentReference,
        val mutation: LearningApplicationMutationReference,
        val downstream: pro.liliya.core.learning.LearningApplicationDownstreamReference
    ) : CognitiveGovernedLearningResult

    data class AlreadyProcessed(
        val status: CognitiveGovernedLearningTerminalStatus
    ) : CognitiveGovernedLearningResult

    data class Rejected(
        val reason: CognitiveGovernedLearningFailure
    ) : CognitiveGovernedLearningResult
}

internal class CognitiveGovernedLearningCoordinator(
    private val scope: CognitiveRuntimeScopeId,
    private val learning: LearningComposition,
    private val policies: LearningPolicyComposition,
    private val policyReference: LearningPolicyReference,
    private val governance: CognitiveLearningGovernancePort,
    private val decisions: LearningDecisionComposition,
    private val materialization: CognitiveLearningApplicationMaterializationPort,
    private val applications: LearningApplicationComposition,
    private val mutations: LearningApplicationMutationComposition,
    private val mutationApplier: LearningApplicationMutationApplier,
    private val principal: AuthorityPrincipal,
    allowedTargets: List<LearningApplicationTarget>,
    private val artifactIds: CognitiveArtifactIdSource,
    private val timestamps: CognitiveTimestampSource,
    private val limits: CognitiveRuntimeLimits
) {
    private val allowedTargets = allowedTargets.distinct().toList()
    private val gate = Any()
    private val inProgress = mutableSetOf<LearningCandidateReference>()
    private val terminal = mutableMapOf<LearningCandidateReference, CognitiveGovernedLearningTerminalStatus>()

    init {
        require(this.allowedTargets.isNotEmpty()) { "governed learning allowed targets must not be empty" }
        require(principal.value.length <= limits.maxLearningSystemPrincipalChars) {
            "governed learning system principal exceeds configured limit"
        }
        require(principal.value.matches(Regex("[A-Za-z0-9._:-]+"))) {
            "governed learning system principal must be a structural non-secret identifier"
        }
    }

    fun process(reference: CognitiveLearningReference): CognitiveGovernedLearningResult {
        val candidateReference = LearningCandidateReference(reference.id, reference.generation)
        synchronized(gate) {
            terminal[candidateReference]?.let {
                return CognitiveGovernedLearningResult.AlreadyProcessed(it)
            }
            if (!inProgress.add(candidateReference)) {
                return CognitiveGovernedLearningResult.Rejected(
                    CognitiveGovernedLearningFailure.ATTEMPT_IN_PROGRESS
                )
            }
        }

        val result = try {
            processInternal(candidateReference)
        } catch (_: Exception) {
            CognitiveGovernedLearningResult.Rejected(CognitiveGovernedLearningFailure.COORDINATOR_FAILED)
        }

        synchronized(gate) {
            terminal[candidateReference] = terminalStatus(result)
            inProgress.remove(candidateReference)
        }
        return result
    }

    private fun processInternal(reference: LearningCandidateReference): CognitiveGovernedLearningResult {
        val candidate = exactCandidate(reference)
            ?: return rejected(CognitiveGovernedLearningFailure.CANDIDATE_MISSING_OR_MISMATCH)
        val policy = exactPolicy()
            ?: return rejected(CognitiveGovernedLearningFailure.POLICY_MISSING_OR_MISMATCH)

        val governanceResult = try {
            governance.evaluate(
                CognitiveLearningGovernanceRequest(
                    candidate = candidate,
                    policy = policy,
                    allowedTargets = allowedTargets,
                    budgets = CognitiveLearningGovernanceBudgets.from(limits)
                )
            )
        } catch (_: Exception) {
            return rejected(CognitiveGovernedLearningFailure.GOVERNANCE_FAILED)
        }

        exactStateFailure(candidate, policy)?.let { return rejected(it) }

        val rationale = when (governanceResult) {
            is CognitiveLearningGovernanceResult.Approved -> governanceResult.rationale
            is CognitiveLearningGovernanceResult.Rejected -> governanceResult.rationale
        }
        if (rationale.length > limits.maxLearningGovernanceRationaleChars) {
            return rejected(CognitiveGovernedLearningFailure.GOVERNANCE_LIMIT_REJECTED)
        }

        if (governanceResult is CognitiveLearningGovernanceResult.Approved &&
            governanceResult.target !in allowedTargets
        ) {
            return rejected(CognitiveGovernedLearningFailure.GOVERNANCE_TARGET_REJECTED)
        }

        val decisionOwnership = installDecision(
            candidate = candidate,
            disposition = if (governanceResult is CognitiveLearningGovernanceResult.Approved) {
                LearningDecisionDisposition.APPROVE
            } else {
                LearningDecisionDisposition.REJECT
            },
            rationale = rationale
        ) ?: return rejected(CognitiveGovernedLearningFailure.DECISION_INSTALL_FAILED)

        val decisionReference = LearningDecisionReference(
            decisionOwnership.decision.id,
            decisionOwnership.generation
        )
        if (governanceResult is CognitiveLearningGovernanceResult.Rejected) {
            return CognitiveGovernedLearningResult.GovernanceRejected(decisionReference)
        }

        val target = governanceResult.target
        exactStateFailure(candidate, policy)?.let {
            return compensateDecision(decisionOwnership, it)
        }

        val materialized = try {
            materialization.materialize(
                CognitiveLearningApplicationMaterializationRequest(
                    candidate = candidate,
                    policy = policy,
                    target = target,
                    budgets = CognitiveLearningApplicationBudgets.from(limits)
                )
            )
        } catch (_: Exception) {
            return compensateDecision(decisionOwnership, CognitiveGovernedLearningFailure.MATERIALIZER_FAILED)
        }
        val content = when (materialized) {
            is CognitiveLearningApplicationMaterializationResult.Rejected -> {
                return compensateDecision(
                    decisionOwnership,
                    CognitiveGovernedLearningFailure.MATERIALIZER_REJECTED
                )
            }
            is CognitiveLearningApplicationMaterializationResult.Succeeded -> materialized.content
        }
        if (content.length > limits.maxLearningMutationContentChars) {
            return compensateDecision(
                decisionOwnership,
                CognitiveGovernedLearningFailure.MATERIALIZER_LIMIT_REJECTED
            )
        }
        exactStateFailure(candidate, policy)?.let {
            return compensateDecision(decisionOwnership, it)
        }

        val allocated = mutableSetOf<String>()
        val prepared = try {
            Prepared(
                applicationId = LearningApplicationId(
                    nextId(CognitiveArtifactIdKind.LEARNING_APPLICATION, allocated)
                ),
                mutationId = LearningApplicationMutationId(
                    nextId(CognitiveArtifactIdKind.LEARNING_MUTATION, allocated)
                ),
                downstreamId = nextId(
                    if (target == LearningApplicationTarget.MEMORY) {
                        CognitiveArtifactIdKind.MEMORY_RECORD
                    } else {
                        CognitiveArtifactIdKind.KNOWLEDGE_ITEM
                    },
                    allocated
                ),
                applicationCreatedAt = timestamps.now(),
                payloadCreatedAt = timestamps.now(),
                mutationCreatedAt = timestamps.now()
            )
        } catch (_: Exception) {
            return compensateDecision(
                decisionOwnership,
                CognitiveGovernedLearningFailure.ARTIFACT_ID_OR_TIME_FAILED
            )
        }

        if (applications.contains(prepared.applicationId)) {
            return compensateDecision(
                decisionOwnership,
                CognitiveGovernedLearningFailure.ARTIFACT_ID_COLLISION
            )
        }

        val applicationOwnership = when (
            val installed = applications.install(
                LearningApplicationIntent(
                    id = prepared.applicationId,
                    decision = decisionReference,
                    policy = policyReference,
                    target = target,
                    createdAt = prepared.applicationCreatedAt
                )
            )
        ) {
            is LearningApplicationInstallResult.Installed -> installed.ownership
            is LearningApplicationInstallResult.Rejected -> {
                return compensateDecision(
                    decisionOwnership,
                    CognitiveGovernedLearningFailure.APPLICATION_INSTALL_FAILED
                )
            }
        }
        val applicationReference = LearningApplicationIntentReference(
            applicationOwnership.intent.id,
            applicationOwnership.generation
        )

        exactStateFailure(candidate, policy)?.let {
            return compensateApplicationAndDecision(applicationOwnership, decisionOwnership, it)
        }

        val candidateReference = LearningCandidateReference(candidate.candidate.id, candidate.generation)
        val provenance = CognitiveProvenance.learningMutationToken(scope, candidateReference, target).value
        val idempotency = CognitiveProvenance.learningIdempotencyToken(scope, candidateReference, target).value
        if (idempotency.length > limits.maxLearningIdempotencyKeyChars ||
            provenance.length > limits.maxProvenanceReferenceChars
        ) {
            return compensateApplicationAndDecision(
                applicationOwnership,
                decisionOwnership,
                CognitiveGovernedLearningFailure.ARTIFACT_ID_OR_TIME_FAILED
            )
        }

        val payload = when (target) {
            LearningApplicationTarget.MEMORY -> LearningApplicationMutationPayload.Memory(
                MemoryRecord(
                    id = MemoryRecordId(prepared.downstreamId),
                    provenance = MemoryProvenance(
                        sourceId = MemorySourceId(COGNITIVE_RUNTIME_LEARNING_SOURCE_ID),
                        sourceReference = MemorySourceReference(provenance)
                    ),
                    content = content,
                    createdAt = prepared.payloadCreatedAt
                )
            )
            LearningApplicationTarget.KNOWLEDGE -> LearningApplicationMutationPayload.Knowledge(
                KnowledgeItem(
                    id = KnowledgeItemId(prepared.downstreamId),
                    origin = KnowledgeOrigin.Declared(
                        sourceId = KnowledgeSourceId(COGNITIVE_RUNTIME_LEARNING_SOURCE_ID),
                        sourceReference = KnowledgeSourceReference(provenance)
                    ),
                    content = content,
                    createdAt = prepared.payloadCreatedAt
                )
            )
        }

        val mutationOwnership = when (
            val preparedMutation = mutations.prepare(
                LearningApplicationMutationPlan(
                    id = prepared.mutationId,
                    application = applicationReference,
                    principal = principal,
                    target = target,
                    idempotencyKey = LearningApplicationIdempotencyKey(idempotency),
                    payload = payload,
                    createdAt = prepared.mutationCreatedAt
                )
            )
        ) {
            is LearningApplicationMutationPrepareResult.Prepared -> preparedMutation.ownership
            is LearningApplicationMutationPrepareResult.AlreadyCompleted,
            is LearningApplicationMutationPrepareResult.Rejected -> {
                return compensateApplicationAndDecision(
                    applicationOwnership,
                    decisionOwnership,
                    CognitiveGovernedLearningFailure.MUTATION_PREPARE_FAILED
                )
            }
        }

        val mutationReference = LearningApplicationMutationReference(
            mutationOwnership.plan.id,
            mutationOwnership.generation
        )

        return when (val applied = mutationApplier.apply(mutationReference)) {
            is LearningApplicationMutationApplicationResult.Applied ->
                CognitiveGovernedLearningResult.Applied(
                    decision = decisionReference,
                    application = applicationReference,
                    mutation = mutationReference,
                    receipt = applied.receipt
                )

            is LearningApplicationMutationApplicationResult.CompletionFailedCompensated ->
                CognitiveGovernedLearningResult.CompletionCompensated(
                    decision = decisionReference,
                    application = applicationReference,
                    mutation = mutationReference,
                    target = applied.target
                )

            is LearningApplicationMutationApplicationResult.PartialFailure ->
                CognitiveGovernedLearningResult.PartialFailure(
                    decision = decisionReference,
                    application = applicationReference,
                    mutation = mutationReference,
                    downstream = applied.downstream
                )

            is LearningApplicationMutationApplicationResult.AuthorizationRejected,
            is LearningApplicationMutationApplicationResult.DownstreamRejected,
            is LearningApplicationMutationApplicationResult.ClaimRejected ->
                compensateMutationApplicationAndDecision(
                    mutationOwnership,
                    applicationOwnership,
                    decisionOwnership,
                    CognitiveGovernedLearningFailure.MUTATION_APPLY_REJECTED
                )
        }
    }

    private data class Prepared(
        val applicationId: LearningApplicationId,
        val mutationId: LearningApplicationMutationId,
        val downstreamId: String,
        val applicationCreatedAt: java.time.Instant,
        val payloadCreatedAt: java.time.Instant,
        val mutationCreatedAt: java.time.Instant
    )

    private fun exactCandidate(reference: LearningCandidateReference): LearningCandidateSnapshot? =
        learning.inspect(reference.candidateId)?.takeIf { it.generation == reference.generation }

    private fun exactPolicy(): LearningPolicySnapshot? =
        policies.inspect(policyReference.policyId)?.takeIf { it.generation == policyReference.generation }

    private fun sameCandidate(snapshot: LearningCandidateSnapshot): Boolean =
        learning.inspect(snapshot.candidate.id)?.let { current ->
            current.generation == snapshot.generation && current.candidate == snapshot.candidate
        } == true

    private fun samePolicy(snapshot: LearningPolicySnapshot): Boolean =
        policies.inspect(snapshot.policy.id)?.let { current ->
            current.generation == snapshot.generation && current.policy == snapshot.policy
        } == true

    private fun exactStateFailure(
        candidate: LearningCandidateSnapshot,
        policy: LearningPolicySnapshot
    ): CognitiveGovernedLearningFailure? = when {
        !sameCandidate(candidate) -> CognitiveGovernedLearningFailure.CANDIDATE_MISSING_OR_MISMATCH
        !samePolicy(policy) -> CognitiveGovernedLearningFailure.POLICY_MISSING_OR_MISMATCH
        else -> null
    }

    private fun installDecision(
        candidate: LearningCandidateSnapshot,
        disposition: LearningDecisionDisposition,
        rationale: String
    ): pro.liliya.core.learning.LearningDecisionOwnership? {
        val id = try {
            LearningDecisionId(nextId(CognitiveArtifactIdKind.LEARNING_DECISION, mutableSetOf()))
        } catch (_: Exception) {
            return null
        }
        if (decisions.contains(id)) return null
        val createdAt = try {
            timestamps.now()
        } catch (_: Exception) {
            return null
        }
        return when (
            val result = decisions.install(
                LearningDecision(
                    id = id,
                    candidate = LearningCandidateReference(candidate.candidate.id, candidate.generation),
                    disposition = disposition,
                    rationale = rationale,
                    createdAt = createdAt
                )
            )
        ) {
            is LearningDecisionInstallResult.Installed -> result.ownership
            is LearningDecisionInstallResult.Rejected -> null
        }
    }

    private fun nextId(kind: CognitiveArtifactIdKind, allocated: MutableSet<String>): String {
        val value = artifactIds.next(kind)
        require(value.isNotBlank()) { "cognitive governed learning artifact id must not be blank" }
        require(value.length <= limits.maxGeneratedArtifactIdChars) {
            "cognitive governed learning artifact id exceeds configured limit"
        }
        require(allocated.add(value)) {
            "duplicate cognitive governed learning artifact id in one attempt"
        }
        return value
    }

    private fun compensateDecision(
        decision: pro.liliya.core.learning.LearningDecisionOwnership,
        failure: CognitiveGovernedLearningFailure
    ): CognitiveGovernedLearningResult =
        if (safeRemove { decision.remove() }) rejected(failure)
        else rejected(CognitiveGovernedLearningFailure.COMPENSATION_FAILED)

    private fun compensateApplicationAndDecision(
        application: pro.liliya.core.learning.LearningApplicationOwnership,
        decision: pro.liliya.core.learning.LearningDecisionOwnership,
        failure: CognitiveGovernedLearningFailure
    ): CognitiveGovernedLearningResult {
        var complete = true
        if (!safeRemove { application.remove() }) complete = false
        if (!safeRemove { decision.remove() }) complete = false
        return rejected(if (complete) failure else CognitiveGovernedLearningFailure.COMPENSATION_FAILED)
    }

    private fun compensateMutationApplicationAndDecision(
        mutation: pro.liliya.core.learning.LearningApplicationMutationOwnership,
        application: pro.liliya.core.learning.LearningApplicationOwnership,
        decision: pro.liliya.core.learning.LearningDecisionOwnership,
        failure: CognitiveGovernedLearningFailure
    ): CognitiveGovernedLearningResult {
        var complete = true
        if (!safeRemove { mutation.remove() }) complete = false
        if (!safeRemove { application.remove() }) complete = false
        if (!safeRemove { decision.remove() }) complete = false
        return rejected(if (complete) failure else CognitiveGovernedLearningFailure.COMPENSATION_FAILED)
    }

    private fun safeRemove(remove: () -> Boolean): Boolean = try {
        remove()
    } catch (_: Exception) {
        false
    }

    private fun rejected(reason: CognitiveGovernedLearningFailure): CognitiveGovernedLearningResult =
        CognitiveGovernedLearningResult.Rejected(reason)

    private fun terminalStatus(
        result: CognitiveGovernedLearningResult
    ): CognitiveGovernedLearningTerminalStatus = when (result) {
        is CognitiveGovernedLearningResult.GovernanceRejected ->
            CognitiveGovernedLearningTerminalStatus.GOVERNANCE_REJECTED
        is CognitiveGovernedLearningResult.Applied ->
            CognitiveGovernedLearningTerminalStatus.APPLIED
        is CognitiveGovernedLearningResult.CompletionCompensated ->
            CognitiveGovernedLearningTerminalStatus.COMPLETION_COMPENSATED
        is CognitiveGovernedLearningResult.PartialFailure ->
            CognitiveGovernedLearningTerminalStatus.PARTIAL_FAILURE
        is CognitiveGovernedLearningResult.AlreadyProcessed -> result.status
        is CognitiveGovernedLearningResult.Rejected ->
            CognitiveGovernedLearningTerminalStatus.REJECTED
    }
}
