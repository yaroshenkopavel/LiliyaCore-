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

class ControlledOrchestrationAuthorizationContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val decisions: DecisionComposition,
        val orchestration: OrchestrationComposition,
        val authority: CapabilityAuthorityComposition
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
            correlationIds = CorrelationIdGenerator { "orchestration-auth-${sequence.incrementAndGet()}" }
        )
        return Fixture(
            logs = logs,
            foundation = foundation,
            decisions = DecisionComposition(foundation),
            orchestration = OrchestrationComposition(foundation),
            authority = CapabilityAuthorityComposition(foundation)
        )
    }

    private fun decision(
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

    private fun authorization(
        f: Fixture,
        executionMapping: Map<ExecutionActionId, CapabilityId> = mapOf(action to capability)
    ) = ControlledOrchestrationAuthorization(
        foundation = f.foundation,
        preflight = preflight(f),
        capabilityAuthority = f.authority,
        executionActionCapabilities = executionMapping
    )

    private fun request(generation: OrchestrationGeneration) = OrchestrationExecutionPreflightRequest(
        intentId = OrchestrationIntentId("intent-1"),
        generation = generation,
        principal = principal,
        actionId = action
    )

    private fun grant(f: Fixture) {
        assertIs<CapabilityOwnershipResult.Registered>(
            f.authority.registerCapability(
                CapabilityDescriptor(
                    id = capability,
                    providerId = CapabilityProviderId("device-provider")
                )
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

    @Test
    fun exact_live_preflight_and_fresh_authority_return_authorization_evidence_only() {
        val f = fixture()
        val decisionOwnership = installDecision(f)
        val intentOwnership = installIntent(f, decisionOwnership.generation)
        grant(f)

        val result = assertIs<OrchestrationAuthorizationResult.Authorized>(
            authorization(f).authorize(request(intentOwnership.generation))
        )

        assertEquals(capability, result.evidence.authorityRequest.capability)
        assertEquals(scope, result.evidence.authorityRequest.scope)
        assertEquals(principal, result.evidence.authorityRequest.principal)
        assertEquals(action, result.evidence.preflight.request.actionId)
        assertTrue(result.evidence.authorityRequest.reason.contains("intent-1"))
        assertTrue(f.logs.snapshot().any { it.marker == "AUTHORITY_GRANTED" })
        assertTrue(f.logs.snapshot().any { it.marker == "ORCHESTRATION_AUTHORIZATION_GRANTED" })
    }

    @Test
    fun denied_authority_rejects_and_produces_no_authorized_evidence() {
        val f = fixture()
        val decisionOwnership = installDecision(f)
        val intentOwnership = installIntent(f, decisionOwnership.generation)

        assertIs<CapabilityOwnershipResult.Registered>(
            f.authority.registerCapability(
                CapabilityDescriptor(capability, CapabilityProviderId("device-provider"))
            )
        )

        val result = authorization(f).authorize(request(intentOwnership.generation))

        assertIs<OrchestrationAuthorizationResult.Rejected>(result)
        assertTrue(f.logs.snapshot().any { it.marker == "AUTHORITY_DENIED" })
        assertFalse(f.logs.snapshot().any { it.marker == "ORCHESTRATION_AUTHORIZATION_GRANTED" })
    }

    @Test
    fun execution_mapping_drift_rejects_before_authority() {
        val f = fixture()
        val decisionOwnership = installDecision(f)
        val intentOwnership = installIntent(f, decisionOwnership.generation)
        grant(f)

        val result = authorization(
            f,
            executionMapping = mapOf(action to otherCapability)
        ).authorize(request(intentOwnership.generation))

        assertIs<OrchestrationAuthorizationResult.Rejected>(result)
        assertFalse(f.logs.snapshot().any { it.marker == "AUTHORITY_GRANTED" || it.marker == "AUTHORITY_DENIED" })
        assertTrue(f.logs.snapshot().any { it.marker == "ORCHESTRATION_AUTHORIZATION_REJECTED" })
    }

    @Test
    fun missing_execution_mapping_rejects_before_authority() {
        val f = fixture()
        val decisionOwnership = installDecision(f)
        val intentOwnership = installIntent(f, decisionOwnership.generation)
        grant(f)

        val result = authorization(f, executionMapping = emptyMap())
            .authorize(request(intentOwnership.generation))

        assertIs<OrchestrationAuthorizationResult.Rejected>(result)
        assertFalse(f.logs.snapshot().any { it.marker == "AUTHORITY_GRANTED" || it.marker == "AUTHORITY_DENIED" })
    }

    @Test
    fun stale_preflight_provenance_rejects_before_authority() {
        val f = fixture()
        val decisionOwnership = installDecision(f)
        val staleIntent = installIntent(f, decisionOwnership.generation)
        grant(f)
        assertTrue(staleIntent.remove())
        installIntent(f, decisionOwnership.generation)

        val result = authorization(f).authorize(request(staleIntent.generation))

        assertIs<OrchestrationAuthorizationResult.Rejected>(result)
        assertFalse(f.logs.snapshot().any { it.marker == "AUTHORITY_GRANTED" || it.marker == "AUTHORITY_DENIED" })
    }

    @Test
    fun private_cognitive_payload_is_absent_from_authorization_observability_and_reason() {
        val f = fixture()
        val secretRationale = "never-authority-decision-rationale"
        val secretOption = "never-authority-decision-option"
        val secretIntent = "never-authority-orchestration-description"
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

        val authorized = assertIs<OrchestrationAuthorizationResult.Authorized>(
            authorization(f).authorize(request(intentOwnership.generation))
        )

        val secrets = setOf(secretRationale, secretOption, secretIntent)
        assertFalse(secrets.any { authorized.evidence.authorityRequest.reason.contains(it) })
        assertFalse(f.logs.snapshot().any { event ->
            event.metadata.values.any { it in secrets } || event.message in secrets
        })
    }
}
