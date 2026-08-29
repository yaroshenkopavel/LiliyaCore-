package pro.liliya.core.orchestration

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.authority.CapabilityOwnershipResult
import pro.liliya.core.authority.DirectAuthorityGrant
import pro.liliya.core.authority.DirectAuthorityGrantOwnership
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

class ControlledOrchestrationReadinessContractTest {
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
    private val scope = AuthorityScope("device.settings")
    private val principal = AuthorityPrincipal("liliya")

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "orchestration-control-ready-${sequence.incrementAndGet()}" }
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

    private fun decision(
        rationale: String = "private decision rationale",
        optionDescription: String = "private option description"
    ) = DecisionRecord(
        id = DecisionId("decision-1"),
        inputs = listOf(
            DecisionInputReference.Reasoning(
                ReasoningArtifactId("reason-1"),
                ReasoningGeneration(7)
            )
        ),
        options = listOf(
            DecisionOption(DecisionOptionId("option-a"), optionDescription),
            DecisionOption(DecisionOptionId("option-b"), "private alternative")
        ),
        selectedOptionId = DecisionOptionId("option-a"),
        rationale = rationale,
        createdAt = Instant.parse("2026-08-29T14:00:00Z")
    )

    private fun installDecision(f: Fixture, value: DecisionRecord = decision()) =
        assertIs<DecisionInstallResult.Installed>(f.decisions.install(value)).ownership

    private fun installIntent(
        f: Fixture,
        decisionGeneration: DecisionGeneration,
        description: String = "private orchestration description"
    ) = assertIs<OrchestrationInstallResult.Installed>(
        f.orchestration.install(
            OrchestrationIntent(
                id = OrchestrationIntentId("intent-1"),
                decision = OrchestrationDecisionReference(
                    decisionId = DecisionId("decision-1"),
                    generation = decisionGeneration,
                    selectedOptionId = DecisionOptionId("option-a")
                ),
                description = description,
                createdAt = Instant.parse("2026-08-29T14:01:00Z")
            )
        )
    ).ownership

    private fun grant(f: Fixture): DirectAuthorityGrantOwnership {
        assertIs<CapabilityOwnershipResult.Registered>(
            f.authority.registerCapability(
                CapabilityDescriptor(capability, CapabilityProviderId("device-provider"))
            )
        )
        return assertIs<DirectAuthorityGrantOwnershipResult.Registered>(
            f.authority.registerDirectGrant(
                DirectAuthorityGrant(principal, capability, scope)
            )
        ).ownership
    }

    private fun authorization(f: Fixture): ControlledOrchestrationAuthorization {
        val preflight = OrchestrationExecutionPreflight(
            foundation = f.foundation,
            orchestration = f.orchestration,
            decisions = f.decisions,
            actionPolicies = mapOf(action to OrchestrationActionPolicy(capability, scope))
        )
        return ControlledOrchestrationAuthorization(
            foundation = f.foundation,
            preflight = preflight,
            capabilityAuthority = f.authority,
            executionActionCapabilities = mapOf(action to capability)
        )
    }

    private fun execution(
        f: Fixture,
        authorization: ControlledOrchestrationAuthorization = authorization(f)
    ) = ControlledOrchestrationExecution(
        authorization = authorization,
        execution = ExecutionComposition(
            foundation = f.foundation,
            capabilityAuthority = f.authority,
            executor = ExecutionExecutor { _, _ ->
                f.executorCalls.incrementAndGet()
                ExecutionResult.Succeeded
            },
            actionCapabilities = mapOf(action to capability)
        )
    )

    private fun request(generation: OrchestrationGeneration) = OrchestrationExecutionPreflightRequest(
        intentId = OrchestrationIntentId("intent-1"),
        generation = generation,
        principal = principal,
        actionId = action
    )

    @Test
    fun prior_authorization_evidence_is_not_reusable_after_grant_revocation() {
        val f = fixture()
        val decisionOwnership = installDecision(f)
        val intentOwnership = installIntent(f, decisionOwnership.generation)
        val grantOwnership = grant(f)
        val auth = authorization(f)

        assertIs<OrchestrationAuthorizationResult.Authorized>(
            auth.authorize(request(intentOwnership.generation))
        )
        assertTrue(grantOwnership.revoke())

        val result = execution(f, auth).execute(request(intentOwnership.generation))

        assertIs<ControlledOrchestrationExecutionResult.Rejected>(result)
        assertEquals(0, f.executorCalls.get())
        assertTrue(f.logs.snapshot().any { it.marker == "AUTHORITY_DENIED" })
    }

    @Test
    fun prior_authorization_evidence_is_not_reusable_after_decision_replacement() {
        val f = fixture()
        val decisionOwnership = installDecision(f)
        val intentOwnership = installIntent(f, decisionOwnership.generation)
        grant(f)
        val auth = authorization(f)

        assertIs<OrchestrationAuthorizationResult.Authorized>(
            auth.authorize(request(intentOwnership.generation))
        )
        assertTrue(decisionOwnership.remove())
        installDecision(f, decision(rationale = "replacement private rationale"))

        val result = execution(f, auth).execute(request(intentOwnership.generation))

        assertIs<ControlledOrchestrationExecutionResult.Rejected>(result)
        assertEquals(0, f.executorCalls.get())
    }

    @Test
    fun full_controlled_path_keeps_private_cognitive_payload_out_of_observability() {
        val f = fixture()
        val secretRationale = "never-observe-full-path-rationale"
        val secretOption = "never-observe-full-path-option"
        val secretIntent = "never-observe-full-path-intent"
        val decisionOwnership = installDecision(
            f,
            decision(rationale = secretRationale, optionDescription = secretOption)
        )
        val intentOwnership = installIntent(
            f,
            decisionGeneration = decisionOwnership.generation,
            description = secretIntent
        )
        grant(f)

        assertIs<ControlledOrchestrationExecutionResult.Succeeded>(
            execution(f).execute(request(intentOwnership.generation))
        )

        val secrets = setOf(secretRationale, secretOption, secretIntent)
        assertFalse(f.logs.snapshot().any { event ->
            event.message in secrets ||
                event.metadata.values.any { value -> value in secrets }
        })
        assertEquals(1, f.executorCalls.get())
    }
}
