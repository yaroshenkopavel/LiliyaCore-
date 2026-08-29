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
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryComposition
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class LearningApplicationMutationCompletedOutcomeContractTest {
    private data class Fixture(
        val candidates: LearningComposition,
        val decisions: LearningDecisionComposition,
        val policies: LearningPolicyComposition,
        val applications: LearningApplicationComposition,
        val mutations: LearningApplicationMutationComposition,
        val authority: CapabilityAuthorityComposition,
        val memory: MemoryComposition,
        val applier: LearningApplicationMutationApplier
    )

    private val principal = AuthorityPrincipal("learning-controller")

    private fun fixture(): Fixture {
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, InMemoryLogWriter()) },
            correlationIds = CorrelationIdGenerator { "completed-outcome-${sequence.incrementAndGet()}" }
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
            candidates = candidates,
            decisions = decisions,
            policies = policies,
            applications = applications,
            mutations = mutations,
            authority = authority,
            memory = memory,
            applier = LearningApplicationMutationApplier(mutations, gate, memory, knowledge)
        )
    }

    private fun installApplication(f: Fixture): LearningApplicationOwnership {
        val candidate = assertIs<LearningInstallResult.Installed>(
            f.candidates.install(
                LearningCandidate(
                    id = LearningCandidateId("candidate-outcome"),
                    origin = LearningOrigin.Declared(LearningSourceId("outcome-test")),
                    proposal = "outcome proposal",
                    createdAt = Instant.parse("2026-08-29T10:50:00Z")
                )
            )
        ).ownership
        val decision = assertIs<LearningDecisionInstallResult.Installed>(
            f.decisions.install(
                LearningDecision(
                    id = LearningDecisionId("decision-outcome"),
                    candidate = LearningCandidateReference(candidate.candidate.id, candidate.generation),
                    disposition = LearningDecisionDisposition.APPROVE,
                    rationale = "approved",
                    createdAt = Instant.parse("2026-08-29T10:51:00Z")
                )
            )
        ).ownership
        val policy = assertIs<LearningPolicyInstallResult.Installed>(
            f.policies.install(
                LearningPolicy(
                    id = LearningPolicyId("policy-outcome"),
                    rule = "allow controlled application",
                    createdAt = Instant.parse("2026-08-29T10:52:00Z")
                )
            )
        ).ownership
        return assertIs<LearningApplicationInstallResult.Installed>(
            f.applications.install(
                LearningApplicationIntent(
                    id = LearningApplicationId("application-outcome"),
                    decision = LearningDecisionReference(decision.decision.id, decision.generation),
                    policy = LearningPolicyReference(policy.policy.id, policy.generation),
                    target = LearningApplicationTarget.MEMORY,
                    createdAt = Instant.parse("2026-08-29T10:53:00Z")
                )
            )
        ).ownership
    }

    private fun grant(f: Fixture) {
        assertIs<CapabilityOwnershipResult.Registered>(
            f.authority.registerCapability(
                CapabilityDescriptor(
                    id = LearningApplicationAuthorityContract.capability,
                    providerId = CapabilityProviderId("learning-application")
                )
            )
        )
        assertIs<DirectAuthorityGrantOwnershipResult.Registered>(
            f.authority.registerDirectGrant(
                DirectAuthorityGrant(
                    principal = principal,
                    capability = LearningApplicationAuthorityContract.capability,
                    scope = LearningApplicationAuthorityContract.scopeFor(LearningApplicationTarget.MEMORY)
                )
            )
        )
    }

    @Test
    fun real_apply_persists_exact_structural_receipt_and_same_plan_replays_without_second_write() {
        val f = fixture()
        val application = installApplication(f)
        grant(f)
        val secret = "completed-outcome-secret"
        val plan = LearningApplicationMutationPlan(
            id = LearningApplicationMutationId("mutation-outcome"),
            application = LearningApplicationIntentReference(application.intent.id, application.generation),
            principal = principal,
            target = LearningApplicationTarget.MEMORY,
            idempotencyKey = LearningApplicationIdempotencyKey("idem-outcome"),
            payload = LearningApplicationMutationPayload.Memory(
                MemoryRecord(
                    id = MemoryRecordId("memory-outcome"),
                    provenance = MemoryProvenance(MemorySourceId("learning-application")),
                    content = secret,
                    createdAt = Instant.parse("2026-08-29T10:54:00Z")
                )
            ),
            createdAt = Instant.parse("2026-08-29T10:55:00Z")
        )
        val ownership = assertIs<LearningApplicationMutationPrepareResult.Prepared>(
            f.mutations.prepare(plan)
        ).ownership

        val applied = assertIs<LearningApplicationMutationApplicationResult.Applied>(
            f.applier.apply(
                LearningApplicationMutationReference(ownership.plan.id, ownership.generation)
            )
        )

        assertEquals(applied.receipt, f.mutations.completedOutcomeByMutationId(plan.id))
        assertEquals(applied.receipt, f.mutations.completedOutcomeByIdempotencyKey(plan.idempotencyKey))
        assertEquals(1, f.memory.snapshotEntries().size)

        val replay = assertIs<LearningApplicationMutationPrepareResult.AlreadyCompleted>(
            f.mutations.prepare(plan.copy())
        )
        assertEquals(applied.receipt, replay.receipt)
        assertEquals(1, f.memory.snapshotEntries().size)
        assertFalse(replay.toString().contains(secret))
    }
}
