package pro.liliya.core.learning

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
import kotlin.test.assertTrue

class LearningApplicationMutationApplierReadinessContractTest {
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
            correlationIds = CorrelationIdGenerator { "apply-readiness-${sequence.incrementAndGet()}" }
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

    private fun installMemoryApplication(f: Fixture): LearningApplicationOwnership {
        val candidate = assertIs<LearningInstallResult.Installed>(
            f.candidates.install(
                LearningCandidate(
                    id = LearningCandidateId("candidate-readiness"),
                    origin = LearningOrigin.Declared(LearningSourceId("readiness-test")),
                    proposal = "readiness proposal",
                    createdAt = Instant.parse("2026-08-29T10:30:00Z")
                )
            )
        ).ownership
        val decision = assertIs<LearningDecisionInstallResult.Installed>(
            f.decisions.install(
                LearningDecision(
                    id = LearningDecisionId("decision-readiness"),
                    candidate = LearningCandidateReference(candidate.candidate.id, candidate.generation),
                    disposition = LearningDecisionDisposition.APPROVE,
                    rationale = "approved for readiness",
                    createdAt = Instant.parse("2026-08-29T10:31:00Z")
                )
            )
        ).ownership
        val policy = assertIs<LearningPolicyInstallResult.Installed>(
            f.policies.install(
                LearningPolicy(
                    id = LearningPolicyId("policy-readiness"),
                    rule = "allow controlled memory application",
                    createdAt = Instant.parse("2026-08-29T10:32:00Z")
                )
            )
        ).ownership
        return assertIs<LearningApplicationInstallResult.Installed>(
            f.applications.install(
                LearningApplicationIntent(
                    id = LearningApplicationId("application-readiness"),
                    decision = LearningDecisionReference(decision.decision.id, decision.generation),
                    policy = LearningPolicyReference(policy.policy.id, policy.generation),
                    target = LearningApplicationTarget.MEMORY,
                    createdAt = Instant.parse("2026-08-29T10:33:00Z")
                )
            )
        ).ownership
    }

    private fun grantMemoryApply(f: Fixture) {
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

    private fun memoryPlan(
        application: LearningApplicationOwnership,
        mutationId: String,
        idempotencyKey: String,
        recordId: String,
        content: String = "readiness learned memory"
    ): LearningApplicationMutationPlan = LearningApplicationMutationPlan(
        id = LearningApplicationMutationId(mutationId),
        application = LearningApplicationIntentReference(application.intent.id, application.generation),
        principal = principal,
        target = LearningApplicationTarget.MEMORY,
        idempotencyKey = LearningApplicationIdempotencyKey(idempotencyKey),
        payload = LearningApplicationMutationPayload.Memory(
            MemoryRecord(
                id = MemoryRecordId(recordId),
                provenance = MemoryProvenance(MemorySourceId("learning-application")),
                content = content,
                createdAt = Instant.parse("2026-08-29T10:34:00Z")
            )
        ),
        createdAt = Instant.parse("2026-08-29T10:35:00Z")
    )

    private fun prepare(
        f: Fixture,
        plan: LearningApplicationMutationPlan
    ): LearningApplicationMutationOwnership = assertIs<LearningApplicationMutationPrepareResult.Prepared>(
        f.mutations.prepare(plan)
    ).ownership

    private fun reference(ownership: LearningApplicationMutationOwnership) =
        LearningApplicationMutationReference(ownership.plan.id, ownership.generation)

    private fun runConcurrently(
        first: () -> LearningApplicationMutationApplicationResult,
        second: () -> LearningApplicationMutationApplicationResult
    ): List<LearningApplicationMutationApplicationResult> {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        return try {
            val futures = listOf(first, second).map { action ->
                executor.submit<LearningApplicationMutationApplicationResult> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    action()
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrent_apply_of_same_exact_mutation_has_one_downstream_winner() {
        val f = fixture()
        val application = installMemoryApplication(f)
        grantMemoryApply(f)
        val mutation = prepare(
            f,
            memoryPlan(application, "mutation-same", "idem-same", "memory-same")
        )
        val ref = reference(mutation)

        val results = runConcurrently(
            first = { f.applier.apply(ref) },
            second = { f.applier.apply(ref) }
        )

        assertEquals(1, results.count { it is LearningApplicationMutationApplicationResult.Applied })
        assertEquals(1, results.count { it is LearningApplicationMutationApplicationResult.ClaimRejected })
        val rejected = results.filterIsInstance<LearningApplicationMutationApplicationResult.ClaimRejected>().single()
        assertTrue(
            rejected.reason == LearningApplicationMutationClaimRejection.ALREADY_CLAIMED ||
                rejected.reason == LearningApplicationMutationClaimRejection.MUTATION_MISSING
        )
        assertEquals(1, f.memory.snapshotEntries().size)
        assertTrue(f.memory.contains(MemoryRecordId("memory-same")))
        assertTrue(f.mutations.isCompletedIdempotencyKey(mutation.plan.idempotencyKey))
    }

    @Test
    fun concurrent_distinct_mutations_for_same_memory_id_do_not_overwrite() {
        val f = fixture()
        val application = installMemoryApplication(f)
        grantMemoryApply(f)
        val firstMutation = prepare(
            f,
            memoryPlan(application, "mutation-a", "idem-a", "memory-conflict", "payload-a")
        )
        val secondMutation = prepare(
            f,
            memoryPlan(application, "mutation-b", "idem-b", "memory-conflict", "payload-b")
        )

        val results = runConcurrently(
            first = { f.applier.apply(reference(firstMutation)) },
            second = { f.applier.apply(reference(secondMutation)) }
        )

        assertEquals(1, results.count { it is LearningApplicationMutationApplicationResult.Applied })
        assertEquals(1, results.count { it is LearningApplicationMutationApplicationResult.DownstreamRejected })
        assertEquals(1, f.memory.snapshotEntries().size)
        assertTrue(f.memory.contains(MemoryRecordId("memory-conflict")))

        val completionStates = listOf(firstMutation, secondMutation).map {
            f.mutations.isCompletedIdempotencyKey(it.plan.idempotencyKey)
        }
        assertEquals(1, completionStates.count { it })
        assertEquals(1, listOf(firstMutation, secondMutation).count { f.mutations.contains(it.plan.id) })
    }

    @Test
    fun apply_path_does_not_render_sensitive_payload_in_observability_or_result() {
        val f = fixture()
        val application = installMemoryApplication(f)
        grantMemoryApply(f)
        val secret = "TOP-SECRET-LEARNED-PAYLOAD-DO-NOT-LOG"
        val mutation = prepare(
            f,
            memoryPlan(application, "mutation-private", "idem-private", "memory-private", secret)
        )

        val result = assertIs<LearningApplicationMutationApplicationResult.Applied>(
            f.applier.apply(reference(mutation))
        )

        val logRendering = f.logs.snapshot().joinToString("\n")
        val diagnosticRendering = f.diagnostics.snapshot().joinToString("\n")
        assertFalse(logRendering.contains(secret))
        assertFalse(diagnosticRendering.contains(secret))
        assertFalse(result.toString().contains(secret))
    }
}
