package pro.liliya.core.orchestration

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
import pro.liliya.core.decision.DecisionGeneration
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.decision.DecisionInputReference
import pro.liliya.core.decision.DecisionInstallResult
import pro.liliya.core.decision.DecisionOption
import pro.liliya.core.decision.DecisionOptionId
import pro.liliya.core.decision.DecisionRecord
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
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningGeneration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ControlledOrchestrationExecutionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val decisions: DecisionComposition,
        val orchestration: OrchestrationComposition,
        val authority: CapabilityAuthorityComposition,
        val executorCalls: AtomicInteger
    )

    private val action = ExecutionActionId("device.open.settings")
    private val capability = CapabilityId("device.settings.open")
    private val otherCapability = CapabilityId("device.settings.other")
    private val scope = AuthorityScope("device.settings")
    private val principal = AuthorityPrincipal("liliya")

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "orchestration-execution-${sequence.incrementAndGet()}" }
        )
        return Fixture(
            logs = logs,
            foundation = foundation,
            decisions = DecisionComposition(foundation),
            orchestration = OrchestrationComposition(foundation),
            authority = CapabilityAuthorityComposition(foundation),
            executorCalls = AtomicInteger(0)
        )
    }

    private fun decision() = DecisionRecord(
        id = DecisionId("decision-1"),
        inputs = listOf(
            DecisionInputReference.Reasoning(
                ReasoningArtifactId("reason-1"),
                ReasoningGeneration(7)
            )
        ),
        options = listOf(
            DecisionOption(DecisionOptionId("option-a"), "private decision option"),
            DecisionOption(DecisionOptionId("option-b"), "private alternative")
        ),
        selectedOptionId = DecisionOptionId("option-a"),
        rationale = "private decision rationale",
        createdAt = Instant.parse("2026-08-29T14:00:00Z")
    )

    private fun installDecision(f: Fixture) =
        assertIs<DecisionInstallResult.Installed>(f.decisions.install(decision())).ownership

    private fun installIntent(
        f: Fixture,
        decisionGeneration: DecisionGeneration
    ) = assertIs<OrchestrationInstallResult.Installed>(
        f.orchestration.install(
            OrchestrationIntent(
                id = OrchestrationIntentId("intent-1"),
                decision = OrchestrationDecisionReference(
                    decisionId = DecisionId("decision-1"),
                    generation = decisionGeneration,
                    selectedOptionId = DecisionOptionId("option-a")
                ),
                description = "private orchestration description",
                createdAt = Instant.parse("2026-08-29T14:01:00Z")
            )
        )
    ).ownership

    private fun grant(f: Fixture) {
        assertIs<CapabilityOwnershipResult.Registered>(
            f.authority.registerCapability(
                CapabilityDescriptor(capability, CapabilityProviderId("device-provider"))
            )
        )
        assertIs<DirectAuthorityGrantOwnershipResult.Registered>(
            f.authority.registerDirectGrant(
                DirectAuthorityGrant(
                    principal = principal,
                    capability = capability,
                    scope = scope
                )
            )
        )
    }

    private fun controlledExecution(
        f: Fixture,
        authorizationMapping: Map<ExecutionActionId, CapabilityId> = mapOf(action to capability),
        executionMapping: Map<ExecutionActionId, CapabilityId> = mapOf(action to capability),
        executorResult: ExecutionResult = ExecutionResult.Succeeded
    ): ControlledOrchestrationExecution {
        val preflight = OrchestrationExecutionPreflight(
            foundation = f.foundation,
            orchestration = f.orchestration,
            decisions = f.decisions,
            actionPolicies = mapOf(
                action to OrchestrationActionPolicy(
                    capability = capability,
                    scope = scope
                )
            )
        )
        val authorization = ControlledOrchestrationAuthorization(
            foundation = f.foundation,
            preflight = preflight,
            capabilityAuthority = f.authority,
            executionActionCapabilities = authorizationMapping
        )
        val execution = ExecutionComposition(
            foundation = f.foundation,
            capabilityAuthority = f.authority,
            executor = ExecutionExecutor { _, _ ->
                f.executorCalls.incrementAndGet()
                executorResult
            },
            actionCapabilities = executionMapping
        )
        return ControlledOrchestrationExecution(
            authorization = authorization,
            execution = execution
        )
    }

    private fun request(generation: OrchestrationGeneration) = OrchestrationExecutionPreflightRequest(
        intentId = OrchestrationIntentId("intent-1"),
        generation = generation,
        principal = principal,
        actionId = action
    )

    @Test
    fun exact_live_authorized_request_reaches_executor_once_with_fresh_execution_authority() {
        val f = fixture()
        val decisionOwnership = installDecision(f)
        val intentOwnership = installIntent(f, decisionOwnership.generation)
        grant(f)

        val result = controlledExecution(f).execute(request(intentOwnership.generation))

        assertIs<ControlledOrchestrationExecutionResult.Succeeded>(result)
        assertEquals(1, f.executorCalls.get())
        assertEquals(2, f.logs.snapshot().count { it.marker == "AUTHORITY_GRANTED" })
        assertTrue(f.logs.snapshot().any { it.marker == "EXECUTION_SUCCEEDED" })
    }

    @Test
    fun denied_authority_never_reaches_executor() {
        val f = fixture()
        val decisionOwnership = installDecision(f)
        val intentOwnership = installIntent(f, decisionOwnership.generation)
        assertIs<CapabilityOwnershipResult.Registered>(
            f.authority.registerCapability(
                CapabilityDescriptor(capability, CapabilityProviderId("device-provider"))
            )
        )

        val result = controlledExecution(f).execute(request(intentOwnership.generation))

        assertIs<ControlledOrchestrationExecutionResult.Rejected>(result)
        assertEquals(0, f.executorCalls.get())
        assertTrue(f.logs.snapshot().any { it.marker == "AUTHORITY_DENIED" })
        assertFalse(f.logs.snapshot().any { it.marker == "EXECUTION_SUCCEEDED" })
    }

    @Test
    fun stale_orchestration_generation_never_reaches_authority_or_executor() {
        val f = fixture()
        val decisionOwnership = installDecision(f)
        val stale = installIntent(f, decisionOwnership.generation)
        grant(f)
        assertTrue(stale.remove())
        installIntent(f, decisionOwnership.generation)

        val result = controlledExecution(f).execute(request(stale.generation))

        assertIs<ControlledOrchestrationExecutionResult.Rejected>(result)
        assertEquals(0, f.executorCalls.get())
        assertFalse(f.logs.snapshot().any { it.marker == "AUTHORITY_GRANTED" || it.marker == "AUTHORITY_DENIED" })
    }

    @Test
    fun authorization_mapping_mismatch_never_reaches_authority_or_executor() {
        val f = fixture()
        val decisionOwnership = installDecision(f)
        val intentOwnership = installIntent(f, decisionOwnership.generation)
        grant(f)

        val result = controlledExecution(
            f,
            authorizationMapping = mapOf(action to otherCapability)
        ).execute(request(intentOwnership.generation))

        assertIs<ControlledOrchestrationExecutionResult.Rejected>(result)
        assertEquals(0, f.executorCalls.get())
        assertFalse(f.logs.snapshot().any { it.marker == "AUTHORITY_GRANTED" || it.marker == "AUTHORITY_DENIED" })
    }

    @Test
    fun execution_mapping_drift_after_authorization_never_reaches_executor() {
        val f = fixture()
        val decisionOwnership = installDecision(f)
        val intentOwnership = installIntent(f, decisionOwnership.generation)
        grant(f)

        val result = controlledExecution(
            f,
            executionMapping = mapOf(action to otherCapability)
        ).execute(request(intentOwnership.generation))

        assertIs<ControlledOrchestrationExecutionResult.Rejected>(result)
        assertEquals(0, f.executorCalls.get())
        assertEquals(1, f.logs.snapshot().count { it.marker == "AUTHORITY_GRANTED" })
        assertTrue(f.logs.snapshot().any { it.marker == "EXECUTION_REJECTED" })
    }

    @Test
    fun executor_failure_isolated_as_controlled_failure() {
        val f = fixture()
        val decisionOwnership = installDecision(f)
        val intentOwnership = installIntent(f, decisionOwnership.generation)
        grant(f)

        val result = controlledExecution(
            f,
            executorResult = ExecutionResult.Failed("device adapter failed")
        ).execute(request(intentOwnership.generation))

        val failed = assertIs<ControlledOrchestrationExecutionResult.Failed>(result)
        assertEquals("device adapter failed", failed.reason)
        assertEquals(1, f.executorCalls.get())
        assertTrue(f.logs.snapshot().any { it.marker == "EXECUTION_FAILED" })
    }
}
