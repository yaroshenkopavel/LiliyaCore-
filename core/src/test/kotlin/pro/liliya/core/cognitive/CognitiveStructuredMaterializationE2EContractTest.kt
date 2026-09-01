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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CognitiveStructuredMaterializationE2EContractTest {
    @Test
    fun production_structured_materializers_drive_generation_to_authoritative_finalization() {
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
            maxDecisionRationaleChars = 128,
            maxResultChars = 128,
            maxReflectionChars = 128,
            maxLearningProposalChars = 128
        )
        val responseBudgets = CognitiveStructuredResponseBudgets.from(limits)
        val output = validEnvelope()
        val logs = InMemoryLogWriter()
        val correlations = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "slice7-e2e-${correlations.incrementAndGet()}" }
        )
        val planning = PlanningComposition(foundation)
        val reasoning = ReasoningComposition(foundation)
        val decision = DecisionComposition(foundation)
        val reflection = ReflectionComposition(foundation)
        val learning = LearningComposition(foundation)
        val perKind = mutableMapOf<CognitiveArtifactIdKind, Int>()
        val ids = CognitiveArtifactIdSource { kind ->
            val next = (perKind[kind] ?: 0) + 1
            perKind[kind] = next
            "${kind.name.lowercase().replace('_', '-')}-$next"
        }
        val composition = CognitiveRuntimeComposition(
            foundation = foundation,
            scope = CognitiveRuntimeScopeId("slice7-e2e-scope"),
            memoryRetrieval = MemoryRetrievalPort { MemoryRetrievalResult(emptyList()) },
            knowledgeRetrieval = KnowledgeRetrievalPort { KnowledgeRetrievalResult(emptyList()) },
            selfSnapshots = SelfSnapshotPort { null },
            personalitySnapshots = PersonalitySnapshotPort { emptyList() },
            inference = CognitiveInferencePort { request ->
                CognitiveInferenceResult.Succeeded(request.turn, output)
            },
            limits = limits,
            materialization = StructuredCognitiveMaterializationPort(responseBudgets),
            planning = planning,
            reasoning = reasoning,
            decision = decision,
            artifactIds = ids,
            timestamps = CognitiveTimestampSource { Instant.parse("2026-09-01T23:00:00Z") },
            outcomeMaterialization = StructuredCognitiveOutcomeMaterializationPort(responseBudgets),
            reflection = reflection,
            learning = learning
        )

        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            composition.beginTurn(
                CognitiveTurnId("slice7-e2e-turn"),
                CognitiveInput("private slice7 input")
            )
        ).turn
        assertIs<CognitiveContextAssemblyResult.Published>(
            composition.assembleContext(turn.reference)
        )

        val generated = assertIs<CognitiveGenerationResult.Succeeded>(
            composition.generateCognition(turn.reference)
        )
        assertEquals(CognitiveTurnLifecycle.COGNITION_READY, turn.lifecycle())

        val planningSnapshot = planning.inspect(generated.planning.id)!!
        val reasoningSnapshot = reasoning.inspect(generated.reasoning.id)!!
        val decisionSnapshot = decision.inspect(generated.decision.id)!!
        assertEquals("goal", planningSnapshot.proposal.goal)
        assertEquals(listOf("step one", "step two"), planningSnapshot.proposal.steps.map { it.description })
        assertEquals(listOf("premise one", "premise two"), reasoningSnapshot.artifact.premises.map { it.content })
        assertEquals("analysis", reasoningSnapshot.artifact.analysis)
        assertEquals("conclusion", reasoningSnapshot.artifact.conclusion)
        assertEquals(listOf("option one", "option two"), decisionSnapshot.decision.options.map { it.description })
        assertEquals(decisionSnapshot.decision.options[1].id, decisionSnapshot.decision.selectedOptionId)
        assertEquals("rationale", decisionSnapshot.decision.rationale)

        val completed = assertIs<CognitiveFinalizationResult.Completed>(
            composition.finalizeCognition(turn.reference)
        )
        assertEquals(CognitiveTurnLifecycle.COMPLETED, turn.lifecycle())
        assertNull(composition.currentReference())
        assertEquals(generated.planning, completed.result.planning)
        assertEquals(generated.reasoning, completed.result.reasoning)
        assertEquals(generated.decision, completed.result.decision)
        assertEquals("result", completed.result.content)
        assertEquals("reflection", reflection.inspect(completed.reflection.id)!!.record.content)
        assertEquals("learning", learning.inspect(completed.learning.id)!!.candidate.proposal)
        assertTrue(logs.snapshot().none { event ->
            event.message.contains(output) || event.metadata.values.any { it.contains(output) }
        })
    }

    private fun validEnvelope(): String = listOf(
        "LILIYA_COGNITIVE_RESPONSE_V1",
        "PLANNING_GOAL=goal",
        "PLANNING_STEP_COUNT=2",
        "PLANNING_STEP=step one",
        "PLANNING_STEP=step two",
        "REASONING_PREMISE_COUNT=2",
        "REASONING_PREMISE=premise one",
        "REASONING_PREMISE=premise two",
        "REASONING_ANALYSIS=analysis",
        "REASONING_CONCLUSION=conclusion",
        "DECISION_OPTION_COUNT=2",
        "DECISION_OPTION=option one",
        "DECISION_OPTION=option two",
        "DECISION_SELECTED_INDEX=1",
        "DECISION_RATIONALE=rationale",
        "RESULT_CONTENT=result",
        "REFLECTION_CONTENT=reflection",
        "LEARNING_PROPOSAL=learning",
        "END"
    ).joinToString("\n")
}
