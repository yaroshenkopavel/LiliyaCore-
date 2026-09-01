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

class CognitiveStructuredMaterializationPrivacyContractTest {
    @Test
    fun malformed_private_response_is_rejected_without_payload_or_exception_leakage() {
        val limits = CognitiveRuntimeLimits(
            maxRuntimeScopeIdChars = 64,
            maxTurnIdChars = 128,
            maxInputChars = 128,
            maxContextItems = 8,
            maxContextItemChars = 128,
            maxRetrievalResults = 4,
            maxInferenceOutputChars = 4_096,
            maxPlanningGoalChars = 128,
            maxPlanningSteps = 4,
            maxPlanningStepChars = 128,
            maxReasoningPremises = 4,
            maxReasoningPremiseChars = 128,
            maxReasoningAnalysisChars = 128,
            maxReasoningConclusionChars = 128,
            maxDecisionOptions = 4,
            maxDecisionOptionChars = 128,
            maxDecisionRationaleChars = 128
        )
        val secret = "slice7-private-materializer-secret-never-log"
        val response = validEnvelope().replace(
            "REASONING_ANALYSIS=analysis",
            "UNKNOWN_FIELD=$secret\nREASONING_ANALYSIS=analysis"
        )
        val logs = InMemoryLogWriter()
        val correlations = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "slice7-privacy-${correlations.incrementAndGet()}" }
        )
        val composition = CognitiveRuntimeComposition(
            foundation = foundation,
            scope = CognitiveRuntimeScopeId("slice7-privacy-scope"),
            memoryRetrieval = MemoryRetrievalPort { MemoryRetrievalResult(emptyList()) },
            knowledgeRetrieval = KnowledgeRetrievalPort { KnowledgeRetrievalResult(emptyList()) },
            selfSnapshots = SelfSnapshotPort { null },
            personalitySnapshots = PersonalitySnapshotPort { emptyList() },
            inference = CognitiveInferencePort { request ->
                CognitiveInferenceResult.Succeeded(request.turn, response)
            },
            limits = limits,
            materialization = StructuredCognitiveMaterializationPort(
                CognitiveStructuredResponseBudgets.from(limits)
            ),
            planning = PlanningComposition(foundation),
            reasoning = ReasoningComposition(foundation),
            decision = DecisionComposition(foundation),
            artifactIds = CognitiveArtifactIdSource { "must-not-allocate-${it.name}" },
            timestamps = CognitiveTimestampSource { Instant.parse("2026-09-01T23:10:00Z") }
        )
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            composition.beginTurn(
                CognitiveTurnId("slice7-private-materializer-turn"),
                CognitiveInput("private input never log")
            )
        ).turn
        assertIs<CognitiveContextAssemblyResult.Published>(
            composition.assembleContext(turn.reference)
        )

        val rejected = assertIs<CognitiveGenerationResult.Rejected>(
            composition.generateCognition(turn.reference)
        )
        assertEquals(CognitiveGenerationFailure.MATERIALIZER_REJECTED, rejected.reason)

        logs.snapshot().forEach { event ->
            assertFalse(event.message.contains(secret))
            assertFalse(event.metadata.values.any { it.contains(secret) })
            assertFalse(event.message.contains(response))
            assertFalse(event.metadata.values.any { it.contains(response) })
        }
    }

    private fun validEnvelope(): String = listOf(
        "LILIYA_COGNITIVE_RESPONSE_V1",
        "PLANNING_GOAL=goal",
        "PLANNING_STEP_COUNT=1",
        "PLANNING_STEP=step",
        "REASONING_PREMISE_COUNT=1",
        "REASONING_PREMISE=premise",
        "REASONING_ANALYSIS=analysis",
        "REASONING_CONCLUSION=conclusion",
        "DECISION_OPTION_COUNT=1",
        "DECISION_OPTION=option",
        "DECISION_SELECTED_INDEX=0",
        "DECISION_RATIONALE=rationale",
        "RESULT_CONTENT=result",
        "REFLECTION_CONTENT=reflection",
        "LEARNING_PROPOSAL=learning",
        "END"
    ).joinToString("\n")
}
