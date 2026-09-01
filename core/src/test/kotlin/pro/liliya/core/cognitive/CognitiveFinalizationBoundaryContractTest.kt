package pro.liliya.core.cognitive

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.decision.DecisionComposition
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.learning.LearningComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.planning.PlanningComposition
import pro.liliya.core.reasoning.ReasoningComposition
import pro.liliya.core.reflection.ReflectionComposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class CognitiveFinalizationBoundaryContractTest {
    @Test
    fun duplicate_finalization_is_one_shot_and_stale_after_completion() {
        val composition = completeFixture(includeFinalization = true)
        val turn = readyAndGeneratedTurn(composition)

        assertIs<CognitiveFinalizationResult.Completed>(composition.finalizeCognition(turn.reference))
        assertIs<CognitiveFinalizationResult.Stale>(composition.finalizeCognition(turn.reference))
        assertEquals(CognitiveTurnLifecycle.COMPLETED, turn.lifecycle())
        assertNull(composition.currentReference())
    }

    @Test
    fun missing_finalization_dependencies_fail_closed_without_terminating_ready_turn() {
        val composition = completeFixture(includeFinalization = false)
        val turn = readyAndGeneratedTurn(composition)

        val rejected = assertIs<CognitiveFinalizationResult.Rejected>(
            composition.finalizeCognition(turn.reference)
        )

        assertEquals(CognitiveFinalizationFailure.DEPENDENCIES_UNAVAILABLE, rejected.reason)
        assertEquals(CognitiveTurnLifecycle.COGNITION_READY, turn.lifecycle())
        assertEquals(turn.reference, composition.currentReference())
    }

    @Test
    fun public_finalization_values_expose_no_domain_ownership_or_mutation_methods() {
        val completedMethods = CognitiveFinalizationResult.Completed::class.java.methods.map { it.name }.toSet()
        val resultMethods = CognitiveResult::class.java.methods.map { it.name }.toSet()

        assertFalse(completedMethods.any { it == "remove" || it == "install" || it == "fail" || it == "complete" })
        assertFalse(resultMethods.any { it == "remove" || it == "install" || it == "fail" || it == "complete" })
    }

    private fun readyAndGeneratedTurn(composition: CognitiveRuntimeComposition): CognitiveTurnHandle {
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            composition.beginTurn(CognitiveTurnId("boundary-turn"), CognitiveInput("private input"))
        ).turn
        assertIs<CognitiveContextAssemblyResult.Published>(composition.assembleContext(turn.reference))
        assertIs<CognitiveGenerationResult.Succeeded>(composition.generateCognition(turn.reference))
        return turn
    }

    private fun completeFixture(includeFinalization: Boolean): CognitiveRuntimeComposition {
        val logs = InMemoryLogWriter()
        val correlation = AtomicInteger(0)
        val ids = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "boundary-${correlation.incrementAndGet()}" }
        )
        val planning = PlanningComposition(foundation)
        val reasoning = ReasoningComposition(foundation)
        val decision = DecisionComposition(foundation)
        val reflection = ReflectionComposition(foundation)
        val learning = LearningComposition(foundation)
        val artifactIds = CognitiveArtifactIdSource { kind ->
            "${kind.name.lowercase().replace('_', '-')}-${ids.incrementAndGet()}"
        }
        return CognitiveRuntimeComposition(
            foundation = foundation,
            scope = CognitiveRuntimeScopeId("boundary-scope"),
            memoryRetrieval = MemoryRetrievalPort { MemoryRetrievalResult(emptyList()) },
            knowledgeRetrieval = KnowledgeRetrievalPort { KnowledgeRetrievalResult(emptyList()) },
            selfSnapshots = SelfSnapshotPort { null },
            personalitySnapshots = PersonalitySnapshotPort { emptyList() },
            inference = CognitiveInferencePort { request ->
                CognitiveInferenceResult.Succeeded(request.turn, "private inference")
            },
            materialization = CognitiveMaterializationPort {
                CognitiveMaterializationResult.Succeeded(
                    CognitiveMaterializationCandidate(
                        planningGoal = "private goal",
                        planningSteps = listOf("private step"),
                        reasoningPremises = listOf("private premise"),
                        reasoningAnalysis = "private analysis",
                        reasoningConclusion = "private conclusion",
                        decisionOptions = listOf("private option"),
                        selectedDecisionOptionIndex = 0,
                        decisionRationale = "private rationale"
                    )
                )
            },
            planning = planning,
            reasoning = reasoning,
            decision = decision,
            artifactIds = artifactIds,
            timestamps = CognitiveTimestampSource { Instant.parse("2026-09-01T18:00:00Z") },
            outcomeMaterialization = if (includeFinalization) {
                CognitiveOutcomeMaterializationPort {
                    CognitiveOutcomeMaterializationResult.Succeeded(
                        CognitiveOutcomeCandidate(
                            resultContent = "private result",
                            reflectionContent = "private reflection",
                            learningProposal = "private learning"
                        )
                    )
                }
            } else {
                null
            },
            reflection = if (includeFinalization) reflection else null,
            learning = if (includeFinalization) learning else null
        )
    }
}
