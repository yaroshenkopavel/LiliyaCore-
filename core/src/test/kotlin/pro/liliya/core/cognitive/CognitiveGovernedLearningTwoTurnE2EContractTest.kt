package pro.liliya.core.cognitive

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.authority.CapabilityOwnershipResult
import pro.liliya.core.authority.DirectAuthorityGrant
import pro.liliya.core.authority.DirectAuthorityGrantOwnershipResult
import pro.liliya.core.capability.CapabilityDescriptor
import pro.liliya.core.capability.CapabilityProviderId
import pro.liliya.core.decision.DecisionComposition
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.knowledge.KnowledgeComposition
import pro.liliya.core.learning.LearningApplicationAuthorityContract
import pro.liliya.core.learning.LearningApplicationComposition
import pro.liliya.core.learning.LearningApplicationDownstreamReference
import pro.liliya.core.learning.LearningApplicationMutationApplier
import pro.liliya.core.learning.LearningApplicationMutationAuthorizationGate
import pro.liliya.core.learning.LearningApplicationMutationComposition
import pro.liliya.core.learning.LearningApplicationAuthorizer
import pro.liliya.core.learning.LearningApplicationPreflightValidator
import pro.liliya.core.learning.LearningApplicationTarget
import pro.liliya.core.learning.LearningComposition
import pro.liliya.core.learning.LearningDecisionComposition
import pro.liliya.core.learning.LearningPolicy
import pro.liliya.core.learning.LearningPolicyComposition
import pro.liliya.core.learning.LearningPolicyId
import pro.liliya.core.learning.LearningPolicyInstallResult
import pro.liliya.core.learning.LearningPolicyReference
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryComposition
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.planning.PlanningComposition
import pro.liliya.core.reasoning.ReasoningComposition
import pro.liliya.core.reflection.ReflectionComposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CognitiveGovernedLearningTwoTurnE2EContractTest {
    @Test
    fun authorized_learning_from_turn_a_is_authoritative_context_evidence_for_turn_b() {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val correlation = AtomicInteger(0)
        val clock = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(diagnostics),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "slice5-e2e-${correlation.incrementAndGet()}" }
        )

        val memory = MemoryComposition(foundation)
        val knowledge = KnowledgeComposition(foundation)
        val planning = PlanningComposition(foundation)
        val reasoning = ReasoningComposition(foundation)
        val decision = DecisionComposition(foundation)
        val reflection = ReflectionComposition(foundation)
        val learning = LearningComposition(foundation)
        val policies = LearningPolicyComposition(foundation)
        val learningDecisions = LearningDecisionComposition(foundation)
        val applications = LearningApplicationComposition(foundation)
        val mutations = LearningApplicationMutationComposition(foundation)
        val authority = CapabilityAuthorityComposition(foundation)
        val scope = CognitiveRuntimeScopeId("slice5-e2e-scope")
        val principal = AuthorityPrincipal("cognitive-learning-system")

        val policy = assertIs<LearningPolicyInstallResult.Installed>(
            policies.install(
                LearningPolicy(
                    id = LearningPolicyId("slice5-policy"),
                    rule = "allow governed memory learning for this contract",
                    createdAt = Instant.parse("2026-09-01T18:00:00Z")
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
        assertIs<DirectAuthorityGrantOwnershipResult.Registered>(
            authority.registerDirectGrant(
                DirectAuthorityGrant(
                    principal = principal,
                    capability = LearningApplicationAuthorityContract.capability,
                    scope = LearningApplicationAuthorityContract.scopeFor(
                        LearningApplicationTarget.MEMORY
                    )
                )
            )
        )

        val preflight = LearningApplicationPreflightValidator(
            applications,
            learningDecisions,
            learning,
            policies
        )
        val authorizer = LearningApplicationAuthorizer(preflight, authority)
        val mutationGate = LearningApplicationMutationAuthorizationGate(mutations, authorizer)
        val mutationApplier = LearningApplicationMutationApplier(
            mutations,
            mutationGate,
            memory,
            knowledge
        )

        val perKind = mutableMapOf<CognitiveArtifactIdKind, Int>()
        val ids = CognitiveArtifactIdSource { kind ->
            val next = (perKind[kind] ?: 0) + 1
            perKind[kind] = next
            "${kind.name.lowercase().replace('_', '-')}-$next"
        }
        val timestamps = CognitiveTimestampSource {
            Instant.parse("2026-09-01T18:10:00Z").plusSeconds(clock.incrementAndGet().toLong())
        }

        var turnBInferenceRequest: CognitiveInferenceRequest? = null
        val runtime = CognitiveRuntimeComposition(
            foundation = foundation,
            scope = scope,
            memoryRetrieval = MemoryCompositionRetrievalPort(memory),
            knowledgeRetrieval = KnowledgeCompositionRetrievalPort(knowledge),
            selfSnapshots = SelfSnapshotPort { null },
            personalitySnapshots = PersonalitySnapshotPort { emptyList() },
            inference = CognitiveInferencePort { request ->
                if (request.input.text == "turn-b-input") {
                    turnBInferenceRequest = request
                }
                CognitiveInferenceResult.Succeeded(
                    turn = request.turn,
                    output = "private inference for ${request.input.text}"
                )
            },
            materialization = CognitiveMaterializationPort {
                CognitiveMaterializationResult.Succeeded(
                    CognitiveMaterializationCandidate(
                        planningGoal = "private planning goal",
                        planningSteps = listOf("private planning step"),
                        reasoningPremises = listOf("private reasoning premise"),
                        reasoningAnalysis = "private reasoning analysis",
                        reasoningConclusion = "private reasoning conclusion",
                        decisionOptions = listOf("private option a", "private option b"),
                        selectedDecisionOptionIndex = 0,
                        decisionRationale = "private decision rationale"
                    )
                )
            },
            planning = planning,
            reasoning = reasoning,
            decision = decision,
            artifactIds = ids,
            timestamps = timestamps,
            outcomeMaterialization = CognitiveOutcomeMaterializationPort {
                CognitiveOutcomeMaterializationResult.Succeeded(
                    CognitiveOutcomeCandidate(
                        resultContent = "private turn a result",
                        reflectionContent = "private turn a reflection",
                        learningProposal = "private turn a learning proposal"
                    )
                )
            },
            reflection = reflection,
            learning = learning
        )

        val turnA = assertIs<CognitiveTurnRegistrationResult.Registered>(
            runtime.beginTurn(
                CognitiveTurnId("slice5-turn-a-private-id"),
                CognitiveInput("turn-a-input")
            )
        ).turn
        val contextA = assertIs<CognitiveContextAssemblyResult.Published>(
            runtime.assembleContext(turnA.reference)
        )
        assertEquals(0, contextA.itemCount)
        assertIs<CognitiveGenerationResult.Succeeded>(runtime.generateCognition(turnA.reference))
        val finalizedA = assertIs<CognitiveFinalizationResult.Completed>(
            runtime.finalizeCognition(turnA.reference)
        )
        assertEquals(CognitiveTurnLifecycle.COMPLETED, turnA.lifecycle())

        val governed = CognitiveGovernedLearningCoordinator(
            scope = scope,
            learning = learning,
            policies = policies,
            policyReference = LearningPolicyReference(policy.policy.id, policy.generation),
            governance = CognitiveLearningGovernancePort {
                CognitiveLearningGovernanceResult.Approved(
                    target = LearningApplicationTarget.MEMORY,
                    rationale = "trusted deterministic approval"
                )
            },
            decisions = learningDecisions,
            materialization = CognitiveLearningApplicationMaterializationPort {
                CognitiveLearningApplicationMaterializationResult.Succeeded(
                    "learned-evidence-from-turn-a"
                )
            },
            applications = applications,
            mutations = mutations,
            mutationApplier = mutationApplier,
            principal = principal,
            allowedTargets = listOf(LearningApplicationTarget.MEMORY),
            artifactIds = ids,
            timestamps = timestamps,
            limits = runtime.limits
        )

        val applied = assertIs<CognitiveGovernedLearningResult.Applied>(
            governed.process(finalizedA.learning)
        )
        val downstream = assertIs<LearningApplicationDownstreamReference.Memory>(
            applied.receipt.downstream
        )
        val learnedSnapshot = assertNotNull(memory.inspect(downstream.recordId))
        assertEquals("learned-evidence-from-turn-a", learnedSnapshot.record.content)

        val turnB = assertIs<CognitiveTurnRegistrationResult.Registered>(
            runtime.beginTurn(
                CognitiveTurnId("slice5-turn-b-private-id"),
                CognitiveInput("turn-b-input")
            )
        ).turn
        val contextB = assertIs<CognitiveContextAssemblyResult.Published>(
            runtime.assembleContext(turnB.reference)
        )
        assertEquals(1, contextB.itemCount)
        assertIs<CognitiveGenerationResult.Succeeded>(runtime.generateCognition(turnB.reference))

        val inferenceB = assertNotNull(turnBInferenceRequest)
        val learnedItem = inferenceB.context.items.single()
        val source = assertIs<CognitiveContextSourceReference.Memory>(learnedItem.source)
        assertEquals(learnedSnapshot.record.id, source.recordId)
        assertEquals(learnedSnapshot.generation, source.generation)
        assertEquals(learnedSnapshot.record.content, learnedItem.content)

        val forbidden = listOf(
            "slice5-turn-a-private-id",
            "slice5-turn-b-private-id",
            "private turn a result",
            "private turn a reflection",
            "private turn a learning proposal",
            "learned-evidence-from-turn-a"
        )
        logs.snapshot().forEach { event ->
            forbidden.forEach { secret ->
                assertFalse(event.message.contains(secret))
                assertFalse(event.metadata.values.any { it.contains(secret) })
            }
        }
        assertTrue(diagnostics.snapshot().none { diagnostic ->
            forbidden.any { secret -> diagnostic.toString().contains(secret) }
        })
    }
}
