package pro.liliya.core.cognitive

import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.modelengine.ModelEngineLoaderPort
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.protectedmodel.ProtectedModelAccessCoordinator
import pro.liliya.core.protectedmodel.ProtectedModelAccessPolicy
import pro.liliya.core.protectedmodel.ProtectedModelDekResolver
import pro.liliya.core.protectedmodel.ProtectedModelPackageVerifier
import pro.liliya.core.protectedmodel.ProtectedModelPayloadLoader
import pro.liliya.core.protectedmodel.ProtectedModelPolicyDecision
import pro.liliya.core.protectedmodel.ProtectedModelRuntimeOwnership
import pro.liliya.core.protectedmodel.ProtectedModelSignerResolver
import pro.liliya.core.runtime.hardening.RuntimeModelSessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CognitiveModelRuntimeResponseBudgetContractTest {
    @Test
    fun composition_projects_exact_non_default_runtime_limits_into_compiled_v1_prompt() {
        val limits = CognitiveRuntimeLimits(
            maxInferenceOutputChars = 4_321,
            maxModelPromptChars = 32_000,
            maxPlanningGoalChars = 111,
            maxPlanningSteps = 3,
            maxPlanningStepChars = 112,
            maxReasoningPremises = 4,
            maxReasoningPremiseChars = 113,
            maxReasoningAnalysisChars = 114,
            maxReasoningConclusionChars = 115,
            maxDecisionOptions = 5,
            maxDecisionOptionChars = 116,
            maxDecisionRationaleChars = 117,
            maxResultChars = 118,
            maxReflectionChars = 119,
            maxLearningProposalChars = 120
        )
        var compiledPrompt: String? = null
        val deterministic = DeterministicCognitiveModelRequestCompiler()
        val compiler = CognitiveModelRequestCompilerPort { request ->
            val result = deterministic.compile(request)
            if (result is CognitiveModelRequestCompilerResult.Compiled) {
                compiledPrompt = result.request.prompt
            }
            result
        }
        val logs = InMemoryLogWriter()
        val correlations = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "budget-contract-${correlations.incrementAndGet()}" }
        )
        val protectedAccess = ProtectedModelAccessCoordinator(
            policy = ProtectedModelAccessPolicy { ProtectedModelPolicyDecision.Allowed },
            ownership = ProtectedModelRuntimeOwnership(),
            loader = ProtectedModelPayloadLoader(
                verifier = ProtectedModelPackageVerifier(
                    ProtectedModelSignerResolver { _, _ -> null }
                ),
                dekResolver = ProtectedModelDekResolver { _, _ -> null },
                maxPlaintextSizeBytes = 1L
            )
        )
        val composition = CognitiveModelRuntimeComposition(
            foundation = foundation,
            protectedAccess = protectedAccess,
            engineLoader = ModelEngineLoaderPort { _, _ -> error("engine load must not run") },
            compiler = compiler,
            sessionIds = CognitiveModelRuntimeSessionIdSource {
                RuntimeModelSessionId("budget-contract-session")
            },
            limits = limits
        )
        val turn = CognitiveTurnReference(
            CognitiveTurnId("private-budget-contract-turn"),
            CognitiveTurnGeneration(1)
        )

        composition.inferencePort.infer(
            CognitiveInferenceRequest(
                turn = turn,
                input = CognitiveInput("private budget contract input"),
                context = CognitiveContextSnapshot(turn, emptyList()),
                maxOutputChars = 1_024
            )
        )

        val prompt = assertNotNull(compiledPrompt)
        assertTrue(prompt.contains("LIMIT_PROTOCOL_OUTPUT_CHARS=4321"))
        assertTrue(prompt.contains("LIMIT_PLANNING_GOAL_CHARS=111"))
        assertTrue(prompt.contains("LIMIT_PLANNING_STEPS=3"))
        assertTrue(prompt.contains("LIMIT_PLANNING_STEP_CHARS=112"))
        assertTrue(prompt.contains("LIMIT_REASONING_PREMISES=4"))
        assertTrue(prompt.contains("LIMIT_REASONING_PREMISE_CHARS=113"))
        assertTrue(prompt.contains("LIMIT_REASONING_ANALYSIS_CHARS=114"))
        assertTrue(prompt.contains("LIMIT_REASONING_CONCLUSION_CHARS=115"))
        assertTrue(prompt.contains("LIMIT_DECISION_OPTIONS=5"))
        assertTrue(prompt.contains("LIMIT_DECISION_OPTION_CHARS=116"))
        assertTrue(prompt.contains("LIMIT_DECISION_RATIONALE_CHARS=117"))
        assertTrue(prompt.contains("LIMIT_RESULT_CHARS=118"))
        assertTrue(prompt.contains("LIMIT_REFLECTION_CHARS=119"))
        assertTrue(prompt.contains("LIMIT_LEARNING_PROPOSAL_CHARS=120"))
        assertEquals(0, composition.operationSupervisor.inFlightCount())
    }
}
