package pro.liliya.core.cognitive

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.decision.DecisionComposition
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.planning.PlanningComposition
import pro.liliya.core.reasoning.ReasoningComposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CognitiveGenerationFailureContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val planning: PlanningComposition,
        val reasoning: ReasoningComposition,
        val decision: DecisionComposition,
        val composition: CognitiveRuntimeComposition
    )

    private fun candidate(): CognitiveMaterializationCandidate = CognitiveMaterializationCandidate(
        planningGoal = "private goal",
        planningSteps = listOf("private step"),
        reasoningPremises = listOf("private premise"),
        reasoningAnalysis = "private analysis",
        reasoningConclusion = "private conclusion",
        decisionOptions = listOf("private option"),
        selectedDecisionOptionIndex = 0,
        decisionRationale = "private rationale"
    )

    private fun fixture(
        inference: CognitiveInferencePort,
        materializer: CognitiveMaterializationPort,
        artifactIds: CognitiveArtifactIdSource? = null
    ): Fixture {
        val logs = InMemoryLogWriter()
        val correlation = AtomicInteger(0)
        val ids = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "failure-${correlation.incrementAndGet()}" }
        )
        val planning = PlanningComposition(foundation)
        val reasoning = ReasoningComposition(foundation)
        val decision = DecisionComposition(foundation)
        val idSource = artifactIds ?: CognitiveArtifactIdSource { kind ->
            "${kind.name.lowercase().replace('_', '-')}-${ids.incrementAndGet()}"
        }
        val composition = CognitiveRuntimeComposition(
            foundation = foundation,
            scope = CognitiveRuntimeScopeId("scope-failure"),
            memoryRetrieval = MemoryRetrievalPort { MemoryRetrievalResult(emptyList()) },
            knowledgeRetrieval = KnowledgeRetrievalPort { KnowledgeRetrievalResult(emptyList()) },
            selfSnapshots = SelfSnapshotPort { null },
            personalitySnapshots = PersonalitySnapshotPort { emptyList() },
            inference = inference,
            materialization = materializer,
            planning = planning,
            reasoning = reasoning,
            decision = decision,
            artifactIds = idSource,
            timestamps = CognitiveTimestampSource { Instant.parse("2026-09-01T16:45:00Z") }
        )
        return Fixture(logs, planning, reasoning, decision, composition)
    }

    private fun readyTurn(composition: CognitiveRuntimeComposition): CognitiveTurnHandle {
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            composition.beginTurn(CognitiveTurnId("primary-turn"), CognitiveInput("private input"))
        ).turn
        assertIs<CognitiveContextAssemblyResult.Published>(composition.assembleContext(turn.reference))
        return turn
    }

    @Test
    fun foreign_rejected_inference_is_classified_as_foreign_before_provider_rejection() {
        var materializerCalled = false
        val inference = CognitiveInferencePort { request ->
            CognitiveInferenceResult.Rejected(
                turn = CognitiveTurnReference(
                    id = CognitiveTurnId("foreign-turn"),
                    generation = request.turn.generation
                ),
                reason = CognitiveInferenceFailure.PROVIDER_REJECTED
            )
        }
        val materializer = CognitiveMaterializationPort {
            materializerCalled = true
            error("materializer must not run")
        }
        val f = fixture(inference, materializer)
        val turn = readyTurn(f.composition)

        val result = assertIs<CognitiveGenerationResult.Rejected>(f.composition.generateCognition(turn.reference))

        assertEquals(CognitiveGenerationFailure.FOREIGN_INFERENCE_RESULT, result.reason)
        assertFalse(materializerCalled)
        assertTrue(f.planning.snapshot().isEmpty())
        assertTrue(f.reasoning.snapshot().isEmpty())
        assertTrue(f.decision.snapshot().isEmpty())
        assertEquals(CognitiveTurnLifecycle.FAILED, turn.lifecycle())
    }

    @Test
    fun materializer_exception_is_structural_and_exception_message_is_not_logged() {
        val exceptionSecret = "materializer-secret-exception-message"
        val inference = CognitiveInferencePort { request ->
            CognitiveInferenceResult.Succeeded(request.turn, "private inference")
        }
        val materializer = CognitiveMaterializationPort {
            throw IllegalArgumentException(exceptionSecret)
        }
        val f = fixture(inference, materializer)
        val turn = readyTurn(f.composition)

        val result = assertIs<CognitiveGenerationResult.Rejected>(f.composition.generateCognition(turn.reference))

        assertEquals(CognitiveGenerationFailure.MATERIALIZER_FAILED, result.reason)
        assertTrue(f.planning.snapshot().isEmpty())
        assertTrue(f.reasoning.snapshot().isEmpty())
        assertTrue(f.decision.snapshot().isEmpty())
        assertEquals(CognitiveTurnLifecycle.FAILED, turn.lifecycle())
        f.logs.snapshot().forEach { event ->
            assertFalse(event.message.contains(exceptionSecret))
            assertFalse(event.metadata.values.any { it.contains(exceptionSecret) })
        }
    }

    @Test
    fun duplicate_id_across_artifact_kinds_fails_before_any_domain_installation() {
        val inference = CognitiveInferencePort { request ->
            CognitiveInferenceResult.Succeeded(request.turn, "private inference")
        }
        val materializer = CognitiveMaterializationPort {
            CognitiveMaterializationResult.Succeeded(candidate())
        }
        val f = fixture(
            inference = inference,
            materializer = materializer,
            artifactIds = CognitiveArtifactIdSource { "duplicate-id" }
        )
        val turn = readyTurn(f.composition)

        val result = assertIs<CognitiveGenerationResult.Rejected>(f.composition.generateCognition(turn.reference))

        assertEquals(CognitiveGenerationFailure.ARTIFACT_ID_OR_TIME_FAILED, result.reason)
        assertTrue(f.planning.snapshot().isEmpty())
        assertTrue(f.reasoning.snapshot().isEmpty())
        assertTrue(f.decision.snapshot().isEmpty())
        assertEquals(CognitiveTurnLifecycle.FAILED, turn.lifecycle())
    }
}
