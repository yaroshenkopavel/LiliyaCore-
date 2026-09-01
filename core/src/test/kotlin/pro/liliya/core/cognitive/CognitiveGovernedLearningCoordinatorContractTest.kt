package pro.liliya.core.cognitive

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
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.learning.LearningApplicationAuthorityContract
import pro.liliya.core.learning.LearningApplicationComposition
import pro.liliya.core.learning.LearningApplicationDownstreamReference
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
import pro.liliya.core.learning.LearningDecisionDisposition
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
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CognitiveGovernedLearningCoordinatorContractTest {
    private val principal = AuthorityPrincipal("cognitive-learning-system")
    private val scope = CognitiveRuntimeScopeId("runtime-scope-contract")

    private data class Fixture(
        val learning: LearningComposition,
        val policies: LearningPolicyComposition,
        val decisions: LearningDecisionComposition,
        val applications: LearningApplicationComposition,
        val mutations: LearningApplicationMutationComposition,
        val authority: CapabilityAuthorityComposition,
        val memory: MemoryComposition,
        val knowledge: KnowledgeComposition,
        val coordinator: CognitiveGovernedLearningCoordinator,
        val reference: CognitiveLearningReference,
        val logs: InMemoryLogWriter,
        val diagnostics: InMemoryDiagnosticSink,
        val governanceCalls: AtomicInteger,
        val materializerCalls: AtomicInteger
    )

    private fun fixture(
        governance: (CognitiveLearningGovernanceRequest) -> CognitiveLearningGovernanceResult,
        materialization: (CognitiveLearningApplicationMaterializationRequest) -> CognitiveLearningApplicationMaterializationResult = {
            CognitiveLearningApplicationMaterializationResult.Succeeded("learned-content")
        },
        grants: Set<LearningApplicationTarget> = emptySet(),
        allowedTargets: List<LearningApplicationTarget> = listOf(
            LearningApplicationTarget.MEMORY,
            LearningApplicationTarget.KNOWLEDGE
        ),
        idFor: (CognitiveArtifactIdKind) -> String = { kind ->
            when (kind) {
                CognitiveArtifactIdKind.LEARNING_DECISION -> "decision-generated"
                CognitiveArtifactIdKind.LEARNING_APPLICATION -> "application-generated"
                CognitiveArtifactIdKind.LEARNING_MUTATION -> "mutation-generated"
                CognitiveArtifactIdKind.MEMORY_RECORD -> "memory-generated"
                CognitiveArtifactIdKind.KNOWLEDGE_ITEM -> "knowledge-generated"
                else -> "unused-${kind.name.lowercase()}"
            }
        },
        candidateProposal: String = "candidate-private-proposal",
        policyRule: String = "policy-private-rule"
    ): Fixture {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val correlation = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(diagnostics),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "governed-${correlation.incrementAndGet()}" }
        )
        val learning = LearningComposition(foundation)
        val policies = LearningPolicyComposition(foundation)
        val decisions = LearningDecisionComposition(foundation)
        val applications = LearningApplicationComposition(foundation)
        val mutations = LearningApplicationMutationComposition(foundation)
        val authority = CapabilityAuthorityComposition(foundation)
        val memory = MemoryComposition(foundation)
        val knowledge = KnowledgeComposition(foundation)

        val candidate = assertIs<LearningInstallResult.Installed>(
            learning.install(
                LearningCandidate(
                    id = LearningCandidateId("candidate-contract"),
                    origin = LearningOrigin.Declared(LearningSourceId("cognitive-test")),
                    proposal = candidateProposal,
                    createdAt = Instant.parse("2026-09-01T10:00:00Z")
                )
            )
        ).ownership
        val policy = assertIs<LearningPolicyInstallResult.Installed>(
            policies.install(
                LearningPolicy(
                    id = LearningPolicyId("policy-contract"),
                    rule = policyRule,
                    createdAt = Instant.parse("2026-09-01T10:01:00Z")
                )
            )
        ).ownership

        assertIs<CapabilityOwnershipResult.Registered>(
            authority.registerCapability(
                CapabilityDescriptor(
                    id = LearningApplicationAuthorityContract.capability,
                    providerId = CapabilityProviderId("cognitive-learning")
                )
            )
        )
        grants.forEach { target ->
            assertIs<DirectAuthorityGrantOwnershipResult.Registered>(
                authority.registerDirectGrant(
                    DirectAuthorityGrant(
                        principal = principal,
                        capability = LearningApplicationAuthorityContract.capability,
                        scope = LearningApplicationAuthorityContract.scopeFor(target)
                    )
                )
            )
        }

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
        val materializerCalls = AtomicInteger(0)
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
            materialization = CognitiveLearningApplicationMaterializationPort { request ->
                materializerCalls.incrementAndGet()
                materialization(request)
            },
            applications = applications,
            mutations = mutations,
            mutationApplier = applier,
            principal = principal,
            allowedTargets = allowedTargets,
            artifactIds = CognitiveArtifactIdSource(idFor),
            timestamps = CognitiveTimestampSource { Instant.parse("2026-09-01T10:02:00Z") },
            limits = CognitiveRuntimeLimits()
        )

        return Fixture(
            learning = learning,
            policies = policies,
            decisions = decisions,
            applications = applications,
            mutations = mutations,
            authority = authority,
            memory = memory,
            knowledge = knowledge,
            coordinator = coordinator,
            reference = CognitiveLearningReference(candidate.candidate.id, candidate.generation),
            logs = logs,
            diagnostics = diagnostics,
            governanceCalls = governanceCalls,
            materializerCalls = materializerCalls
        )
    }

    @Test
    fun governance_reject_persists_exact_reject_decision_and_is_one_shot() {
        val rationale = "PRIVATE-GOVERNANCE-REJECT-RATIONALE"
        val f = fixture(
            governance = { CognitiveLearningGovernanceResult.Rejected(rationale) },
            materialization = { error("materializer must not run after governance reject") }
        )

        val rejected = assertIs<CognitiveGovernedLearningResult.GovernanceRejected>(
            f.coordinator.process(f.reference)
        )
        val decision = assertNotNull(f.decisions.inspect(rejected.decision.decisionId))

        assertEquals(rejected.decision.generation, decision.generation)
        assertEquals(LearningDecisionDisposition.REJECT, decision.decision.disposition)
        assertEquals(f.reference.id, decision.decision.candidate.candidateId)
        assertEquals(f.reference.generation, decision.decision.candidate.generation)
        assertEquals(1, f.governanceCalls.get())
        assertEquals(0, f.materializerCalls.get())
        assertTrue(f.applications.snapshot().isEmpty())
        assertTrue(f.mutations.snapshot().isEmpty())
        assertTrue(f.memory.snapshotEntries().isEmpty())
        assertTrue(f.knowledge.snapshotEntries().isEmpty())

        val second = assertIs<CognitiveGovernedLearningResult.AlreadyProcessed>(
            f.coordinator.process(f.reference)
        )
        assertEquals(CognitiveGovernedLearningTerminalStatus.GOVERNANCE_REJECTED, second.status)
        assertEquals(1, f.governanceCalls.get())
        assertEquals(0, f.materializerCalls.get())
    }

    @Test
    fun authority_denial_causes_zero_downstream_write_and_compensates_attempt_records() {
        val f = fixture(
            governance = {
                CognitiveLearningGovernanceResult.Approved(
                    LearningApplicationTarget.MEMORY,
                    "approve structurally"
                )
            },
            materialization = {
                CognitiveLearningApplicationMaterializationResult.Succeeded("private-memory-content")
            },
            grants = emptySet()
        )

        val rejected = assertIs<CognitiveGovernedLearningResult.Rejected>(
            f.coordinator.process(f.reference)
        )

        assertEquals(CognitiveGovernedLearningFailure.MUTATION_APPLY_REJECTED, rejected.reason)
        assertTrue(f.memory.snapshotEntries().isEmpty())
        assertTrue(f.knowledge.snapshotEntries().isEmpty())
        assertTrue(f.decisions.snapshotEntries().isEmpty())
        assertTrue(f.applications.snapshotEntries().isEmpty())
        assertTrue(f.mutations.snapshotEntries().isEmpty())

        val second = assertIs<CognitiveGovernedLearningResult.AlreadyProcessed>(
            f.coordinator.process(f.reference)
        )
        assertEquals(CognitiveGovernedLearningTerminalStatus.REJECTED, second.status)
        assertEquals(1, f.governanceCalls.get())
        assertEquals(1, f.materializerCalls.get())
    }

    @Test
    fun authorized_memory_application_uses_existing_applier_and_is_one_shot() {
        val secretProposal = "PRIVATE-CANDIDATE-PROPOSAL-DO-NOT-LOG"
        val secretPolicy = "PRIVATE-POLICY-RULE-DO-NOT-LOG"
        val secretRationale = "PRIVATE-APPROVAL-RATIONALE-DO-NOT-LOG"
        val secretContent = "PRIVATE-LEARNED-MEMORY-DO-NOT-LOG"
        val f = fixture(
            governance = {
                CognitiveLearningGovernanceResult.Approved(
                    LearningApplicationTarget.MEMORY,
                    secretRationale
                )
            },
            materialization = {
                CognitiveLearningApplicationMaterializationResult.Succeeded(secretContent)
            },
            grants = setOf(LearningApplicationTarget.MEMORY),
            candidateProposal = secretProposal,
            policyRule = secretPolicy
        )

        val applied = assertIs<CognitiveGovernedLearningResult.Applied>(
            f.coordinator.process(f.reference)
        )
        val downstream = assertIs<LearningApplicationDownstreamReference.Memory>(applied.receipt.downstream)
        val memory = assertNotNull(f.memory.inspect(downstream.recordId))

        assertEquals(MemoryRecordId("memory-generated"), downstream.recordId)
        assertEquals(secretContent, memory.record.content)
        assertNotNull(f.decisions.inspect(applied.decision.decisionId))
        assertNotNull(f.applications.inspect(applied.application.applicationId))
        assertNull(f.mutations.inspect(applied.mutation.mutationId))
        assertEquals(1, f.governanceCalls.get())
        assertEquals(1, f.materializerCalls.get())

        val rendering = f.logs.snapshot().joinToString("\n") + "\n" +
            f.diagnostics.snapshot().joinToString("\n") + "\n" + applied.toString()
        assertFalse(rendering.contains(secretProposal))
        assertFalse(rendering.contains(secretPolicy))
        assertFalse(rendering.contains(secretRationale))
        assertFalse(rendering.contains(secretContent))

        val second = assertIs<CognitiveGovernedLearningResult.AlreadyProcessed>(
            f.coordinator.process(f.reference)
        )
        assertEquals(CognitiveGovernedLearningTerminalStatus.APPLIED, second.status)
        assertEquals(1, f.governanceCalls.get())
        assertEquals(1, f.materializerCalls.get())
    }

    @Test
    fun authorized_knowledge_application_writes_authoritative_knowledge_only() {
        val f = fixture(
            governance = {
                CognitiveLearningGovernanceResult.Approved(
                    LearningApplicationTarget.KNOWLEDGE,
                    "approve knowledge"
                )
            },
            materialization = {
                CognitiveLearningApplicationMaterializationResult.Succeeded("learned-knowledge")
            },
            grants = setOf(LearningApplicationTarget.KNOWLEDGE)
        )

        val applied = assertIs<CognitiveGovernedLearningResult.Applied>(
            f.coordinator.process(f.reference)
        )
        val downstream = assertIs<LearningApplicationDownstreamReference.Knowledge>(applied.receipt.downstream)
        val knowledge = assertNotNull(f.knowledge.inspect(downstream.itemId))

        assertEquals(KnowledgeItemId("knowledge-generated"), downstream.itemId)
        assertEquals("learned-knowledge", knowledge.item.content)
        assertTrue(f.memory.snapshotEntries().isEmpty())
        assertEquals(1, f.knowledge.snapshotEntries().size)
    }

    @Test
    fun concurrent_same_candidate_has_one_governance_attempt() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val f = fixture(
            governance = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                CognitiveLearningGovernanceResult.Approved(
                    LearningApplicationTarget.MEMORY,
                    "approve after barrier"
                )
            },
            grants = setOf(LearningApplicationTarget.MEMORY)
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<CognitiveGovernedLearningResult> {
                f.coordinator.process(f.reference)
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            val concurrent = assertIs<CognitiveGovernedLearningResult.Rejected>(
                f.coordinator.process(f.reference)
            )
            assertEquals(CognitiveGovernedLearningFailure.ATTEMPT_IN_PROGRESS, concurrent.reason)

            release.countDown()
            assertIs<CognitiveGovernedLearningResult.Applied>(first.get(10, TimeUnit.SECONDS))
            assertEquals(1, f.governanceCalls.get())
            assertEquals(1, f.materializerCalls.get())
            assertEquals(1, f.memory.snapshotEntries().size)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun cross_kind_generated_id_collision_is_rejected_before_application_install() {
        val f = fixture(
            governance = {
                CognitiveLearningGovernanceResult.Approved(
                    LearningApplicationTarget.MEMORY,
                    "approve collision test"
                )
            },
            grants = setOf(LearningApplicationTarget.MEMORY),
            idFor = { kind ->
                when (kind) {
                    CognitiveArtifactIdKind.LEARNING_DECISION,
                    CognitiveArtifactIdKind.LEARNING_APPLICATION -> "same-generated-id"
                    CognitiveArtifactIdKind.LEARNING_MUTATION -> "mutation-other"
                    CognitiveArtifactIdKind.MEMORY_RECORD -> "memory-other"
                    else -> "unused-${kind.name.lowercase()}"
                }
            }
        )

        val rejected = assertIs<CognitiveGovernedLearningResult.Rejected>(
            f.coordinator.process(f.reference)
        )

        assertEquals(CognitiveGovernedLearningFailure.ARTIFACT_ID_OR_TIME_FAILED, rejected.reason)
        assertTrue(f.decisions.snapshotEntries().isEmpty())
        assertTrue(f.applications.snapshotEntries().isEmpty())
        assertTrue(f.mutations.snapshotEntries().isEmpty())
        assertTrue(f.memory.snapshotEntries().isEmpty())
    }
}
