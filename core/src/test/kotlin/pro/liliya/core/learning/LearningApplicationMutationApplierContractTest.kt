package pro.liliya.core.learning

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.authority.CapabilityOwnershipResult
import pro.liliya.core.authority.DirectAuthorityGrant
import pro.liliya.core.authority.DirectAuthorityGrantOwnershipResult
import pro.liliya.core.capability.CapabilityDescriptor
import pro.liliya.core.capability.CapabilityProviderId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.knowledge.KnowledgeComposition
import pro.liliya.core.knowledge.KnowledgeItem
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeOrigin
import pro.liliya.core.knowledge.KnowledgeSourceId
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryComposition
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemoryRememberResult
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LearningApplicationMutationApplierContractTest {
    private data class Fixture(
        val candidates: LearningComposition,
        val decisions: LearningDecisionComposition,
        val policies: LearningPolicyComposition,
        val applications: LearningApplicationComposition,
        val mutations: LearningApplicationMutationComposition,
        val authority: CapabilityAuthorityComposition,
        val memory: MemoryComposition,
        val knowledge: KnowledgeComposition,
        val applier: LearningApplicationMutationApplier
    )

    private val principal = AuthorityPrincipal("learning-controller")

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "mutation-apply-${sequence.incrementAndGet()}" }
        )
        val candidates = LearningComposition(foundation)
        val decisions = LearningDecisionComposition(foundation)
        val policies = LearningPolicyComposition(foundation)
        val applications = LearningApplicationComposition(foundation)
        val mutations = LearningApplicationMutationComposition(foundation)
        val authority = CapabilityAuthorityComposition(foundation)
        val memory = MemoryComposition(foundation)
        val knowledge = KnowledgeComposition(foundation)
        val preflight = LearningApplicationPreflightValidator(applications, decisions, candidates, policies)
        val authorizer = LearningApplicationAuthorizer(preflight, authority)
        val gate = LearningApplicationMutationAuthorizationGate(mutations, authorizer)
        return Fixture(
            candidates,
            decisions,
            policies,
            applications,
            mutations,
            authority,
            memory,
            knowledge,
            LearningApplicationMutationApplier(mutations, gate, memory, knowledge)
        )
    }

    private fun installApplication(
        f: Fixture,
        target: LearningApplicationTarget
    ): LearningApplicationOwnership {
        val candidate = assertIs<LearningInstallResult.Installed>(
            f.candidates.install(
                LearningCandidate(
                    LearningCandidateId("candidate-1"),
                    LearningOrigin.Declared(LearningSourceId("test")),
                    "proposal",
                    Instant.parse("2026-08-29T10:10:00Z")
                )
            )
        ).ownership
        val decision = assertIs<LearningDecisionInstallResult.Installed>(
            f.decisions.install(
                LearningDecision(
                    LearningDecisionId("decision-1"),
                    LearningCandidateReference(candidate.candidate.id, candidate.generation),
                    LearningDecisionDisposition.APPROVE,
                    "approved",
                    Instant.parse("2026-08-29T10:11:00Z")
                )
            )
        ).ownership
        val policy = assertIs<LearningPolicyInstallResult.Installed>(
            f.policies.install(
                LearningPolicy(
                    LearningPolicyId("policy-1"),
                    "allow controlled application",
                    Instant.parse("2026-08-29T10:12:00Z")
                )
            )
        ).ownership
        return assertIs<LearningApplicationInstallResult.Installed>(
            f.applications.install(
                LearningApplicationIntent(
                    LearningApplicationId("application-1"),
                    LearningDecisionReference(decision.decision.id, decision.generation),
                    LearningPolicyReference(policy.policy.id, policy.generation),
                    target,
                    Instant.parse("2026-08-29T10:13:00Z")
                )
            )
        ).ownership
    }

    private fun registerCapabilityAndGrant(f: Fixture, target: LearningApplicationTarget) {
        assertIs<CapabilityOwnershipResult.Registered>(
            f.authority.registerCapability(
                CapabilityDescriptor(
                    LearningApplicationAuthorityContract.capability,
                    CapabilityProviderId("learning-application")
                )
            )
        )
        assertIs<DirectAuthorityGrantOwnershipResult.Registered>(
            f.authority.registerDirectGrant(
                DirectAuthorityGrant(
                    principal,
                    LearningApplicationAuthorityContract.capability,
                    LearningApplicationAuthorityContract.scopeFor(target)
                )
            )
        )
    }

    private fun memoryPlan(application: LearningApplicationOwnership): LearningApplicationMutationPlan =
        LearningApplicationMutationPlan(
            LearningApplicationMutationId("mutation-memory"),
            LearningApplicationIntentReference(application.intent.id, application.generation),
            principal,
            LearningApplicationTarget.MEMORY,
            LearningApplicationIdempotencyKey("idem-memory"),
            LearningApplicationMutationPayload.Memory(
                MemoryRecord(
                    MemoryRecordId("memory-applied"),
                    MemoryProvenance(MemorySourceId("learning-application")),
                    "sensitive learned memory",
                    Instant.parse("2026-08-29T10:14:00Z")
                )
            ),
            Instant.parse("2026-08-29T10:15:00Z")
        )

    private fun knowledgePlan(application: LearningApplicationOwnership): LearningApplicationMutationPlan =
        LearningApplicationMutationPlan(
            LearningApplicationMutationId("mutation-knowledge"),
            LearningApplicationIntentReference(application.intent.id, application.generation),
            principal,
            LearningApplicationTarget.KNOWLEDGE,
            LearningApplicationIdempotencyKey("idem-knowledge"),
            LearningApplicationMutationPayload.Knowledge(
                KnowledgeItem(
                    KnowledgeItemId("knowledge-applied"),
                    KnowledgeOrigin.Declared(KnowledgeSourceId("learning-application")),
                    "sensitive learned knowledge",
                    Instant.parse("2026-08-29T10:14:00Z")
                )
            ),
            Instant.parse("2026-08-29T10:15:00Z")
        )

    private fun prepare(f: Fixture, plan: LearningApplicationMutationPlan): LearningApplicationMutationOwnership =
        assertIs<LearningApplicationMutationPrepareResult.Prepared>(f.mutations.prepare(plan)).ownership

    private fun reference(ownership: LearningApplicationMutationOwnership) =
        LearningApplicationMutationReference(ownership.plan.id, ownership.generation)

    @Test
    fun authorized_memory_mutation_is_applied_once_and_completed() {
        val f = fixture()
        val application = installApplication(f, LearningApplicationTarget.MEMORY)
        registerCapabilityAndGrant(f, LearningApplicationTarget.MEMORY)
        val mutation = prepare(f, memoryPlan(application))

        val applied = assertIs<LearningApplicationMutationApplicationResult.Applied>(
            f.applier.apply(reference(mutation))
        )
        val downstream = assertIs<LearningApplicationDownstreamReference.Memory>(applied.receipt.downstream)

        assertEquals(MemoryRecordId("memory-applied"), downstream.recordId)
        assertNotNull(f.memory.inspect(downstream.recordId))
        assertFalse(f.mutations.contains(mutation.plan.id))
        assertTrue(f.mutations.isCompletedIdempotencyKey(mutation.plan.idempotencyKey))
    }

    @Test
    fun authorized_knowledge_mutation_is_applied_once_and_completed() {
        val f = fixture()
        val application = installApplication(f, LearningApplicationTarget.KNOWLEDGE)
        registerCapabilityAndGrant(f, LearningApplicationTarget.KNOWLEDGE)
        val mutation = prepare(f, knowledgePlan(application))

        val applied = assertIs<LearningApplicationMutationApplicationResult.Applied>(
            f.applier.apply(reference(mutation))
        )
        val downstream = assertIs<LearningApplicationDownstreamReference.Knowledge>(applied.receipt.downstream)

        assertEquals(KnowledgeItemId("knowledge-applied"), downstream.itemId)
        assertNotNull(f.knowledge.inspect(downstream.itemId))
        assertFalse(f.mutations.contains(mutation.plan.id))
        assertTrue(f.mutations.isCompletedIdempotencyKey(mutation.plan.idempotencyKey))
    }

    @Test
    fun authority_denial_causes_zero_downstream_write_and_releases_claim() {
        val f = fixture()
        val application = installApplication(f, LearningApplicationTarget.MEMORY)
        val mutation = prepare(f, memoryPlan(application))
        assertIs<CapabilityOwnershipResult.Registered>(
            f.authority.registerCapability(
                CapabilityDescriptor(
                    LearningApplicationAuthorityContract.capability,
                    CapabilityProviderId("learning-application")
                )
            )
        )

        assertIs<LearningApplicationMutationApplicationResult.AuthorizationRejected>(
            f.applier.apply(reference(mutation))
        )

        assertFalse(f.memory.contains(MemoryRecordId("memory-applied")))
        assertTrue(f.mutations.contains(mutation.plan.id))
        assertFalse(f.mutations.isCompletedIdempotencyKey(mutation.plan.idempotencyKey))
        assertIs<LearningApplicationMutationClaimResult.Claimed>(f.mutations.claim(reference(mutation)))
    }

    @Test
    fun downstream_conflict_keeps_prepared_mutation_retryable_without_completion_tombstone() {
        val f = fixture()
        val application = installApplication(f, LearningApplicationTarget.MEMORY)
        registerCapabilityAndGrant(f, LearningApplicationTarget.MEMORY)
        val plan = memoryPlan(application)
        val mutation = prepare(f, plan)
        val payload = assertIs<LearningApplicationMutationPayload.Memory>(plan.payload)
        assertIs<MemoryRememberResult.Remembered>(f.memory.remember(payload.record))

        assertIs<LearningApplicationMutationApplicationResult.DownstreamRejected>(
            f.applier.apply(reference(mutation))
        )

        assertTrue(f.mutations.contains(mutation.plan.id))
        assertFalse(f.mutations.isCompletedIdempotencyKey(mutation.plan.idempotencyKey))
        assertIs<LearningApplicationMutationClaimResult.Claimed>(f.mutations.claim(reference(mutation)))
    }

    @Test
    fun completed_exact_mutation_cannot_apply_a_second_downstream_write() {
        val f = fixture()
        val application = installApplication(f, LearningApplicationTarget.MEMORY)
        registerCapabilityAndGrant(f, LearningApplicationTarget.MEMORY)
        val mutation = prepare(f, memoryPlan(application))
        val ref = reference(mutation)

        assertIs<LearningApplicationMutationApplicationResult.Applied>(f.applier.apply(ref))
        assertEquals(1, f.memory.snapshotEntries().size)

        val second = assertIs<LearningApplicationMutationApplicationResult.ClaimRejected>(
            f.applier.apply(ref)
        )

        assertEquals(LearningApplicationMutationClaimRejection.MUTATION_MISSING, second.reason)
        assertEquals(1, f.memory.snapshotEntries().size)
        assertTrue(f.mutations.isCompletedIdempotencyKey(mutation.plan.idempotencyKey))
    }

    @Test
    fun fresh_application_target_mismatch_is_rejected_before_downstream_write() {
        val f = fixture()
        val application = installApplication(f, LearningApplicationTarget.KNOWLEDGE)
        registerCapabilityAndGrant(f, LearningApplicationTarget.KNOWLEDGE)
        val mutation = prepare(f, memoryPlan(application))

        val rejected = assertIs<LearningApplicationMutationApplicationResult.AuthorizationRejected>(
            f.applier.apply(reference(mutation))
        )
        val gateResult = assertIs<LearningApplicationMutationAuthorizationResult.MutationRejected>(
            rejected.result
        )

        assertEquals(LearningApplicationMutationAuthorizationRejection.TARGET_MISMATCH, gateResult.reason)
        assertFalse(f.memory.contains(MemoryRecordId("memory-applied")))
        assertTrue(f.mutations.contains(mutation.plan.id))
        assertFalse(f.mutations.isCompletedIdempotencyKey(mutation.plan.idempotencyKey))
        assertIs<LearningApplicationMutationClaimResult.Claimed>(f.mutations.claim(reference(mutation)))
    }
}
