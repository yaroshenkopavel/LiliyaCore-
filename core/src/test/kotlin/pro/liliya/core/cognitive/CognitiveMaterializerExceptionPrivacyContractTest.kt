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

class CognitiveMaterializerExceptionPrivacyContractTest {
    @Test
    fun materializer_exception_message_and_private_inference_output_never_reach_logs() {
        val exceptionSecret = "slice7-materializer-exception-secret-never-log"
        val inferenceSecret = "slice7-private-inference-output-never-log"
        val logs = InMemoryLogWriter()
        val correlations = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "slice7-exception-${correlations.incrementAndGet()}" }
        )
        val composition = CognitiveRuntimeComposition(
            foundation = foundation,
            scope = CognitiveRuntimeScopeId("slice7-exception-scope"),
            memoryRetrieval = MemoryRetrievalPort { MemoryRetrievalResult(emptyList()) },
            knowledgeRetrieval = KnowledgeRetrievalPort { KnowledgeRetrievalResult(emptyList()) },
            selfSnapshots = SelfSnapshotPort { null },
            personalitySnapshots = PersonalitySnapshotPort { emptyList() },
            inference = CognitiveInferencePort { request ->
                CognitiveInferenceResult.Succeeded(request.turn, inferenceSecret)
            },
            limits = CognitiveRuntimeLimits(
                maxRuntimeScopeIdChars = 64,
                maxTurnIdChars = 128,
                maxInputChars = 128,
                maxContextItems = 8,
                maxContextItemChars = 128,
                maxRetrievalResults = 4,
                maxInferenceOutputChars = 4_096
            ),
            materialization = CognitiveMaterializationPort {
                throw IllegalStateException(exceptionSecret)
            },
            planning = PlanningComposition(foundation),
            reasoning = ReasoningComposition(foundation),
            decision = DecisionComposition(foundation),
            artifactIds = CognitiveArtifactIdSource { "must-not-allocate-${it.name}" },
            timestamps = CognitiveTimestampSource { Instant.parse("2026-09-01T23:15:00Z") }
        )
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            composition.beginTurn(
                CognitiveTurnId("slice7-exception-turn"),
                CognitiveInput("slice7 private input never log")
            )
        ).turn
        assertIs<CognitiveContextAssemblyResult.Published>(
            composition.assembleContext(turn.reference)
        )

        val rejected = assertIs<CognitiveGenerationResult.Rejected>(
            composition.generateCognition(turn.reference)
        )
        assertEquals(CognitiveGenerationFailure.MATERIALIZER_FAILED, rejected.reason)

        logs.snapshot().forEach { event ->
            assertFalse(event.message.contains(exceptionSecret))
            assertFalse(event.metadata.values.any { it.contains(exceptionSecret) })
            assertFalse(event.message.contains(inferenceSecret))
            assertFalse(event.metadata.values.any { it.contains(inferenceSecret) })
        }
    }
}
