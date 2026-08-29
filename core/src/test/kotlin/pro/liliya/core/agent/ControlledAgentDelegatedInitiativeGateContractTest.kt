package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.autonomy.AutonomyAttemptReference
import pro.liliya.core.autonomy.AutonomyBudget
import pro.liliya.core.autonomy.AutonomyComposition
import pro.liliya.core.autonomy.AutonomyDeliberationAttemptValidationResult
import pro.liliya.core.autonomy.AutonomyPriority
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.autonomy.ControlledAutonomyDeliberationGate
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class ControlledAgentDelegatedInitiativeGateContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val agents: AgentComposition,
        val lifecycle: ControlledAgentLifecycle,
        val delegations: AgentDelegationComposition,
        val autonomy: AutonomyComposition,
        val bindings: AgentDelegatedWorkBindingComposition,
        val autonomyGate: ControlledAutonomyDeliberationGate,
        val agentGate: ControlledAgentInitiativeGate,
        val preflight: ControlledAgentDelegationPreflight,
        val delegatedInitiative: ControlledAgentDelegatedInitiative,
        val gate: ControlledAgentDelegatedInitiativeGate
    )

    private data class Prepared(
        val parent: AgentOwnership,
        val parentLifecycle: AgentLifecycleOwnership,
        val child: AgentOwnership,
        val childLifecycle: AgentLifecycleOwnership,
        val delegation: AgentDelegationOwnership,
        val delegated: AgentDelegatedInitiativeOwnership
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "delegated-attempt-${sequence.incrementAndGet()}" }
        )
        val agents = AgentComposition(foundation)
        val lifecycle = ControlledAgentLifecycle(foundation, agents)
        val delegations = AgentDelegationComposition(foundation)
        val autonomy = AutonomyComposition(foundation)
        val bindings = AgentDelegatedWorkBindingComposition(foundation)
        val autonomyGate = ControlledAutonomyDeliberationGate(foundation, autonomy)
        val initiative = ControlledAgentInitiative(foundation, agents, lifecycle, autonomy)
        val agentGate = ControlledAgentInitiativeGate(
            foundation,
            agents,
            lifecycle,
            autonomy,
            autonomyGate
        )
        val preflight = ControlledAgentDelegationPreflight(
            foundation,
            delegations,
            agents,
            lifecycle
        )
        val delegatedInitiative = ControlledAgentDelegatedInitiative(
            foundation,
            preflight,
            initiative,
            bindings
        )
        return Fixture(
            logs,
            foundation,
            agents,
            lifecycle,
            delegations,
            autonomy,
            bindings,
            autonomyGate,
            agentGate,
            preflight,
            delegatedInitiative,
            ControlledAgentDelegatedInitiativeGate(
                foundation,
                bindings,
                preflight,
                agentGate,
                autonomyGate
            )
        )
    }

    private fun installAgent(f: Fixture, id: String): AgentOwnership =
        assertIs<AgentInstallResult.Installed>(
            f.agents.install(
                AgentRecord(
                    id = AgentId(id),
                    origin = AgentOrigin.Declared(AgentSourceId("declared-$id")),
                    role = "private $id role",
                    purpose = "private $id purpose",
                    createdAt = Instant.parse("2026-08-29T22:00:00Z")
                )
            )
        ).ownership

    private fun activate(f: Fixture, agent: AgentOwnership): AgentLifecycleOwnership =
        assertIs<AgentLifecycleActivationResult.Activated>(
            f.lifecycle.activate(agent.agent.id, agent.generation)
        ).ownership

    private fun prepare(f: Fixture): Prepared {
        val parent = installAgent(f, "parent")
        val child = installAgent(f, "child")
        val parentLifecycle = activate(f, parent)
        val childLifecycle = activate(f, child)
        val delegation = assertIs<AgentDelegationInstallResult.Installed>(
            f.delegations.install(
                AgentDelegationRecord(
                    id = AgentDelegationId("delegation-attempt"),
                    parent = ExactAgentReference(parent.agent.id, parent.generation),
                    child = ExactAgentReference(child.agent.id, child.generation),
                    purpose = "private delegated attempt purpose",
                    createdAt = Instant.parse("2026-08-29T22:01:00Z")
                )
            )
        ).ownership
        val delegated = assertIs<AgentDelegatedInitiativeResult.Created>(
            f.delegatedInitiative.create(
                AgentDelegatedInitiativeRequest(
                    delegationId = delegation.delegation.id,
                    delegationGeneration = delegation.generation,
                    autonomyProposalId = AutonomyProposalId("delegated-attempt-autonomy"),
                    objective = "private delegated objective",
                    triggerDescription = "private delegated trigger",
                    priority = AutonomyPriority.NORMAL,
                    budget = AutonomyBudget(2),
                    createdAt = Instant.parse("2026-08-29T22:02:00Z")
                )
            )
        ).ownership
        return Prepared(parent, parentLifecycle, child, childLifecycle, delegation, delegated)
    }

    @Test
    fun exact_live_binding_and_active_delegation_claim_one_bounded_attempt() {
        val f = fixture()
        val p = prepare(f)
        val autonomy = p.delegated.receipt.autonomy

        val claimed = assertIs<AgentDelegatedInitiativeAttemptResult.Claimed>(
            f.gate.claimAttempt(autonomy.proposalId, autonomy.generation)
        )

        assertEquals(1, claimed.attempt.attempt.evidence.attemptNumber)
        assertEquals(
            1,
            f.logs.snapshot().count { it.marker == "AUTONOMY_DELIBERATION_ATTEMPT_CLAIMED" }
        )
        assertIs<AutonomyDeliberationAttemptValidationResult.Valid>(
            f.autonomyGate.validateAttempt(
                AutonomyAttemptReference(
                    proposalId = autonomy.proposalId,
                    proposalGeneration = autonomy.generation,
                    attemptNumber = 1
                )
            )
        )
    }

    @Test
    fun parent_cancelled_before_claim_causes_zero_attempt_claims() {
        val f = fixture()
        val p = prepare(f)
        val autonomy = p.delegated.receipt.autonomy
        assertTrue(p.parentLifecycle.cancel())

        assertIs<AgentDelegatedInitiativeAttemptResult.Rejected>(
            f.gate.claimAttempt(autonomy.proposalId, autonomy.generation)
        )
        assertEquals(
            0,
            f.logs.snapshot().count { it.marker == "AUTONOMY_DELIBERATION_ATTEMPT_CLAIMED" }
        )
    }

    @Test
    fun child_stopped_before_claim_causes_zero_attempt_claims() {
        val f = fixture()
        val p = prepare(f)
        val autonomy = p.delegated.receipt.autonomy
        assertTrue(p.childLifecycle.stop())

        assertIs<AgentDelegatedInitiativeAttemptResult.Rejected>(
            f.gate.claimAttempt(autonomy.proposalId, autonomy.generation)
        )
        assertEquals(
            0,
            f.logs.snapshot().count { it.marker == "AUTONOMY_DELIBERATION_ATTEMPT_CLAIMED" }
        )
    }

    @Test
    fun missing_exact_binding_causes_zero_attempt_claims() {
        val f = fixture()
        val p = prepare(f)
        val autonomy = p.delegated.receipt.autonomy
        assertTrue(p.delegated.remove())

        assertIs<AgentDelegatedInitiativeAttemptResult.Rejected>(
            f.gate.claimAttempt(autonomy.proposalId, autonomy.generation)
        )
        assertEquals(
            0,
            f.logs.snapshot().count { it.marker == "AUTONOMY_DELIBERATION_ATTEMPT_CLAIMED" }
        )
    }

    @Test
    fun parent_cancelled_in_preflight_to_claim_window_cancels_exact_autonomy_after_claim() {
        val f = fixture()
        val p = prepare(f)
        val autonomy = p.delegated.receipt.autonomy
        val calls = AtomicInteger(0)
        val racingGate = ControlledAgentDelegatedInitiativeGate(
            foundation = f.foundation,
            bindings = f.bindings,
            preflight = AgentDelegationPreflightChecker { request ->
                val result = f.preflight.check(request)
                if (calls.incrementAndGet() == 1) {
                    assertTrue(p.parentLifecycle.cancel())
                }
                result
            },
            agentGate = f.agentGate,
            autonomyGate = f.autonomyGate,
            testOnly = Unit
        )

        assertIs<AgentDelegatedInitiativeAttemptResult.Rejected>(
            racingGate.claimAttempt(autonomy.proposalId, autonomy.generation)
        )
        assertEquals(
            1,
            f.logs.snapshot().count { it.marker == "AUTONOMY_DELIBERATION_ATTEMPT_CLAIMED" }
        )
        assertIs<AutonomyDeliberationAttemptValidationResult.Rejected>(
            f.autonomyGate.validateAttempt(
                AutonomyAttemptReference(
                    proposalId = autonomy.proposalId,
                    proposalGeneration = autonomy.generation,
                    attemptNumber = 1
                )
            )
        )
        assertTrue(
            f.logs.snapshot().any {
                it.marker == "AGENT_DELEGATED_INITIATIVE_ATTEMPT_COMPENSATED"
            }
        )
    }

    @Test
    fun delegation_purpose_never_enters_attempt_observability() {
        val f = fixture()
        val p = prepare(f)
        val secret = p.delegation.delegation.purpose
        val autonomy = p.delegated.receipt.autonomy

        assertIs<AgentDelegatedInitiativeAttemptResult.Claimed>(
            f.gate.claimAttempt(autonomy.proposalId, autonomy.generation)
        )
        assertTrue(f.logs.snapshot().none { event ->
            event.message == secret || event.metadata.values.any { it == secret }
        })
    }
}
