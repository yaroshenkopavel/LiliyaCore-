package pro.liliya.core.cognitive

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.decision.DecisionComposition
import pro.liliya.core.decision.DecisionInputReference
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.planning.PlanningComposition
import pro.liliya.core.reasoning.ReasoningArtifact
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningComposition
import pro.liliya.core.reasoning.ReasoningInstallResult
import pro.liliya.core.reasoning.ReasoningOrigin
import pro.liliya.core.reasoning.ReasoningPremise
import pro.liliya.core.reasoning.ReasoningPremiseId
import pro.liliya.core.reasoning.ReasoningSourceId
import pro.liliya.core.reasoning.ReasoningSourceReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CognitiveGenerationCoordinatorContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val planning: PlanningComposition,
        val reasoning: ReasoningComposition,
        val decision: DecisionComposition,
        val composition: CognitiveRuntimeComposition,
        val limits: CognitiveRuntimeLimits,
        val idCalls: AtomicInteger
    )

    private fun candidate(
        planningGoal: String = "private planning goal"
    ): CognitiveMaterializationCandidate = CognitiveMaterializationCandidate(
        planningGoal = planningGoal,
        planningSteps = listOf("private planning step"),
        reasoningPremises = listOf("private reasoning premise"),
        reasoningAnalysis = "private reasoning analysis",
        reasoningConclusion = "private reasoning conclusion",
        decisionOptions = listOf("private option a", "private option b"),
        selectedDecisionOptionIndex = 1,
        decisionRationale = "private decision rationale"
    )

    private fun fixture(
        prefix: String,
        inference: CognitiveInferencePort,
        materialization: CognitiveMaterializationPort,
        streamingInference: CognitiveStreamingInferencePort? = null,
        limits: CognitiveRuntimeLimits = CognitiveRuntimeLimits(
            maxRuntimeScopeIdChars = 64,
            maxTurnIdChars = 128,
            maxInputChars = 128,
            maxContextItems = 8,
            maxContextItemChars = 128,
            maxRetrievalResults = 4,
            maxInferenceOutputChars = 64,
            maxPlanningGoalChars = 64,
            maxPlanningSteps = 4,
            maxPlanningStepChars = 64,
            maxReasoningPremises = 4,
            maxReasoningPremiseChars = 64,
            maxReasoningAnalysisChars = 64,
            maxReasoningConclusionChars = 64,
            maxDecisionOptions = 4,
            maxDecisionOptionChars = 64,
            maxDecisionRationaleChars = 64
        )
    ): Fixture {
        val logs = InMemoryLogWriter()
        val correlation = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "$prefix-${correlation.incrementAndGet()}" }
        )
        val planning = PlanningComposition(foundation)
        val reasoning = ReasoningComposition(foundation)
        val decision = DecisionComposition(foundation)
        val idCalls = AtomicInteger(0)
        val perKind = mutableMapOf<CognitiveArtifactIdKind, Int>()
        val ids = CognitiveArtifactIdSource { kind ->
            idCalls.incrementAndGet()
            val next = (perKind[kind] ?: 0) + 1
            perKind[kind] = next
            "${kind.name.lowercase().replace('_', '-')}-$next"
        }
        val composition = CognitiveRuntimeComposition(
            foundation = foundation,
            scope = CognitiveRuntimeScopeId("scope-$prefix"),
            memoryRetrieval = MemoryRetrievalPort { MemoryRetrievalResult(emptyList()) },
            knowledgeRetrieval = KnowledgeRetrievalPort { KnowledgeRetrievalResult(emptyList()) },
            selfSnapshots = SelfSnapshotPort { null },
            personalitySnapshots = PersonalitySnapshotPort { emptyList() },
            inference = inference,
            streamingInference = streamingInference,
            limits = limits,
            materialization = materialization,
            planning = planning,
            reasoning = reasoning,
            decision = decision,
            artifactIds = ids,
            timestamps = CognitiveTimestampSource { Instant.parse("2026-09-01T16:30:00Z") }
        )
        return Fixture(logs, planning, reasoning, decision, composition, limits, idCalls)
    }

    private fun readyTurn(
        composition: CognitiveRuntimeComposition,
        id: String = "slice3-turn",
        input: String = "private user input"
    ): CognitiveTurnHandle {
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            composition.beginTurn(CognitiveTurnId(id), CognitiveInput(input))
        ).turn
        assertIs<CognitiveContextAssemblyResult.Published>(
            composition.assembleContext(turn.reference)
        )
        assertEquals(CognitiveTurnLifecycle.CONTEXT_READY, turn.lifecycle())
        return turn
    }

    @Test
    fun successful_generation_propagates_exact_budgets_and_installs_exact_decision_inputs() {
        var inferenceBudget: Int? = null
        var materializationBudgets: CognitiveMaterializationBudgets? = null
        val inference = CognitiveInferencePort { request ->
            inferenceBudget = request.maxOutputChars
            CognitiveInferenceResult.Succeeded(request.turn, "private inference output")
        }
        val materializer = CognitiveMaterializationPort { request ->
            materializationBudgets = request.budgets
            CognitiveMaterializationResult.Succeeded(candidate())
        }
        val f = fixture("success", inference, materializer)
        val turn = readyTurn(f.composition)

        val result = assertIs<CognitiveGenerationResult.Succeeded>(
            f.composition.generateCognition(turn.reference)
        )

        assertEquals(f.limits.maxInferenceOutputChars, inferenceBudget)
        assertEquals(CognitiveMaterializationBudgets.from(f.limits), materializationBudgets)
        assertEquals(CognitiveTurnLifecycle.COGNITION_READY, turn.lifecycle())

        val planning = f.planning.inspect(result.planning.id)!!
        val reasoning = f.reasoning.inspect(result.reasoning.id)!!
        val decision = f.decision.inspect(result.decision.id)!!
        assertEquals(result.planning.generation, planning.generation)
        assertEquals(result.reasoning.generation, reasoning.generation)
        assertEquals(result.decision.generation, decision.generation)
        assertEquals(
            listOf(
                DecisionInputReference.Planning(result.planning.id, result.planning.generation),
                DecisionInputReference.Reasoning(result.reasoning.id, result.reasoning.generation)
            ),
            decision.decision.inputs
        )
    }

    @Test
    fun generation_observability_never_contains_raw_turn_id_or_private_payloads_even_indirectly() {
        val rawTurnId = "secret-raw-turn-id-never-log"
        val privateInput = "secret-user-input-never-log"
        val privateInference = "secret-inference-never-log"
        val privateCandidate = candidate()
        val inference = CognitiveInferencePort { request ->
            CognitiveInferenceResult.Succeeded(request.turn, privateInference)
        }
        val materializer = CognitiveMaterializationPort {
            CognitiveMaterializationResult.Succeeded(privateCandidate)
        }
        val f = fixture("privacy", inference, materializer)
        val turn = readyTurn(f.composition, id = rawTurnId, input = privateInput)

        assertIs<CognitiveGenerationResult.Succeeded>(
            f.composition.generateCognition(turn.reference)
        )

        val forbidden = listOf(
            rawTurnId,
            privateInput,
            privateInference,
            privateCandidate.planningGoal,
            privateCandidate.planningSteps.single(),
            privateCandidate.reasoningPremises.single(),
            privateCandidate.reasoningAnalysis,
            privateCandidate.reasoningConclusion,
            privateCandidate.decisionOptions.first(),
            privateCandidate.decisionRationale
        )
        f.logs.snapshot().forEach { event ->
            forbidden.forEach { secret ->
                assertFalse(event.message.contains(secret))
                assertFalse(event.metadata.values.any { it.contains(secret) })
            }
            assertFalse(event.metadata.keys.any { it == "cognitiveTurnId" })
            assertFalse(event.metadata.keys.any { it.contains("authority", ignoreCase = true) })
            assertFalse(event.metadata.keys.any { it.contains("license", ignoreCase = true) })
        }
    }

    @Test
    fun provider_exception_is_structural_and_exception_message_is_not_logged() {
        val exceptionSecret = "provider-secret-exception-message"
        val inference = CognitiveInferencePort {
            throw IllegalStateException(exceptionSecret)
        }
        val materializer = CognitiveMaterializationPort {
            error("materializer must not run")
        }
        val f = fixture("provider-failure", inference, materializer)
        val turn = readyTurn(f.composition)

        val result = assertIs<CognitiveGenerationResult.Rejected>(
            f.composition.generateCognition(turn.reference)
        )
        assertEquals(CognitiveGenerationFailure.INFERENCE_PROVIDER_FAILED, result.reason)
        assertEquals(CognitiveTurnLifecycle.FAILED, turn.lifecycle())
        assertEquals(null, f.composition.currentReference())
        f.logs.snapshot().forEach { event ->
            assertFalse(event.message.contains(exceptionSecret))
            assertFalse(event.metadata.values.any { it.contains(exceptionSecret) })
        }
    }

    @Test
    fun over_bound_candidate_fails_before_authoritative_id_allocation_or_domain_installation() {
        val inference = CognitiveInferencePort { request ->
            CognitiveInferenceResult.Succeeded(request.turn, "bounded")
        }
        lateinit var limits: CognitiveRuntimeLimits
        val materializer = CognitiveMaterializationPort {
            CognitiveMaterializationResult.Succeeded(
                candidate(planningGoal = "x".repeat(limits.maxPlanningGoalChars + 1))
            )
        }
        limits = CognitiveRuntimeLimits(
            maxRuntimeScopeIdChars = 64,
            maxTurnIdChars = 128,
            maxInputChars = 128,
            maxContextItems = 8,
            maxContextItemChars = 128,
            maxRetrievalResults = 4,
            maxInferenceOutputChars = 64,
            maxPlanningGoalChars = 16,
            maxPlanningSteps = 4,
            maxPlanningStepChars = 64,
            maxReasoningPremises = 4,
            maxReasoningPremiseChars = 64,
            maxReasoningAnalysisChars = 64,
            maxReasoningConclusionChars = 64,
            maxDecisionOptions = 4,
            maxDecisionOptionChars = 64,
            maxDecisionRationaleChars = 64
        )
        val f = fixture("candidate-limit", inference, materializer, limits)
        val turn = readyTurn(f.composition)

        val result = assertIs<CognitiveGenerationResult.Rejected>(
            f.composition.generateCognition(turn.reference)
        )
        assertEquals(CognitiveGenerationFailure.CANDIDATE_REJECTED, result.reason)
        assertEquals(0, f.idCalls.get())
        assertTrue(f.planning.snapshot().isEmpty())
        assertTrue(f.reasoning.snapshot().isEmpty())
        assertTrue(f.decision.snapshot().isEmpty())
        assertEquals(CognitiveTurnLifecycle.FAILED, turn.lifecycle())
    }

    @Test
    fun reasoning_install_failure_compensates_planning_without_removing_preexisting_reasoning() {
        val inference = CognitiveInferencePort { request ->
            CognitiveInferenceResult.Succeeded(request.turn, "bounded")
        }
        val materializer = CognitiveMaterializationPort {
            CognitiveMaterializationResult.Succeeded(candidate())
        }
        val f = fixture("compensation", inference, materializer)
        val preexisting = ReasoningArtifact(
            id = ReasoningArtifactId("reasoning-artifact-1"),
            origin = ReasoningOrigin(
                sourceId = ReasoningSourceId("preexisting"),
                sourceReference = ReasoningSourceReference("preexisting")
            ),
            premises = listOf(
                ReasoningPremise(ReasoningPremiseId("premise-preexisting"), "preexisting premise")
            ),
            analysis = "preexisting analysis",
            conclusion = "preexisting conclusion",
            createdAt = Instant.parse("2026-09-01T16:00:00Z")
        )
        assertIs<ReasoningInstallResult.Installed>(f.reasoning.install(preexisting))
        val turn = readyTurn(f.composition)

        val result = assertIs<CognitiveGenerationResult.Rejected>(
            f.composition.generateCognition(turn.reference)
        )

        assertEquals(CognitiveGenerationFailure.REASONING_INSTALL_FAILED, result.reason)
        assertTrue(f.planning.snapshot().isEmpty())
        assertEquals(listOf(preexisting), f.reasoning.snapshot())
        assertTrue(f.decision.snapshot().isEmpty())
        assertEquals(CognitiveTurnLifecycle.FAILED, turn.lifecycle())
    }
}
