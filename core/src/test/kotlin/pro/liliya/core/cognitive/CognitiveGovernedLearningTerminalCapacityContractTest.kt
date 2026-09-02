package pro.liliya.core.cognitive

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.knowledge.KnowledgeComposition
import pro.liliya.core.learning.LearningApplicationComposition
import pro.liliya.core.learning.LearningApplicationMutationApplier
import pro.liliya.core.learning.LearningApplicationMutationAuthorizationGate
import pro.liliya.core.learning.LearningApplicationMutationComposition
import pro.liliya.core.learning.LearningApplicationAuthorizer
import pro.liliya.core.learning.LearningApplicationPreflightValidator
import pro.liliya.core.learning.LearningApplicationTarget
import pro.liliya.core.learning.LearningCandidate
import pro.liliya.core.learning.LearningCandidateId
import pro.liliya.core.learning.LearningComposition
import pro.liliya.core.learning.LearningDecisionComposition
import pro.liliya.core.learning.LearningInstallResult
import pro.liliya.core.learning.LearningOrigin
import pro.liliya.core.learning.LearningOwnership
import pro.liliya.core.learning.LearningPolicy
import pro.liliya.core.learning.LearningPolicyComposition
import pro.liliya.core.learning.LearningPolicyId
import pro.liliya.core.learning.LearningPolicyInstallResult
import pro.liliya.core.learning.LearningPolicyReference
import pro.liliya.core.learning.LearningSourceId
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryComposition
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CognitiveGovernedLearningTerminalCapacityContractTest {
    private val principal = AuthorityPrincipal("cognitive-learning-capacity")
    private val scope = CognitiveRuntimeScopeId("runtime-scope-capacity")

    private data class CandidateHandle(
        val ownership: LearningOwnership,
        val reference: CognitiveLearningReference
    )

    private data class Fixture(
        val learning: LearningComposition,
        val decisions: LearningDecisionComposition,
        val applications: LearningApplicationComposition,
        val mutations: LearningApplicationMutationComposition,
        val memory: MemoryComposition,
        val knowledge: KnowledgeComposition,
        val coordinator: CognitiveGovernedLearningCoordinator,
        val governanceCalls: AtomicInteger,
        val materializerCalls: AtomicInteger
    ) {
        fun installCandidate(id: String): CandidateHandle {
            val installed = assertIs<LearningInstallResult.Installed>(
                learning.install(
                    LearningCandidate(
                        id = LearningCandidateId(id),
                        origin = LearningOrigin.Declared(LearningSourceId("capacity-test")),
                        proposal = "private-proposal-$id",
                        createdAt = Instant.parse("2026-09-02T12:00:00Z")
                    )
                )
            ).ownership
            return CandidateHandle(
                ownership = installed,
                reference = CognitiveLearningReference(installed.candidate.id, installed.generation)
            )
        }
    }

    private fun fixture(
        capacity: Int,
        governance: (CognitiveLearningGovernanceRequest) -> CognitiveLearningGovernanceResult = {
            CognitiveLearningGovernanceResult.Rejected("private-capacity-rationale")
        }
    ): Fixture {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val correlations = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(diagnostics),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "capacity-${correlations.incrementAndGet()}" }
        )
        val learning = LearningComposition(foundation)
        val policies = LearningPolicyComposition(foundation)
        val decisions = LearningDecisionComposition(foundation)
        val applications = LearningApplicationComposition(foundation)
        val mutations = LearningApplicationMutationComposition(foundation)
        val authority = CapabilityAuthorityComposition(foundation)
        val memory = MemoryComposition(foundation)
        val knowledge = KnowledgeComposition(foundation)

        val policy = assertIs<LearningPolicyInstallResult.Installed>(
            policies.install(
                LearningPolicy(
                    id = LearningPolicyId("capacity-policy"),
                    rule = "private-capacity-policy",
                    createdAt = Instant.parse("2026-09-02T12:01:00Z")
                )
            )
        ).ownership

        val preflight = LearningApplicationPreflightValidator(
            applications,
            decisions,
            learning,
            policies
        )
        val authorizer = LearningApplicationAuthorizer(preflight, authority)
        val authorizationGate = LearningApplicationMutationAuthorizationGate(mutations, authorizer)
        val mutationApplier = LearningApplicationMutationApplier(
            mutations,
            authorizationGate,
            memory,
            knowledge
        )
        val governanceCalls = AtomicInteger(0)
        val materializerCalls = AtomicInteger(0)
        val generatedIds = AtomicInteger(0)
        val coordinator = CognitiveGovernedLearningCoordinator(
            scope = scope,
            learning = learning,
            policies = policies,
            policyReference = LearningPolicyReference(policy.policy.id, policy.generation),
            governance = CognitiveLearningGovernancePort { request ->
                governanceCalls.incrementAndGet()
                governance(request)
            },
            decisions = decisions,
            materialization = CognitiveLearningApplicationMaterializationPort {
                materializerCalls.incrementAndGet()
                error("capacity contracts must not reach materialization")
            },
            applications = applications,
            mutations = mutations,
            mutationApplier = mutationApplier,
            principal = principal,
            allowedTargets = listOf(LearningApplicationTarget.MEMORY),
            artifactIds = CognitiveArtifactIdSource { kind ->
                "capacity-${kind.name.lowercase()}-${generatedIds.incrementAndGet()}"
            },
            timestamps = CognitiveTimestampSource { Instant.parse("2026-09-02T12:02:00Z") },
            limits = CognitiveRuntimeLimits(
                maxGovernedLearningTerminalEvidenceEntries = capacity
            )
        )

        return Fixture(
            learning = learning,
            decisions = decisions,
            applications = applications,
            mutations = mutations,
            memory = memory,
            knowledge = knowledge,
            coordinator = coordinator,
            governanceCalls = governanceCalls,
            materializerCalls = materializerCalls
        )
    }

    @Test
    fun terminal_capacity_must_be_positive() {
        assertFailsWith<IllegalArgumentException> {
            CognitiveRuntimeLimits(maxGovernedLearningTerminalEvidenceEntries = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            CognitiveRuntimeLimits(maxGovernedLearningTerminalEvidenceEntries = -1)
        }
    }

    @Test
    fun retained_terminal_entry_consumes_capacity_without_forgetting_or_provider_work() {
        val f = fixture(capacity = 1)
        val first = f.installCandidate("capacity-first")
        val second = f.installCandidate("capacity-second")

        assertIs<CognitiveGovernedLearningResult.GovernanceRejected>(
            f.coordinator.process(first.reference)
        )
        assertEquals(1, f.governanceCalls.get())

        val exhausted = assertIs<CognitiveGovernedLearningResult.Rejected>(
            f.coordinator.process(second.reference)
        )
        assertEquals(
            CognitiveGovernedLearningFailure.TERMINAL_EVIDENCE_CAPACITY_EXHAUSTED,
            exhausted.reason
        )
        assertEquals(1, f.governanceCalls.get())
        assertEquals(0, f.materializerCalls.get())
        assertEquals(1, f.decisions.snapshotEntries().size)
        assertTrue(f.applications.snapshotEntries().isEmpty())
        assertTrue(f.mutations.snapshotEntries().isEmpty())
        assertTrue(f.memory.snapshotEntries().isEmpty())
        assertTrue(f.knowledge.snapshotEntries().isEmpty())

        val already = assertIs<CognitiveGovernedLearningResult.AlreadyProcessed>(
            f.coordinator.process(first.reference)
        )
        assertEquals(CognitiveGovernedLearningTerminalStatus.GOVERNANCE_REJECTED, already.status)

        val exhaustedAgain = assertIs<CognitiveGovernedLearningResult.Rejected>(
            f.coordinator.process(second.reference)
        )
        assertEquals(
            CognitiveGovernedLearningFailure.TERMINAL_EVIDENCE_CAPACITY_EXHAUSTED,
            exhaustedAgain.reason
        )
        assertEquals(1, f.governanceCalls.get())
        assertTrue(!exhaustedAgain.toString().contains("capacity-second"))
    }

    @Test
    fun distinct_concurrent_candidate_cannot_overbook_reserved_terminal_capacity() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val f = fixture(capacity = 1) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            CognitiveLearningGovernanceResult.Rejected("private-concurrent-rationale")
        }
        val first = f.installCandidate("concurrent-first")
        val second = f.installCandidate("concurrent-second")
        val executor = Executors.newSingleThreadExecutor()

        try {
            val firstResult = executor.submit<CognitiveGovernedLearningResult> {
                f.coordinator.process(first.reference)
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            val rejected = assertIs<CognitiveGovernedLearningResult.Rejected>(
                f.coordinator.process(second.reference)
            )
            assertEquals(
                CognitiveGovernedLearningFailure.TERMINAL_EVIDENCE_CAPACITY_EXHAUSTED,
                rejected.reason
            )
            assertEquals(1, f.governanceCalls.get())

            release.countDown()
            assertIs<CognitiveGovernedLearningResult.GovernanceRejected>(
                firstResult.get(10, TimeUnit.SECONDS)
            )

            val stillRejected = assertIs<CognitiveGovernedLearningResult.Rejected>(
                f.coordinator.process(second.reference)
            )
            assertEquals(
                CognitiveGovernedLearningFailure.TERMINAL_EVIDENCE_CAPACITY_EXHAUSTED,
                stillRejected.reason
            )
            assertEquals(1, f.governanceCalls.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun same_reference_concurrent_attempt_keeps_attempt_in_progress_semantics_at_capacity() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val f = fixture(capacity = 1) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            CognitiveLearningGovernanceResult.Rejected("private-same-reference-rationale")
        }
        val candidate = f.installCandidate("same-reference")
        val executor = Executors.newSingleThreadExecutor()

        try {
            val firstResult = executor.submit<CognitiveGovernedLearningResult> {
                f.coordinator.process(candidate.reference)
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            val concurrent = assertIs<CognitiveGovernedLearningResult.Rejected>(
                f.coordinator.process(candidate.reference)
            )
            assertEquals(CognitiveGovernedLearningFailure.ATTEMPT_IN_PROGRESS, concurrent.reason)
            assertEquals(1, f.governanceCalls.get())

            release.countDown()
            assertIs<CognitiveGovernedLearningResult.GovernanceRejected>(
                firstResult.get(10, TimeUnit.SECONDS)
            )
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun same_candidate_id_new_generation_is_distinct_and_requires_another_slot() {
        val f = fixture(capacity = 1)
        val first = f.installCandidate("generation-reuse")

        assertIs<CognitiveGovernedLearningResult.GovernanceRejected>(
            f.coordinator.process(first.reference)
        )
        assertTrue(first.ownership.remove())

        val next = f.installCandidate("generation-reuse")
        assertNotEquals(first.reference.generation, next.reference.generation)

        val rejected = assertIs<CognitiveGovernedLearningResult.Rejected>(
            f.coordinator.process(next.reference)
        )
        assertEquals(
            CognitiveGovernedLearningFailure.TERMINAL_EVIDENCE_CAPACITY_EXHAUSTED,
            rejected.reason
        )
        assertEquals(1, f.governanceCalls.get())
    }
}
