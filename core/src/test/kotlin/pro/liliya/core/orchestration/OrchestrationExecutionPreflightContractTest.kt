package pro.liliya.core.orchestration

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.AuthorityScope
import pro.liliya.core.authority.CapabilityId
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

class OrchestrationExecutionPreflightContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val decisions: DecisionComposition,
        val orchestration: OrchestrationComposition
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "orchestration-preflight-${sequence.incrementAndGet()}" }
        )
        return Fixture(
            logs = logs,
            foundation = foundation,
            decisions = DecisionComposition(foundation),
            orchestration = OrchestrationComposition(foundation)
        )
    }

    private fun decision(
        selectedOptionId: String = "option-a",
        rationale: String = "private decision rationale",
        optionDescription: String = "private decision option"
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
        selectedOptionId = DecisionOptionId(selectedOptionId),
        rationale = rationale,
        createdAt = Instant.parse("2026-08-29T14:00:00Z")
    )

    private fun installDecision(f: Fixture, value: DecisionRecord = decision()) =
        assertIs<DecisionInstallResult.Installed>(f.decisions.install(value)).ownership

    private fun installIntent(
        f: Fixture,
        decisionGeneration: DecisionGeneration,
        selectedOptionId: String = "option-a",
        description: String = "private orchestration description"
    ) = assertIs<OrchestrationInstallResult.Installed>(
        f.orchestration.install(
            OrchestrationIntent(
                id = OrchestrationIntentId("intent-1"),
                decision = OrchestrationDecisionReference(
                    decisionId = DecisionId("decision-1"),
                    generation = decisionGeneration,
                    selectedOptionId = DecisionOptionId(selectedOptionId)
                ),
                description = description,
                createdAt = Instant.parse("2026-08-29T14:01:00Z")
            )
        )
    ).ownership

    private val action = ExecutionActionId("device.open.settings")
    private val capability = CapabilityId("device.settings.open")
    private val scope = AuthorityScope("device.settings")
    private val principal = AuthorityPrincipal("liliya")

    private fun preflight(f: Fixture) = OrchestrationExecutionPreflight(
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

    private fun request(generation: OrchestrationGeneration) = OrchestrationExecutionPreflightRequest(
        intentId = OrchestrationIntentId("intent-1"),
        generation = generation,
        principal = principal,
        actionId = action
    )

    @Test
    fun exact_live_provenance_returns_evidence_only() {
        val f = fixture()
        val decisionOwnership = installDecision(f)
        val intentOwnership = installIntent(f, decisionOwnership.generation)

        val ready = assertIs<OrchestrationExecutionPreflightResult.Ready>(
            preflight(f).check(request(intentOwnership.generation))
        )

        assertEquals(intentOwnership.generation, ready.evidence.request.generation)
        assertEquals(decisionOwnership.generation, ready.evidence.decision.generation)
        assertEquals(DecisionOptionId("option-a"), ready.evidence.decision.selectedOptionId)
        assertEquals(capability, ready.evidence.requiredCapability)
        assertEquals(scope, ready.evidence.requiredScope)
        assertEquals(principal, ready.evidence.request.principal)
        assertEquals(action, ready.evidence.request.actionId)
    }

    @Test
    fun stale_orchestration_generation_rejects_fail_closed() {
        val f = fixture()
        val decisionOwnership = installDecision(f)
        val stale = installIntent(f, decisionOwnership.generation)
        assertTrue(stale.remove())
        val current = installIntent(f, decisionOwnership.generation)

        val result = preflight(f).check(request(stale.generation))

        assertIs<OrchestrationExecutionPreflightResult.Rejected>(result)
        assertTrue(f.orchestration.contains(current.intent.id))
    }

    @Test
    fun missing_orchestration_intent_rejects_fail_closed() {
        val f = fixture()
        val result = preflight(f).check(request(OrchestrationGeneration(1)))
        assertIs<OrchestrationExecutionPreflightResult.Rejected>(result)
    }

    @Test
    fun stale_decision_generation_rejects_fail_closed() {
        val f = fixture()
        val staleDecision = installDecision(f)
        val intentOwnership = installIntent(f, staleDecision.generation)
        assertTrue(staleDecision.remove())
        installDecision(f, decision(rationale = "replacement private rationale"))

        val result = preflight(f).check(request(intentOwnership.generation))

        assertIs<OrchestrationExecutionPreflightResult.Rejected>(result)
    }

    @Test
    fun missing_decision_rejects_fail_closed() {
        val f = fixture()
        val intentOwnership = installIntent(f, DecisionGeneration(3))
        val result = preflight(f).check(request(intentOwnership.generation))
        assertIs<OrchestrationExecutionPreflightResult.Rejected>(result)
    }

    @Test
    fun selected_option_mismatch_rejects_fail_closed() {
        val f = fixture()
        val decisionOwnership = installDecision(f, decision(selectedOptionId = "option-a"))
        val intentOwnership = installIntent(
            f,
            decisionGeneration = decisionOwnership.generation,
            selectedOptionId = "option-b"
        )

        val result = preflight(f).check(request(intentOwnership.generation))

        assertIs<OrchestrationExecutionPreflightResult.Rejected>(result)
    }

    @Test
    fun unknown_action_rejects_before_any_authorization_or_execution_boundary() {
        val f = fixture()
        val decisionOwnership = installDecision(f)
        val intentOwnership = installIntent(f, decisionOwnership.generation)
        val unknown = request(intentOwnership.generation).copy(
            actionId = ExecutionActionId("unknown.action")
        )

        val result = preflight(f).check(unknown)

        assertIs<OrchestrationExecutionPreflightResult.Rejected>(result)
    }

    @Test
    fun capability_and_scope_come_only_from_trusted_action_policy() {
        val f = fixture()
        val decisionOwnership = installDecision(f)
        val intentOwnership = installIntent(f, decisionOwnership.generation)

        val ready = assertIs<OrchestrationExecutionPreflightResult.Ready>(
            preflight(f).check(request(intentOwnership.generation))
        )

        assertEquals(capability, ready.evidence.requiredCapability)
        assertEquals(scope, ready.evidence.requiredScope)
    }

    @Test
    fun private_decision_and_orchestration_payloads_are_absent_from_preflight_observability() {
        val f = fixture()
        val secretRationale = "never-observe-decision-rationale-at-preflight"
        val secretOption = "never-observe-decision-option-at-preflight"
        val secretIntent = "never-observe-orchestration-description-at-preflight"
        val decisionOwnership = installDecision(
            f,
            decision(rationale = secretRationale, optionDescription = secretOption)
        )
        val intentOwnership = installIntent(
            f,
            decisionGeneration = decisionOwnership.generation,
            description = secretIntent
        )

        assertIs<OrchestrationExecutionPreflightResult.Ready>(
            preflight(f).check(request(intentOwnership.generation))
        )

        val secrets = setOf(secretRationale, secretOption, secretIntent)
        assertFalse(f.logs.snapshot().any { event ->
            event.metadata.values.any { it in secrets } || event.message in secrets
        })
    }
}
