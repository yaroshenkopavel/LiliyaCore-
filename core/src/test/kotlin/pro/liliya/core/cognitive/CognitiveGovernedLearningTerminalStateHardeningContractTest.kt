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
import pro.liliya.core.learning.LearningApplicationAuthorizer
import pro.liliya.core.learning.LearningApplicationComposition
import pro.liliya.core.learning.LearningApplicationMutationApplier
import pro.liliya.core.learning.LearningApplicationMutationAuthorizationGate
import pro.liliya.core.learning.LearningApplicationMutationComposition
import pro.liliya.core.learning.LearningApplicationPreflightValidator
import pro.liliya.core.learning.LearningApplicationTarget
import pro.liliya.core.learning.LearningCandidate
import pro.liliya.core.learning.LearningCandidateId
import pro.liliya.core.learning.LearningComposition
import pro.liliya.core.learning.LearningDecisionComposition
import pro.liliya.core.learning.LearningInstallResult
import pro.liliya.core.learning.LearningOrigin
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
import kotlin.test.assertTrue

class CognitiveGovernedLearningTerminalStateHardeningContractTest {
    private val principal = AuthorityPrincipal("cognitive-learning-system")
    private val scope = CognitiveRuntimeScopeId("runtime-scope-terminal-hardening")

    private data class Fixture(
        val learning: LearningComposition,
        val decisions: LearningDecisionComposition,
        val coordinator: CognitiveGovernedLearningCoordinator,
        val governanceCalls: AtomicInteger
    )

    @Test
    fun terminal_evidence_capacity_must_be_positive() {
        assertFailsWith<IllegalArgumentException> {
            CognitiveRuntimeLimits(maxGovernedLearningTerminalEvidenceEntries = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            CognitiveRuntimeLimits(maxGovernedLearningTerminalEvidenceEntries = -1)
        }
        assertTrue(CognitiveRuntimeLimits().maxGovernedLearningTerminalEvidenceEntries > 0)
    }

    @Test
    fun full_capacity_preserves_terminal_one_shot_and_rejects_unseen_before_governance() {
        val f = fixture(capacity = 1) {
            CognitiveLearningGovernanceResult.Rejected("structural reject")
        }
        val first = installCandidate(f.learning, "candidate-capacity-first")
        val second = installCandidate(f.learning, "candidate-capacity-second")

        assertIs<CognitiveGovernedLearningResult.GovernanceRejected>(f.coordinator.process(first.reference))
        assertEquals(1, f.governanceCalls.get())
        assertEquals(1, f.decisions.snapshotEntries().size)

        val full = assertIs<CognitiveGovernedLearningResult.Rejected>(
            f.coordinator.process(second.reference)
        )
        assertEquals(
            CognitiveGovernedLearningFailure.TERMINAL_EVIDENCE_CAPACITY_EXHAUSTED,
            full.reason
        )
        assertEquals(1, f.governanceCalls.get())
        assertEquals(1, f.decisions.snapshotEntries().size)

        val already = assertIs<CognitiveGovernedLearningResult.AlreadyProcessed>(
            f.coordinator.process(first.reference)
        )
        assertEquals(CognitiveGovernedLearningTerminalStatus.GOVERNANCE_REJECTED, already.status)

        val stillFull = assertIs<CognitiveGovernedLearningResult.Rejected>(
            f.coordinator.process(second.reference)
        )
        assertEquals(
            CognitiveGovernedLearningFailure.TERMINAL_EVIDENCE_CAPACITY_EXHAUSTED,
            stillFull.reason
        )
        assertEquals(1, f.governanceCalls.get())
    }

    @Test
    fun capacity_one_reserves_before_governance_and_same_reference_keeps_in_progress_priority() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val f = fixture(capacity = 1) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            CognitiveLearningGovernanceResult.Rejected("barrier reject")
        }
        val first = installCandidate(f.learning, "candidate-reserved-first")
        val second = installCandidate(f.learning, "candidate-reserved-second")
        val executor = Executors.newSingleThreadExecutor()

        try {
            val admitted = executor.submit<CognitiveGovernedLearningResult> {
                f.coordinator.process(first.reference)
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            val duplicate = assertIs<CognitiveGovernedLearningResult.Rejected>(
                f.coordinator.process(first.reference)
            )
            assertEquals(CognitiveGovernedLearningFailure.ATTEMPT_IN_PROGRESS, duplicate.reason)

            val exhausted = assertIs<CognitiveGovernedLearningResult.Rejected>(
                f.coordinator.process(second.reference)
            )
            assertEquals(
                CognitiveGovernedLearningFailure.TERMINAL_EVIDENCE_CAPACITY_EXHAUSTED,
                exhausted.reason
            )
            assertEquals(1, f.governanceCalls.get())

            release.countDown()
            assertIs<CognitiveGovernedLearningResult.GovernanceRejected>(
                admitted.get(10, TimeUnit.SECONDS)
            )

            val secondAfterCompletion = assertIs<CognitiveGovernedLearningResult.Rejected>(
                f.coordinator.process(second.reference)
            )
            assertEquals(
                CognitiveGovernedLearningFailure.TERMINAL_EVIDENCE_CAPACITY_EXHAUSTED,
                secondAfterCompletion.reason
            )
            assertEquals(1, f.governanceCalls.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun same_candidate_id_new_generation_is_distinct_and_requires_another_slot() {
        val f = fixture(capacity = 2) {
            CognitiveLearningGovernanceResult.Rejected("generation reject")
        }
        val first = installCandidate(f.learning, "candidate-generation")
        assertIs<CognitiveGovernedLearningResult.GovernanceRejected>(
            f.coordinator.process(first.reference)
        )
        assertTrue(first.remove())

        val second = installCandidate(f.learning, "candidate-generation")
        assertTrue(second.reference.generation != first.reference.generation)
        assertIs<CognitiveGovernedLearningResult.GovernanceRejected>(
            f.coordinator.process(second.reference)
        )
        assertEquals(2, f.governanceCalls.get())

        val firstAgain = assertIs<CognitiveGovernedLearningResult.AlreadyProcessed>(
            f.coordinator.process(first.reference)
        )
        assertEquals(CognitiveGovernedLearningTerminalStatus.GOVERNANCE_REJECTED, firstAgain.status)

        val secondAgain = assertIs<CognitiveGovernedLearningResult.AlreadyProcessed>(
            f.coordinator.process(second.reference)
        )
        assertEquals(CognitiveGovernedLearningTerminalStatus.GOVERNANCE_REJECTED, secondAgain.status)
        assertEquals(2, f.governanceCalls.get())
    }

    private data class CandidateHandle(
        val reference: CognitiveLearningReference,
        val remove: () -> Boolean
    )

    private fun installCandidate(learning: LearningComposition, id: String): CandidateHandle {
        val ownership = assertIs<LearningInstallResult.Installed>(
            learning.install(
                LearningCandidate(
                    id = LearningCandidateId(id),
                    origin = LearningOrigin.Declared(LearningSourceId("terminal-hardening-test")),
                    proposal = "private candidate proposal",
                    createdAt = Instant.parse("2026-09-02T18:00:00Z")
                )
            )
        ).ownership
        return CandidateHandle(
            reference = CognitiveLearningReference(ownership.candidate.id, ownership.generation),
            remove = { ownership.remove() }
        )
    }

    private fun fixture(
        capacity: Int,
        governance: (CognitiveLearningGovernanceRequest) -> CognitiveLearningGovernanceResult
    ): Fixture {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val correlation = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(diagnostics),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "terminal-hardening-${correlation.incrementAndGet()}" }
        )
        val learning = LearningComposition(foundation)
        val policies = LearningPolicyComposition(foundation)
        val decisions = LearningDecisionComposition(foundation)
        val applications = LearningApplicationComposition(foundation)
        val mutations = LearningApplicationMutationComposition(foundation)
        val authority = CapabilityAuthorityComposition(foundation)
        val memory = MemoryComposition(foundation)
        val knowledge = KnowledgeComposition(foundation)

        val policyOwnership = assertIs<LearningPolicyInstallResult.Installed>(
            policies.install(
                LearningPolicy(
                    id = LearningPolicyId("terminal-hardening-policy"),
                    rule = "private policy rule",
                    createdAt = Instant.parse("2026-09-02T18:01:00Z")
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
        val gate = LearningApplicationMutationAuthorizationGate(mutations, authorizer)
        val applier = LearningApplicationMutationApplier(mutations, gate, memory, knowledge)
        val governanceCalls = AtomicInteger(0)
        val artifactSequence = AtomicInteger(0)

        val coordinator = CognitiveGovernedLearningCoordinator(
            scope = scope,
            learning = learning,
            policies = policies,
            policyReference = LearningPolicyReference(
                policyOwnership.policy.id,
                policyOwnership.generation
            ),
            governance = CognitiveLearningGovernancePort { request ->
                governanceCalls.incrementAndGet()
                governance(request)
            },
            decisions = decisions,
            materialization = CognitiveLearningApplicationMaterializationPort {
                error("materialization must not run in terminal hardening governance-reject fixture")
            },
            applications = applications,
            mutations = mutations,
            mutationApplier = applier,
            principal = principal,
            allowedTargets = listOf(LearningApplicationTarget.MEMORY),
            artifactIds = CognitiveArtifactIdSource { kind ->
                "terminal-hardening-${kind.name.lowercase()}-${artifactSequence.incrementAndGet()}"
            },
            timestamps = CognitiveTimestampSource { Instant.parse("2026-09-02T18:02:00Z") },
            limits = CognitiveRuntimeLimits(
                maxGovernedLearningTerminalEvidenceEntries = capacity
            )
        )

        return Fixture(
            learning = learning,
            decisions = decisions,
            coordinator = coordinator,
            governanceCalls = governanceCalls
        )
    }
}
