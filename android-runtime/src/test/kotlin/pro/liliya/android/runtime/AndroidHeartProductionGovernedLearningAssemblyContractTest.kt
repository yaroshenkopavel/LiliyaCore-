package pro.liliya.android.runtime

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.cognitive.CognitiveArtifactIdKind
import pro.liliya.core.cognitive.CognitiveArtifactIdSource
import pro.liliya.core.cognitive.CognitiveGovernedLearningFailure
import pro.liliya.core.cognitive.CognitiveGovernedLearningResult
import pro.liliya.core.cognitive.CognitiveLearningApplicationMaterializationPort
import pro.liliya.core.cognitive.CognitiveLearningApplicationMaterializationResult
import pro.liliya.core.cognitive.CognitiveLearningGovernancePort
import pro.liliya.core.cognitive.CognitiveLearningGovernanceResult
import pro.liliya.core.cognitive.CognitiveLearningReference
import pro.liliya.core.cognitive.CognitiveRuntimeLimits
import pro.liliya.core.cognitive.CognitiveRuntimeScopeId
import pro.liliya.core.cognitive.CognitiveTimestampSource
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.learning.LearningApplicationMutationApplicationPort
import pro.liliya.core.learning.LearningApplicationMutationApplicationResult
import pro.liliya.core.learning.LearningApplicationMutationAuthorizationGate
import pro.liliya.core.learning.LearningApplicationMutationGeneration
import pro.liliya.core.learning.LearningApplicationMutationInspectionPort
import pro.liliya.core.learning.LearningApplicationMutationPreparationPort
import pro.liliya.core.learning.LearningApplicationMutationPreparationResult
import pro.liliya.core.learning.LearningApplicationMutationPreparedOwnership
import pro.liliya.core.learning.LearningApplicationMutationSnapshot
import pro.liliya.core.learning.LearningApplicationTarget
import pro.liliya.core.learning.LearningCandidate
import pro.liliya.core.learning.LearningCandidateId
import pro.liliya.core.learning.LearningComposition
import pro.liliya.core.learning.LearningGeneration
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
import pro.liliya.core.observability.LoggerProvider

class AndroidHeartProductionGovernedLearningAssemblyContractTest {

    @Test
    fun creation_rejects_before_ready_without_touching_downstream_ports() {
        var mutationPortCalls = 0
        var governedCalls = 0
        val fixture = fixture()
        val bridge = object : AndroidHeartProductionGovernedLearningBridge {
            override fun state(): HeartRuntimeState = HeartRuntimeState.FAILED

            override fun mutationApplicationPort(
                authorizationGate: LearningApplicationMutationAuthorizationGate
            ): LearningApplicationMutationApplicationPort? {
                mutationPortCalls += 1
                error("must not be called")
            }

            override fun governedLearning(
                composition: pro.liliya.core.cognitive.CognitiveGovernedLearningComposition
            ): AndroidHeartGovernedLearningComposition? {
                governedCalls += 1
                error("must not be called")
            }
        }

        val result = assertIs<AndroidHeartProductionGovernedLearningCreateResult.Rejected>(
            createInternal(fixture, bridge)
        )

        assertEquals(
            AndroidHeartProductionGovernedLearningCreateFailure.HEART_NOT_READY,
            result.reason
        )
        assertEquals(0, mutationPortCalls)
        assertEquals(0, governedCalls)
    }

    @Test
    fun obtained_composition_rechecks_live_heart_state_before_every_process() {
        var state = HeartRuntimeState.READY
        var governedCalls = 0
        val bridge = object : AndroidHeartProductionGovernedLearningBridge {
            override fun state(): HeartRuntimeState = state
            override fun mutationApplicationPort(
                authorizationGate: LearningApplicationMutationAuthorizationGate
            ): LearningApplicationMutationApplicationPort? = null
            override fun governedLearning(
                composition: pro.liliya.core.cognitive.CognitiveGovernedLearningComposition
            ): AndroidHeartGovernedLearningComposition? = null
        }
        val governed = AndroidHeartGovernedLearningComposition(
            governed = AndroidHeartGovernedLearningPort {
                governedCalls += 1
                CognitiveGovernedLearningResult.Rejected(
                    CognitiveGovernedLearningFailure.CANDIDATE_MISSING_OR_MISMATCH
                )
            },
            semantic = AndroidHeartAppliedSemanticSyncPort {
                error("semantic must not run")
            },
            onSemanticUnavailable = {}
        )
        val composition = AndroidHeartProductionGovernedLearningComposition(
            bridge = bridge,
            governed = governed
        )
        val reference = CognitiveLearningReference(
            LearningCandidateId("candidate-live-state"),
            LearningGeneration(1)
        )

        assertIs<AndroidHeartProductionGovernedLearningProcessResult.Processed>(
            composition.process(reference)
        )
        assertEquals(1, governedCalls)

        state = HeartRuntimeState.FAILED
        assertEquals(
            AndroidHeartProductionGovernedLearningProcessResult.NotReady,
            composition.process(reference)
        )
        assertEquals(1, governedCalls)
    }

    @Test
    fun exact_shared_candidate_reaches_memory_only_governance() {
        val fixture = fixture()
        val installed = installCandidate(fixture.learning, "candidate-exact")
        var allowedTargets: List<LearningApplicationTarget>? = null
        var governanceCalls = 0
        val bridge = bridge(
            mutationApplication = { error("mutation must not run after governance rejection") }
        )

        val result = assertIs<AndroidHeartProductionGovernedLearningCreateResult.Ready>(
            createInternal(
                fixture = fixture,
                bridge = bridge,
                governance = CognitiveLearningGovernancePort { request ->
                    governanceCalls += 1
                    allowedTargets = request.allowedTargets
                    CognitiveLearningGovernanceResult.Rejected("explicit trusted rejection")
                }
            )
        ).composition.process(
            CognitiveLearningReference(
                installed.candidate.id,
                installed.generation
            )
        )

        val processed = assertIs<AndroidHeartProductionGovernedLearningProcessResult.Processed>(
            result
        )
        assertIs<CognitiveGovernedLearningResult.GovernanceRejected>(
            processed.result.governed
        )
        assertEquals(1, governanceCalls)
        assertEquals(listOf(LearningApplicationTarget.MEMORY), allowedTargets)
        assertEquals(AndroidHeartSemanticLearningSyncStatus.NOT_APPLICABLE, processed.result.semanticSync)
    }

    @Test
    fun replacement_learning_store_cannot_resolve_finalized_candidate_reference() {
        val fixture = fixture()
        val sourceLearning = LearningComposition(fixture.foundation)
        val installed = installCandidate(sourceLearning, "candidate-wrong-store")
        var governanceCalls = 0
        var mutationCalls = 0
        val bridge = bridge {
            LearningApplicationMutationApplicationPort {
                mutationCalls += 1
                error("mutation must not run for missing candidate")
            }
        }

        val composition = assertIs<AndroidHeartProductionGovernedLearningCreateResult.Ready>(
            createInternal(
                fixture = fixture,
                bridge = bridge,
                governance = CognitiveLearningGovernancePort {
                    governanceCalls += 1
                    CognitiveLearningGovernanceResult.Approved(
                        LearningApplicationTarget.MEMORY,
                        "should never be consulted"
                    )
                }
            )
        ).composition

        val processed = assertIs<AndroidHeartProductionGovernedLearningProcessResult.Processed>(
            composition.process(
                CognitiveLearningReference(
                    installed.candidate.id,
                    installed.generation
                )
            )
        )
        val rejected = assertIs<CognitiveGovernedLearningResult.Rejected>(
            processed.result.governed
        )

        assertEquals(
            CognitiveGovernedLearningFailure.CANDIDATE_MISSING_OR_MISMATCH,
            rejected.reason
        )
        assertEquals(0, governanceCalls)
        assertEquals(0, mutationCalls)
        assertEquals(AndroidHeartSemanticLearningSyncStatus.NOT_APPLICABLE, processed.result.semanticSync)
    }

    @Test
    fun governance_cannot_select_knowledge_in_memory_only_v0_1() {
        val fixture = fixture()
        val installed = installCandidate(fixture.learning, "candidate-knowledge-rejected")
        var mutationCalls = 0
        val bridge = bridge {
            LearningApplicationMutationApplicationPort {
                mutationCalls += 1
                error("mutation must not run for disallowed target")
            }
        }

        val composition = assertIs<AndroidHeartProductionGovernedLearningCreateResult.Ready>(
            createInternal(
                fixture = fixture,
                bridge = bridge,
                governance = CognitiveLearningGovernancePort {
                    assertEquals(listOf(LearningApplicationTarget.MEMORY), it.allowedTargets)
                    CognitiveLearningGovernanceResult.Approved(
                        LearningApplicationTarget.KNOWLEDGE,
                        "attempt disallowed target"
                    )
                }
            )
        ).composition

        val processed = assertIs<AndroidHeartProductionGovernedLearningProcessResult.Processed>(
            composition.process(
                CognitiveLearningReference(
                    installed.candidate.id,
                    installed.generation
                )
            )
        )
        val rejected = assertIs<CognitiveGovernedLearningResult.Rejected>(
            processed.result.governed
        )

        assertEquals(
            CognitiveGovernedLearningFailure.GOVERNANCE_TARGET_REJECTED,
            rejected.reason
        )
        assertEquals(0, mutationCalls)
        assertEquals(AndroidHeartSemanticLearningSyncStatus.NOT_APPLICABLE, processed.result.semanticSync)
    }

    @Test
    fun governance_approval_cannot_bypass_missing_authority_grant() {
        val fixture = fixture()
        val installed = installCandidate(fixture.learning, "candidate-authority-denied")
        var preparedSnapshot: LearningApplicationMutationSnapshot? = null
        var mutationApplications = 0
        val preparation = LearningApplicationMutationPreparationPort { plan ->
            val generation = LearningApplicationMutationGeneration(1)
            preparedSnapshot = LearningApplicationMutationSnapshot(plan, generation)
            LearningApplicationMutationPreparationResult.Prepared(
                object : LearningApplicationMutationPreparedOwnership {
                    override val plan = plan
                    override val generation = generation
                    override fun remove(): Boolean = true
                }
            )
        }
        val inspection = LearningApplicationMutationInspectionPort { id ->
            preparedSnapshot?.takeIf { it.plan.id == id }
        }
        val bridge = bridge { gate ->
            LearningApplicationMutationApplicationPort { reference ->
                mutationApplications += 1
                LearningApplicationMutationApplicationResult.AuthorizationRejected(
                    gate.authorize(reference)
                )
            }
        }

        val composition = assertIs<AndroidHeartProductionGovernedLearningCreateResult.Ready>(
            createInternal(
                fixture = fixture,
                bridge = bridge,
                governance = CognitiveLearningGovernancePort {
                    CognitiveLearningGovernanceResult.Approved(
                        LearningApplicationTarget.MEMORY,
                        "explicit trusted approval"
                    )
                },
                materialization = CognitiveLearningApplicationMaterializationPort {
                    CognitiveLearningApplicationMaterializationResult.Succeeded(
                        "bounded learned memory"
                    )
                },
                mutationPreparation = preparation,
                mutationInspection = inspection
            )
        ).composition

        val processed = assertIs<AndroidHeartProductionGovernedLearningProcessResult.Processed>(
            composition.process(
                CognitiveLearningReference(
                    installed.candidate.id,
                    installed.generation
                )
            )
        )

        val rejected = assertIs<CognitiveGovernedLearningResult.Rejected>(
            processed.result.governed
        )
        assertEquals(CognitiveGovernedLearningFailure.MUTATION_APPLY_REJECTED, rejected.reason)
        assertEquals(1, mutationApplications)
        assertEquals(AndroidHeartSemanticLearningSyncStatus.NOT_APPLICABLE, processed.result.semanticSync)
    }

    private data class Fixture(
        val foundation: FoundationComposition,
        val learning: LearningComposition,
        val policies: LearningPolicyComposition,
        val policyReference: LearningPolicyReference,
        val authority: CapabilityAuthorityComposition,
        val ids: CognitiveArtifactIdSource,
        val timestamps: CognitiveTimestampSource
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "prod-learning-test" }
        )
        val policies = LearningPolicyComposition(foundation)
        val policy = assertIs<LearningPolicyInstallResult.Installed>(
            policies.install(
                LearningPolicy(
                    id = LearningPolicyId("production-memory-policy"),
                    rule = "trusted memory learning policy",
                    createdAt = Instant.parse("2026-09-08T00:00:00Z")
                )
            )
        ).ownership
        val counter = AtomicInteger(0)
        return Fixture(
            foundation = foundation,
            learning = LearningComposition(foundation),
            policies = policies,
            policyReference = LearningPolicyReference(
                policy.policy.id,
                policy.generation
            ),
            authority = CapabilityAuthorityComposition(foundation),
            ids = CognitiveArtifactIdSource { kind: CognitiveArtifactIdKind ->
                "prod-learning-" + kind.name.lowercase() + "-" + counter.incrementAndGet()
            },
            timestamps = CognitiveTimestampSource {
                Instant.parse("2026-09-08T00:00:01Z")
            }
        )
    }

    private fun installCandidate(
        learning: LearningComposition,
        id: String
    ): pro.liliya.core.learning.LearningOwnership =
        assertIs<LearningInstallResult.Installed>(
            learning.install(
                LearningCandidate(
                    id = LearningCandidateId(id),
                    origin = LearningOrigin.Declared(
                        LearningSourceId("production-learning-test")
                    ),
                    proposal = "candidate proposal",
                    createdAt = Instant.parse("2026-09-08T00:00:00Z")
                )
            )
        ).ownership

    private fun bridge(
        mutationApplication:
            (LearningApplicationMutationAuthorizationGate) -> LearningApplicationMutationApplicationPort
    ): AndroidHeartProductionGovernedLearningBridge =
        object : AndroidHeartProductionGovernedLearningBridge {
            override fun state(): HeartRuntimeState = HeartRuntimeState.READY

            override fun mutationApplicationPort(
                authorizationGate: LearningApplicationMutationAuthorizationGate
            ): LearningApplicationMutationApplicationPort =
                mutationApplication(authorizationGate)

            override fun governedLearning(
                composition: pro.liliya.core.cognitive.CognitiveGovernedLearningComposition
            ): AndroidHeartGovernedLearningComposition =
                AndroidHeartGovernedLearningComposition(
                    governed = AndroidHeartGovernedLearningPort { reference ->
                        composition.process(reference)
                    },
                    semantic = AndroidHeartAppliedSemanticSyncPort {
                        AndroidHeartSemanticLearningSyncStatus.SYNCHRONIZED
                    },
                    onSemanticUnavailable = {}
                )
        }

    private fun createInternal(
        fixture: Fixture,
        bridge: AndroidHeartProductionGovernedLearningBridge,
        governance: CognitiveLearningGovernancePort =
            CognitiveLearningGovernancePort {
                CognitiveLearningGovernanceResult.Rejected("default rejection")
            },
        materialization: CognitiveLearningApplicationMaterializationPort =
            CognitiveLearningApplicationMaterializationPort {
                CognitiveLearningApplicationMaterializationResult.Rejected(
                    pro.liliya.core.cognitive.CognitiveLearningApplicationMaterializationFailure
                        .MATERIALIZER_REJECTED
                )
            },
        mutationPreparation: LearningApplicationMutationPreparationPort =
            LearningApplicationMutationPreparationPort {
                LearningApplicationMutationPreparationResult.Rejected("not used")
            },
        mutationInspection: LearningApplicationMutationInspectionPort =
            LearningApplicationMutationInspectionPort { null }
    ): AndroidHeartProductionGovernedLearningCreateResult =
        AndroidHeartProductionGovernedLearningAssembly.createInternal(
            bridge = bridge,
            foundation = fixture.foundation,
            scope = CognitiveRuntimeScopeId("production-learning-scope"),
            learning = fixture.learning,
            policies = fixture.policies,
            policyReference = fixture.policyReference,
            authority = fixture.authority,
            principal = AuthorityPrincipal("production-learning-principal"),
            governance = governance,
            materialization = materialization,
            mutationPreparation = mutationPreparation,
            mutationInspection = mutationInspection,
            artifactIds = fixture.ids,
            timestamps = fixture.timestamps,
            limits = CognitiveRuntimeLimits()
        )
}
