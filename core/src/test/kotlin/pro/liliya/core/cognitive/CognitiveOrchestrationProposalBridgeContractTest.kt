package pro.liliya.core.cognitive

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.authority.CapabilityOwnershipResult
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.authority.DirectAuthorityGrant
import pro.liliya.core.authority.DirectAuthorityGrantOwnershipResult
import pro.liliya.core.capability.CapabilityDescriptor
import pro.liliya.core.capability.CapabilityProviderId
import pro.liliya.core.decision.DecisionComposition
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.execution.ExecutionActionId
import pro.liliya.core.execution.ExecutionComposition
import pro.liliya.core.execution.ExecutionExecutor
import pro.liliya.core.execution.ExecutionResult
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.orchestration.OrchestrationActionPolicy
import pro.liliya.core.orchestration.OrchestrationComposition
import pro.liliya.core.orchestration.OrchestrationExecutionPreflight
import pro.liliya.core.orchestration.OrchestrationExecutionPreflightRequest
import pro.liliya.core.orchestration.OrchestrationExecutionPreflightResult
import pro.liliya.core.orchestration.ControlledOrchestrationAuthorization
import pro.liliya.core.orchestration.ControlledOrchestrationExecution
import pro.liliya.core.orchestration.ControlledOrchestrationExecutionResult
import pro.liliya.core.orchestration.OrchestrationIntentId
import pro.liliya.core.planning.PlanningComposition
import pro.liliya.core.reasoning.ReasoningComposition

class CognitiveOrchestrationProposalBridgeContractTest {
    @Test
    fun accepted_model_cognition_creates_inert_orchestration_proposal_without_authority_or_execution() {
        val fixture = fixture()
        val turn = fixture.beginAndGenerate()
        val generated = fixture.generated!!

        val proposed = assertIs<CognitiveOrchestrationProposalResult.Proposed>(
            fixture.runtime.proposeOrchestration(
                CognitiveOrchestrationProposalRequest(
                    turn = turn,
                    intentId = OrchestrationIntentId("model-proposal-1"),
                    description = "selected model action proposal",
                    createdAt = Instant.parse("2026-09-17T16:30:00Z")
                )
            )
        )

        val installed = fixture.orchestration.inspect(proposed.ownership.intent.id)!!
        assertEquals(generated.decision.id, installed.intent.decision.decisionId)
        assertEquals(generated.decision.generation, installed.intent.decision.generation)
        assertEquals(
            fixture.decisions.inspect(generated.decision.id)!!.decision.selectedOptionId,
            installed.intent.decision.selectedOptionId
        )
        assertEquals(ExecutionActionId("safe-action"), installed.intent.actionId)
        assertEquals(CognitiveTurnLifecycle.COGNITION_READY, fixture.runtime.currentLifecycle())

        val diagnosticCodes = fixture.diagnostics.snapshot().map { it.code }
        assertTrue("COGNITIVE_ORCHESTRATION_PROPOSAL_CREATED" in diagnosticCodes)
        assertTrue(diagnosticCodes.none { code ->
            code.startsWith("AUTHORITY_") ||
                code.startsWith("EXECUTION_") ||
                code.startsWith("ORCHESTRATION_AUTHORIZATION_") ||
                code.startsWith("ORCHESTRATION_EXECUTION_PREFLIGHT_")
        })
    }


    @Test
    fun bound_model_proposal_rejects_a_different_execution_action_before_authority() {
        val fixture = fixture()
        val turn = fixture.beginAndGenerate()
        val proposed = assertIs<CognitiveOrchestrationProposalResult.Proposed>(
            fixture.runtime.proposeOrchestration(
                CognitiveOrchestrationProposalRequest(
                    turn = turn,
                    intentId = OrchestrationIntentId("bound-model-proposal"),
                    description = "bound action proposal",
                    createdAt = Instant.parse("2026-09-17T16:32:00Z")
                )
            )
        )

        val preflight = OrchestrationExecutionPreflight(
            foundation = fixture.foundation,
            orchestration = fixture.orchestration,
            decisions = fixture.decisions,
            actionPolicies = mapOf(
                ExecutionActionId("safe-action") to OrchestrationActionPolicy(
                    capability = CapabilityId("safe-capability"),
                    scope = AuthorityScope("safe-scope")
                ),
                ExecutionActionId("different-action") to OrchestrationActionPolicy(
                    capability = CapabilityId("different-capability"),
                    scope = AuthorityScope("different-scope")
                )
            )
        )

        val rejected = assertIs<OrchestrationExecutionPreflightResult.Rejected>(
            preflight.check(
                OrchestrationExecutionPreflightRequest(
                    intentId = proposed.ownership.intent.id,
                    generation = proposed.ownership.generation,
                    principal = AuthorityPrincipal("proposal-principal"),
                    actionId = ExecutionActionId("different-action")
                )
            )
        )
        assertTrue(rejected.reason.contains("bound to a different execution action"))
        assertTrue(fixture.diagnostics.snapshot().none { event ->
            event.code.startsWith("AUTHORITY_") || event.code.startsWith("EXECUTION_")
        })
    }


    @Test
    fun proposal_remains_inert_and_reaches_executor_only_after_exact_authority_grant() {
        val fixture = fixture()
        val turn = fixture.beginAndGenerate()
        val proposal = assertIs<CognitiveOrchestrationProposalResult.Proposed>(
            fixture.runtime.proposeOrchestration(
                CognitiveOrchestrationProposalRequest(
                    turn = turn,
                    intentId = OrchestrationIntentId("authority-gated-proposal"),
                    description = "authority gated action proposal",
                    createdAt = Instant.parse("2026-09-17T16:33:00Z")
                )
            )
        )

        val action = ExecutionActionId("safe-action")
        val capability = CapabilityId("safe-capability")
        val scope = AuthorityScope("safe-scope")
        val principal = AuthorityPrincipal("liliya")
        val authority = CapabilityAuthorityComposition(fixture.foundation)
        assertIs<CapabilityOwnershipResult.Registered>(
            authority.registerCapability(
                CapabilityDescriptor(capability, CapabilityProviderId("safe-provider"))
            )
        )
        val executorCalls = AtomicInteger(0)
        val preflight = OrchestrationExecutionPreflight(
            foundation = fixture.foundation,
            orchestration = fixture.orchestration,
            decisions = fixture.decisions,
            actionPolicies = mapOf(
                action to OrchestrationActionPolicy(capability = capability, scope = scope)
            )
        )
        val authorization = ControlledOrchestrationAuthorization(
            foundation = fixture.foundation,
            preflight = preflight,
            capabilityAuthority = authority,
            executionActionCapabilities = mapOf(action to capability)
        )
        val execution = ExecutionComposition(
            foundation = fixture.foundation,
            capabilityAuthority = authority,
            executor = ExecutionExecutor { _, _ ->
                executorCalls.incrementAndGet()
                ExecutionResult.Succeeded
            },
            actionCapabilities = mapOf(action to capability)
        )
        val controlled = ControlledOrchestrationExecution(authorization, execution)
        val request = OrchestrationExecutionPreflightRequest(
            intentId = proposal.ownership.intent.id,
            generation = proposal.ownership.generation,
            principal = principal,
            actionId = action
        )

        assertEquals(0, executorCalls.get(), "creating a proposal must never execute it")
        assertIs<ControlledOrchestrationExecutionResult.Rejected>(controlled.execute(request))
        assertEquals(0, executorCalls.get(), "denied Authority must keep executor unreachable")

        assertIs<DirectAuthorityGrantOwnershipResult.Registered>(
            authority.registerDirectGrant(
                DirectAuthorityGrant(
                    principal = principal,
                    capability = capability,
                    scope = scope
                )
            )
        )
        assertIs<ControlledOrchestrationExecutionResult.Succeeded>(controlled.execute(request))
        assertEquals(1, executorCalls.get())
    }

    @Test
    fun stale_turn_cannot_create_orchestration_proposal() {
        val fixture = fixture()
        val turn = fixture.beginAndGenerate()
        assertIs<CognitiveTurnAbortResult.Aborted>(fixture.runtime.abortTurn(turn))

        assertIs<CognitiveOrchestrationProposalResult.Stale>(
            fixture.runtime.proposeOrchestration(
                CognitiveOrchestrationProposalRequest(
                    turn = turn,
                    intentId = OrchestrationIntentId("stale-model-proposal"),
                    description = "must remain inert",
                    createdAt = Instant.parse("2026-09-17T16:31:00Z")
                )
            )
        )
        assertNull(fixture.orchestration.find(OrchestrationIntentId("stale-model-proposal")))
    }

    private class Fixture(
        val runtime: CognitiveRuntimeComposition,
        val orchestration: OrchestrationComposition,
        val decisions: DecisionComposition,
        val diagnostics: InMemoryDiagnosticSink,
        val foundation: FoundationComposition
    ) {
        var generated: CognitiveGenerationResult.Succeeded? = null

        fun beginAndGenerate(): CognitiveTurnReference {
            val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
                runtime.beginTurn(
                    CognitiveTurnId("proposal-turn"),
                    CognitiveInput("private request")
                )
            ).turn.reference
            assertIs<CognitiveContextAssemblyResult.Published>(runtime.assembleContext(turn))
            generated = assertIs<CognitiveGenerationResult.Succeeded>(runtime.generateCognition(turn))
            return turn
        }
    }

    private fun fixture(): Fixture {
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
        val diagnosticSink = InMemoryDiagnosticSink()
        val logs = InMemoryLogWriter()
        val correlation = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(diagnosticSink),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "307-proposal-${correlation.incrementAndGet()}" }
        )
        val planning = PlanningComposition(foundation)
        val reasoning = ReasoningComposition(foundation)
        val decisions = DecisionComposition(foundation)
        val orchestration = OrchestrationComposition(foundation)
        val perKind = mutableMapOf<CognitiveArtifactIdKind, Int>()
        val ids = CognitiveArtifactIdSource { kind ->
            val next = (perKind[kind] ?: 0) + 1
            perKind[kind] = next
            "${kind.name.lowercase().replace('_', '-')}-$next"
        }
        val runtime = CognitiveRuntimeComposition(
            foundation = foundation,
            scope = CognitiveRuntimeScopeId("307-proposal-scope"),
            memoryRetrieval = MemoryRetrievalPort { MemoryRetrievalResult(emptyList()) },
            knowledgeRetrieval = KnowledgeRetrievalPort { KnowledgeRetrievalResult(emptyList()) },
            selfSnapshots = SelfSnapshotPort { null },
            personalitySnapshots = PersonalitySnapshotPort { emptyList() },
            inference = CognitiveInferencePort { request ->
                CognitiveInferenceResult.Succeeded(request.turn, validEnvelope())
            },
            limits = limits,
            materialization = StructuredCognitiveMaterializationPort(
                CognitiveStructuredResponseBudgets.from(limits)
            ),
            planning = planning,
            reasoning = reasoning,
            decision = decisions,
            artifactIds = ids,
            timestamps = CognitiveTimestampSource { Instant.parse("2026-09-17T16:29:00Z") },
            orchestration = orchestration,
            orchestrationActionResolver = CognitiveOrchestrationActionResolver {
                ExecutionActionId("safe-action")
            }
        )
        return Fixture(runtime, orchestration, decisions, diagnosticSink, foundation)
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
        "DECISION_OPTION_COUNT=2",
        "DECISION_OPTION=do nothing",
        "DECISION_OPTION=propose action",
        "DECISION_SELECTED_INDEX=1",
        "DECISION_RATIONALE=rationale",
        "RESULT_CONTENT=result",
        "REFLECTION_CONTENT=reflection",
        "LEARNING_PROPOSAL=learning",
        "END"
    ).joinToString("\n")
}
