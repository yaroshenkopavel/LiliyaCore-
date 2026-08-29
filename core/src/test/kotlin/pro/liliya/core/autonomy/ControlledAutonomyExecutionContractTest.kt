package pro.liliya.core.autonomy

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.authority.CapabilityOwnershipResult
import pro.liliya.core.authority.DirectAuthorityGrant
import pro.liliya.core.authority.DirectAuthorityGrantOwnershipResult
import pro.liliya.core.capability.CapabilityDescriptor
import pro.liliya.core.capability.CapabilityProviderId
import pro.liliya.core.decision.DecisionComposition
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.decision.DecisionOption
import pro.liliya.core.decision.DecisionOptionId
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
import pro.liliya.core.orchestration.ControlledOrchestrationAuthorization
import pro.liliya.core.orchestration.ControlledOrchestrationExecution
import pro.liliya.core.orchestration.OrchestrationActionPolicy
import pro.liliya.core.orchestration.OrchestrationComposition
import pro.liliya.core.orchestration.OrchestrationExecutionPreflight
import pro.liliya.core.orchestration.OrchestrationIntentId
import pro.liliya.core.planning.PlanningComposition
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.planning.PlanningStep
import pro.liliya.core.planning.PlanningStepId
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningComposition
import pro.liliya.core.reasoning.ReasoningPremise
import pro.liliya.core.reasoning.ReasoningPremiseId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ControlledAutonomyExecutionContractTest {
    private val action = ExecutionActionId("device.open.settings")
    private val capability = CapabilityId("device.settings.open")
    private val scope = AuthorityScope("device.settings")
    private val principal = AuthorityPrincipal("liliya")

    private data class Fixture(
        val logs: InMemoryLogWriter,
        val autonomy: AutonomyComposition,
        val gate: ControlledAutonomyDeliberationGate,
        val deliberation: AutonomyDeliberationComposition,
        val planning: PlanningComposition,
        val reasoning: ReasoningComposition,
        val decisions: DecisionComposition,
        val orchestration: OrchestrationComposition,
        val authority: CapabilityAuthorityComposition,
        val planningBridge: AutonomyPlanningBridge,
        val reasoningBridge: AutonomyReasoningBridge,
        val decisionBridge: AutonomyDecisionBridge,
        val orchestrationBridge: AutonomyOrchestrationBridge,
        val controlledExecution: ControlledAutonomyExecution,
        val executorCalls: AtomicInteger
    )

    private data class Prepared(
        val autonomy: AutonomyOwnership,
        val deliberation: AutonomyDeliberationOwnership,
        val planning: pro.liliya.core.planning.PlanningOwnership,
        val reasoning: pro.liliya.core.reasoning.ReasoningOwnership,
        val decision: pro.liliya.core.decision.DecisionOwnership,
        val orchestration: pro.liliya.core.orchestration.OrchestrationOwnership
    )

    private fun fixture(withGrant: Boolean = true): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "autonomy-execution-${sequence.incrementAndGet()}" }
        )
        val autonomy = AutonomyComposition(foundation)
        val gate = ControlledAutonomyDeliberationGate(foundation, autonomy)
        val deliberation = AutonomyDeliberationComposition(foundation)
        val planning = PlanningComposition(foundation)
        val reasoning = ReasoningComposition(foundation)
        val decisions = DecisionComposition(foundation)
        val orchestration = OrchestrationComposition(foundation)
        val authority = CapabilityAuthorityComposition(foundation)
        val preflight = AutonomyDeliberationPreflight(foundation, deliberation, gate)
        val planningBridge = AutonomyPlanningBridge(foundation, preflight, planning)
        val reasoningBridge = AutonomyReasoningBridge(foundation, preflight, planning, reasoning)
        val decisionBridge = AutonomyDecisionBridge(foundation, preflight, planning, reasoning, decisions)
        val orchestrationBridge = AutonomyOrchestrationBridge(
            foundation, preflight, planning, reasoning, decisions, orchestration
        )

        assertIs<CapabilityOwnershipResult.Registered>(
            authority.registerCapability(
                CapabilityDescriptor(capability, CapabilityProviderId("device-provider"))
            )
        )
        if (withGrant) {
            assertIs<DirectAuthorityGrantOwnershipResult.Registered>(
                authority.registerDirectGrant(
                    DirectAuthorityGrant(principal, capability, scope)
                )
            )
        }

        val executorCalls = AtomicInteger(0)
        val orchestrationPreflight = OrchestrationExecutionPreflight(
            foundation = foundation,
            orchestration = orchestration,
            decisions = decisions,
            actionPolicies = mapOf(action to OrchestrationActionPolicy(capability, scope))
        )
        val authorization = ControlledOrchestrationAuthorization(
            foundation = foundation,
            preflight = orchestrationPreflight,
            capabilityAuthority = authority,
            executionActionCapabilities = mapOf(action to capability)
        )
        val execution = ExecutionComposition(
            foundation = foundation,
            capabilityAuthority = authority,
            executor = ExecutionExecutor { _, _ ->
                executorCalls.incrementAndGet()
                ExecutionResult.Succeeded
            },
            actionCapabilities = mapOf(action to capability)
        )
        val controlledOrchestration = ControlledOrchestrationExecution(authorization, execution)
        val controlledExecution = ControlledAutonomyExecution(
            preflight = preflight,
            planning = planning,
            reasoning = reasoning,
            decisions = decisions,
            orchestration = orchestration,
            controlledOrchestration = controlledOrchestration
        )

        return Fixture(
            logs, autonomy, gate, deliberation, planning, reasoning, decisions, orchestration,
            authority, planningBridge, reasoningBridge, decisionBridge, orchestrationBridge,
            controlledExecution, executorCalls
        )
    }

    private fun prepare(f: Fixture): Prepared {
        val autonomy = assertIs<AutonomyInstallResult.Installed>(
            f.autonomy.install(
                AutonomyProposal(
                    AutonomyProposalId("autonomy-1"),
                    AutonomyOrigin.Declared(AutonomySourceId("goal-context")),
                    "private autonomy objective",
                    "private autonomy trigger",
                    AutonomyPriority.NORMAL,
                    AutonomyBudget(2),
                    Instant.parse("2026-08-29T16:00:00Z")
                )
            )
        ).ownership
        val claim = assertIs<AutonomyDeliberationAttemptResult.Claimed>(
            f.gate.claimAttempt(autonomy.proposal.id, autonomy.generation)
        )
        val deliberation = assertIs<AutonomyDeliberationInstallResult.Installed>(
            f.deliberation.install(
                AutonomyDeliberationRequest(
                    AutonomyDeliberationRequestId("request-1"),
                    AutonomyAttemptReference(
                        claim.evidence.proposal.id,
                        claim.evidence.generation,
                        claim.evidence.attemptNumber
                    ),
                    "private deliberation objective",
                    Instant.parse("2026-08-29T16:01:00Z")
                )
            )
        ).ownership
        val planning = assertIs<AutonomyPlanningBridgeResult.Installed>(
            f.planningBridge.install(
                AutonomyPlanningBridgeRequest(
                    deliberation.request.id,
                    deliberation.generation,
                    PlanningProposalId("plan-1"),
                    "private planning goal",
                    listOf(PlanningStep(PlanningStepId("step-1"), "private planning step")),
                    Instant.parse("2026-08-29T16:02:00Z")
                )
            )
        ).planning
        val reasoning = assertIs<AutonomyReasoningBridgeResult.Installed>(
            f.reasoningBridge.install(
                AutonomyReasoningBridgeRequest(
                    deliberation.request.id,
                    deliberation.generation,
                    planning.proposal.id,
                    planning.generation,
                    ReasoningArtifactId("reason-1"),
                    listOf(ReasoningPremise(ReasoningPremiseId("premise-1"), "private premise")),
                    "private analysis",
                    "private conclusion",
                    Instant.parse("2026-08-29T16:03:00Z")
                )
            )
        ).reasoning
        val decision = assertIs<AutonomyDecisionBridgeResult.Installed>(
            f.decisionBridge.install(
                AutonomyDecisionBridgeRequest(
                    deliberation.request.id,
                    deliberation.generation,
                    planning.proposal.id,
                    planning.generation,
                    reasoning.artifact.id,
                    reasoning.generation,
                    DecisionId("decision-1"),
                    listOf(
                        DecisionOption(DecisionOptionId("option-a"), "private option a"),
                        DecisionOption(DecisionOptionId("option-b"), "private option b")
                    ),
                    DecisionOptionId("option-a"),
                    "private decision rationale",
                    Instant.parse("2026-08-29T16:04:00Z")
                )
            )
        ).decision
        val orchestration = assertIs<AutonomyOrchestrationBridgeResult.Installed>(
            f.orchestrationBridge.install(
                AutonomyOrchestrationBridgeRequest(
                    deliberation.request.id,
                    deliberation.generation,
                    planning.proposal.id,
                    planning.generation,
                    reasoning.artifact.id,
                    reasoning.generation,
                    decision.decision.id,
                    decision.generation,
                    OrchestrationIntentId("intent-1"),
                    "private orchestration description",
                    Instant.parse("2026-08-29T16:05:00Z")
                )
            )
        ).orchestration
        return Prepared(autonomy, deliberation, planning, reasoning, decision, orchestration)
    }

    private fun request(p: Prepared) = ControlledAutonomyExecutionRequest(
        deliberationRequestId = p.deliberation.request.id,
        deliberationGeneration = p.deliberation.generation,
        planningProposalId = p.planning.proposal.id,
        planningGeneration = p.planning.generation,
        reasoningArtifactId = p.reasoning.artifact.id,
        reasoningGeneration = p.reasoning.generation,
        decisionId = p.decision.decision.id,
        decisionGeneration = p.decision.generation,
        orchestrationIntentId = p.orchestration.intent.id,
        orchestrationGeneration = p.orchestration.generation,
        principal = principal,
        actionId = action
    )

    @Test
    fun exact_live_full_chain_reaches_executor_once_through_frozen_authority_boundaries() {
        val f = fixture()
        val prepared = prepare(f)

        val result = f.controlledExecution.execute(request(prepared))

        assertIs<ControlledAutonomyExecutionResult.Succeeded>(result)
        assertEquals(1, f.executorCalls.get())
        assertEquals(2, f.logs.snapshot().count { it.marker == "AUTHORITY_GRANTED" })
        assertTrue(f.logs.snapshot().any { it.marker == "EXECUTION_SUCCEEDED" })
    }

    @Test
    fun cancellation_after_orchestration_creation_causes_zero_executor_calls_and_zero_downstream_authority() {
        val f = fixture()
        val prepared = prepare(f)
        assertIs<AutonomyDeliberationCancellationResult.Cancelled>(
            f.gate.cancel(prepared.autonomy.proposal.id, prepared.autonomy.generation)
        )
        val authorityBefore = f.logs.snapshot().count {
            it.marker == "AUTHORITY_GRANTED" || it.marker == "AUTHORITY_DENIED"
        }

        val result = f.controlledExecution.execute(request(prepared))

        assertIs<ControlledAutonomyExecutionResult.Rejected>(result)
        assertEquals(0, f.executorCalls.get())
        val authorityAfter = f.logs.snapshot().count {
            it.marker == "AUTHORITY_GRANTED" || it.marker == "AUTHORITY_DENIED"
        }
        assertEquals(authorityBefore, authorityAfter)
        assertFalse(f.logs.snapshot().any { it.marker == "EXECUTION_SUCCEEDED" })
    }

    @Test
    fun stale_autonomy_replacement_after_orchestration_creation_causes_zero_executor_calls() {
        val f = fixture()
        val prepared = prepare(f)
        assertTrue(prepared.autonomy.remove())
        assertIs<AutonomyInstallResult.Installed>(
            f.autonomy.install(
                AutonomyProposal(
                    AutonomyProposalId("autonomy-1"),
                    AutonomyOrigin.Declared(AutonomySourceId("goal-context")),
                    "replacement private objective",
                    "replacement private trigger",
                    AutonomyPriority.NORMAL,
                    AutonomyBudget(2),
                    Instant.parse("2026-08-29T16:06:00Z")
                )
            )
        )

        assertIs<ControlledAutonomyExecutionResult.Rejected>(
            f.controlledExecution.execute(request(prepared))
        )
        assertEquals(0, f.executorCalls.get())
    }

    @Test
    fun stale_orchestration_generation_causes_zero_executor_calls() {
        val f = fixture()
        val prepared = prepare(f)
        assertTrue(prepared.orchestration.remove())

        assertIs<ControlledAutonomyExecutionResult.Rejected>(
            f.controlledExecution.execute(request(prepared))
        )
        assertEquals(0, f.executorCalls.get())
    }

    @Test
    fun denied_authority_after_fresh_autonomy_guard_causes_zero_executor_calls() {
        val f = fixture(withGrant = false)
        val prepared = prepare(f)

        val result = f.controlledExecution.execute(request(prepared))

        assertIs<ControlledAutonomyExecutionResult.Rejected>(result)
        assertEquals(0, f.executorCalls.get())
        assertTrue(f.logs.snapshot().any { it.marker == "AUTHORITY_DENIED" })
    }

    @Test
    fun private_cognitive_payload_stays_out_of_full_execution_observability() {
        val f = fixture()
        val prepared = prepare(f)
        f.controlledExecution.execute(request(prepared))

        val secrets = setOf(
            "private autonomy objective", "private autonomy trigger", "private deliberation objective",
            "private planning goal", "private planning step", "private premise", "private analysis",
            "private conclusion", "private option a", "private option b", "private decision rationale",
            "private orchestration description"
        )
        assertFalse(f.logs.snapshot().any { event ->
            event.message in secrets || event.metadata.values.any { it in secrets }
        })
    }
}
