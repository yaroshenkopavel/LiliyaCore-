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
import pro.liliya.core.diagnostics.DiagnosticEvent
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
import pro.liliya.core.logging.LogEvent
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryComposition
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LearningApplicationMutationApplierCorrelationContractTest {
    private data class Fixture(
        val candidates: LearningComposition,
        val decisions: LearningDecisionComposition,
        val policies: LearningPolicyComposition,
        val applications: LearningApplicationComposition,
        val mutations: LearningApplicationMutationComposition,
        val authority: CapabilityAuthorityComposition,
        val memory: MemoryComposition,
        val knowledge: KnowledgeComposition,
        val applier: LearningApplicationMutationApplier,
        val logs: InMemoryLogWriter,
        val diagnostics: InMemoryDiagnosticSink
    )

    private val principal = AuthorityPrincipal("learning-controller")

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(diagnostics),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "apply-correlation-${sequence.incrementAndGet()}" }
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
            knowledge = knowledge,
            applier = LearningApplicationMutationApplier(mutations, gate, memory, knowledge),
            logs = logs,
            diagnostics = diagnostics
        )
    }

    private fun installApplication(
        f: Fixture,
        target: LearningApplicationTarget
    ): LearningApplicationOwnership {
        val candidate = assertIs<LearningInstallResult.Installed>(
            f.candidates.install(
                LearningCandidate(
                    id = LearningCandidateId("candidate-correlation"),
                    origin = LearningOrigin.Declared(LearningSourceId("correlation-test")),
                    proposal = "correlation proposal",
                    createdAt = Instant.parse("2026-08-29T10:40:00Z")
                )
            )
        ).ownership
        val decision = assertIs<LearningDecisionInstallResult.Installed>(
            f.decisions.install(
                LearningDecision(
                    id = LearningDecisionId("decision-correlation"),
                    candidate = LearningCandidateReference(candidate.candidate.id, candidate.generation),
                    disposition = LearningDecisionDisposition.APPROVE,
                    rationale = "approved",
                    createdAt = Instant.parse("2026-08-29T10:41:00Z")
                )
            )
        ).ownership
        val policy = assertIs<LearningPolicyInstallResult.Installed>(
            f.policies.install(
                LearningPolicy(
                    id = LearningPolicyId("policy-correlation"),
                    rule = "allow controlled application",
                    createdAt = Instant.parse("2026-08-29T10:42:00Z")
                )
            )
        ).ownership
        return assertIs<LearningApplicationInstallResult.Installed>(
            f.applications.install(
                LearningApplicationIntent(
                    id = LearningApplicationId("application-correlation"),
                    decision = LearningDecisionReference(decision.decision.id, decision.generation),
                    policy = LearningPolicyReference(policy.policy.id, policy.generation),
                    target = target,
                    createdAt = Instant.parse("2026-08-29T10:43:00Z")
                )
            )
        ).ownership
    }

    private fun grant(f: Fixture, target: LearningApplicationTarget) {
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
                    scope = LearningApplicationAuthorityContract.scopeFor(target)
                )
            )
        )
    }

    private fun prepareMemory(
        f: Fixture,
        application: LearningApplicationOwnership
    ): LearningApplicationMutationOwnership = assertIs<LearningApplicationMutationPrepareResult.Prepared>(
        f.mutations.prepare(
            LearningApplicationMutationPlan(
                id = LearningApplicationMutationId("mutation-correlation-memory"),
                application = LearningApplicationIntentReference(application.intent.id, application.generation),
                principal = principal,
                target = LearningApplicationTarget.MEMORY,
                idempotencyKey = LearningApplicationIdempotencyKey("idem-correlation-memory"),
                payload = LearningApplicationMutationPayload.Memory(
                    MemoryRecord(
                        id = MemoryRecordId("memory-correlation"),
                        provenance = MemoryProvenance(MemorySourceId("learning-application")),
                        content = "sensitive correlation memory",
                        createdAt = Instant.parse("2026-08-29T10:44:00Z")
                    )
                ),
                createdAt = Instant.parse("2026-08-29T10:45:00Z")
            )
        )
    ).ownership

    private fun prepareKnowledge(
        f: Fixture,
        application: LearningApplicationOwnership
    ): LearningApplicationMutationOwnership = assertIs<LearningApplicationMutationPrepareResult.Prepared>(
        f.mutations.prepare(
            LearningApplicationMutationPlan(
                id = LearningApplicationMutationId("mutation-correlation-knowledge"),
                application = LearningApplicationIntentReference(application.intent.id, application.generation),
                principal = principal,
                target = LearningApplicationTarget.KNOWLEDGE,
                idempotencyKey = LearningApplicationIdempotencyKey("idem-correlation-knowledge"),
                payload = LearningApplicationMutationPayload.Knowledge(
                    KnowledgeItem(
                        id = KnowledgeItemId("knowledge-correlation"),
                        origin = KnowledgeOrigin.Declared(KnowledgeSourceId("learning-application")),
                        content = "sensitive correlation knowledge",
                        createdAt = Instant.parse("2026-08-29T10:44:00Z")
                    )
                ),
                createdAt = Instant.parse("2026-08-29T10:45:00Z")
            )
        )
    ).ownership

    private fun reference(ownership: LearningApplicationMutationOwnership) =
        LearningApplicationMutationReference(ownership.plan.id, ownership.generation)

    @Test
    fun memory_apply_preserves_explicit_operation_correlation_lineage() {
        val f = fixture()
        val application = installApplication(f, LearningApplicationTarget.MEMORY)
        grant(f, LearningApplicationTarget.MEMORY)
        val mutation = prepareMemory(f, application)
        f.logs.clear()
        f.diagnostics.clear()

        assertIs<LearningApplicationMutationApplicationResult.Applied>(
            f.applier.apply(reference(mutation))
        )

        assertApplyLineage(f, downstreamCode = "MEMORY_REGISTERED")
    }

    @Test
    fun knowledge_apply_preserves_explicit_operation_correlation_lineage() {
        val f = fixture()
        val application = installApplication(f, LearningApplicationTarget.KNOWLEDGE)
        grant(f, LearningApplicationTarget.KNOWLEDGE)
        val mutation = prepareKnowledge(f, application)
        f.logs.clear()
        f.diagnostics.clear()

        assertIs<LearningApplicationMutationApplicationResult.Applied>(
            f.applier.apply(reference(mutation))
        )

        assertApplyLineage(f, downstreamCode = "KNOWLEDGE_REGISTERED")
    }

    private fun assertApplyLineage(
        f: Fixture,
        downstreamCode: String
    ) {
        val logs = f.logs.snapshot()
        val diagnostics = f.diagnostics.snapshot()

        val started = log(logs, "LEARNING_APPLICATION_MUTATION_APPLY_STARTED")
        val claimed = log(logs, "LEARNING_APPLICATION_MUTATION_CLAIMED")
        val authorized = log(logs, "AUTHORITY_GRANTED")
        val downstream = log(logs, downstreamCode)
        val completed = log(logs, "LEARNING_APPLICATION_MUTATION_COMPLETED")
        val applied = log(logs, "LEARNING_APPLICATION_MUTATION_APPLIED")

        val rootCorrelation = assertNotNull(started.context.correlationId)
        assertNull(started.context.parentCorrelationId)
        assertEquals(rootCorrelation, applied.context.correlationId)
        assertNull(applied.context.parentCorrelationId)

        assertEquals(rootCorrelation, claimed.context.parentCorrelationId)
        assertEquals(rootCorrelation, authorized.context.parentCorrelationId)
        assertEquals(rootCorrelation, downstream.context.parentCorrelationId)
        assertEquals(claimed.context.correlationId, completed.context.parentCorrelationId)

        listOf(
            "LEARNING_APPLICATION_MUTATION_APPLY_STARTED",
            "LEARNING_APPLICATION_MUTATION_CLAIMED",
            "AUTHORITY_GRANTED",
            downstreamCode,
            "LEARNING_APPLICATION_MUTATION_COMPLETED",
            "LEARNING_APPLICATION_MUTATION_APPLIED"
        ).forEach { code ->
            assertEquals(log(logs, code).context, diagnostic(diagnostics, code).context)
        }
    }

    private fun log(events: List<LogEvent>, code: String): LogEvent =
        events.single { it.marker == code }

    private fun diagnostic(events: List<DiagnosticEvent>, code: String): DiagnosticEvent =
        events.single { it.code == code }
}
