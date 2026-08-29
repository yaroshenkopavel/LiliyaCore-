package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.autonomy.AutonomyBudget
import pro.liliya.core.autonomy.AutonomyComposition
import pro.liliya.core.autonomy.AutonomyGeneration
import pro.liliya.core.autonomy.AutonomyOrigin
import pro.liliya.core.autonomy.AutonomyOwnership
import pro.liliya.core.autonomy.AutonomyPriority
import pro.liliya.core.autonomy.AutonomyProposal
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.autonomy.AutonomySourceId
import pro.liliya.core.autonomy.AutonomySourceReference
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class ControlledAgentDelegatedInitiativeContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val agents: AgentComposition,
        val lifecycle: ControlledAgentLifecycle,
        val delegations: AgentDelegationComposition,
        val autonomy: AutonomyComposition,
        val bindings: AgentDelegatedWorkBindingComposition,
        val childInitiative: ControlledAgentInitiative,
        val preflight: ControlledAgentDelegationPreflight,
        val bridge: ControlledAgentDelegatedInitiative
    )

    private data class Prepared(
        val parent: AgentOwnership,
        val parentLifecycle: AgentLifecycleOwnership,
        val child: AgentOwnership,
        val childLifecycle: AgentLifecycleOwnership,
        val delegation: AgentDelegationOwnership
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "delegated-initiative-${sequence.incrementAndGet()}" }
        )
        val agents = AgentComposition(foundation)
        val lifecycle = ControlledAgentLifecycle(foundation, agents)
        val delegations = AgentDelegationComposition(foundation)
        val autonomy = AutonomyComposition(foundation)
        val bindings = AgentDelegatedWorkBindingComposition(foundation)
        val childInitiative = ControlledAgentInitiative(foundation, agents, lifecycle, autonomy)
        val preflight = ControlledAgentDelegationPreflight(
            foundation = foundation,
            delegations = delegations,
            agents = agents,
            lifecycle = lifecycle
        )
        return Fixture(
            logs = logs,
            foundation = foundation,
            agents = agents,
            lifecycle = lifecycle,
            delegations = delegations,
            autonomy = autonomy,
            bindings = bindings,
            childInitiative = childInitiative,
            preflight = preflight,
            bridge = ControlledAgentDelegatedInitiative(
                foundation = foundation,
                preflight = preflight,
                childInitiative = childInitiative,
                bindings = bindings
            )
        )
    }

    private fun installAgent(f: Fixture, id: String): AgentOwnership =
        assertIs<AgentInstallResult.Installed>(
            f.agents.install(
                AgentRecord(
                    id = AgentId(id),
                    origin = AgentOrigin.Declared(AgentSourceId("declared-$id")),
                    role = "private role for $id",
                    purpose = "private purpose for $id",
                    createdAt = Instant.parse("2026-08-29T21:30:00Z")
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
                    id = AgentDelegationId("delegation-1"),
                    parent = ExactAgentReference(parent.agent.id, parent.generation),
                    child = ExactAgentReference(child.agent.id, child.generation),
                    purpose = "private delegation purpose",
                    createdAt = Instant.parse("2026-08-29T21:31:00Z")
                )
            )
        ).ownership
        return Prepared(parent, parentLifecycle, child, childLifecycle, delegation)
    }

    private fun request(p: Prepared) = AgentDelegatedInitiativeRequest(
        delegationId = p.delegation.delegation.id,
        delegationGeneration = p.delegation.generation,
        autonomyProposalId = AutonomyProposalId("delegated-autonomy"),
        objective = "private delegated objective",
        triggerDescription = "private delegated trigger",
        priority = AutonomyPriority.NORMAL,
        budget = AutonomyBudget(2),
        createdAt = Instant.parse("2026-08-29T21:32:00Z")
    )

    private fun readyEvidence(p: Prepared) = AgentDelegationReadyEvidence(
        delegationId = p.delegation.delegation.id,
        delegationGeneration = p.delegation.generation,
        parent = ExactAgentReference(p.parent.agent.id, p.parent.generation),
        child = ExactAgentReference(p.child.agent.id, p.child.generation)
    )

    @Test
    fun exact_live_active_delegation_creates_child_autonomy_and_exact_binding() {
        val f = fixture()
        val p = prepare(f)

        val ownership = assertIs<AgentDelegatedInitiativeResult.Created>(
            f.bridge.create(request(p))
        ).ownership
        val receipt = ownership.receipt
        val binding = f.bindings.find(receipt.autonomy)
        val autonomySnapshot = f.autonomy.inspect(receipt.autonomy.proposalId)

        assertEquals(receipt.delegation, binding?.delegation)
        assertEquals(receipt.child, binding?.child)
        assertEquals(receipt.autonomy, binding?.autonomy)
        assertEquals(receipt.autonomy.generation, autonomySnapshot?.generation)

        val origin = assertIs<AutonomyOrigin.Declared>(autonomySnapshot?.proposal?.origin)
        assertEquals("agent", origin.sourceId.value)
        assertEquals("agent:child@${p.child.generation.value}", origin.sourceReference?.value)
    }

    @Test
    fun composite_ownership_removes_autonomy_then_binding_without_exposing_split_handles() {
        val f = fixture()
        val p = prepare(f)
        val ownership = assertIs<AgentDelegatedInitiativeResult.Created>(
            f.bridge.create(request(p))
        ).ownership
        val receipt = ownership.receipt

        assertTrue(ownership.remove())
        assertFalse(f.autonomy.contains(receipt.autonomy.proposalId))
        assertFalse(f.bindings.contains(receipt.autonomy))
        assertFalse(ownership.remove())
    }

    @Test
    fun invalid_parent_lifecycle_causes_zero_autonomy_and_zero_binding_writes() {
        val f = fixture()
        val p = prepare(f)
        assertTrue(p.parentLifecycle.cancel())

        assertIs<AgentDelegatedInitiativeResult.Rejected>(f.bridge.create(request(p)))
        assertTrue(f.autonomy.snapshot().isEmpty())
        assertTrue(f.bindings.snapshot().isEmpty())
    }

    @Test
    fun invalid_child_lifecycle_causes_zero_autonomy_and_zero_binding_writes() {
        val f = fixture()
        val p = prepare(f)
        assertTrue(p.childLifecycle.stop())

        assertIs<AgentDelegatedInitiativeResult.Rejected>(f.bridge.create(request(p)))
        assertTrue(f.autonomy.snapshot().isEmpty())
        assertTrue(f.bindings.snapshot().isEmpty())
    }

    @Test
    fun post_create_preflight_rejection_rolls_back_new_autonomy_before_returning() {
        val f = fixture()
        val p = prepare(f)
        val calls = AtomicInteger(0)
        val bridge = ControlledAgentDelegatedInitiative(
            foundation = f.foundation,
            preflight = AgentDelegationPreflightChecker {
                if (calls.incrementAndGet() == 1) {
                    AgentDelegationPreflightResult.Ready(readyEvidence(p))
                } else {
                    AgentDelegationPreflightResult.Rejected("parent lifecycle changed")
                }
            },
            childInitiative = AgentChildInitiativeCreator(f.childInitiative::create),
            bindings = AgentDelegatedBindingInstaller(f.bindings::install),
            testOnly = Unit
        )

        assertIs<AgentDelegatedInitiativeResult.Rejected>(bridge.create(request(p)))
        assertTrue(f.autonomy.snapshot().isEmpty())
        assertTrue(f.bindings.snapshot().isEmpty())
        assertTrue(f.logs.snapshot().any { it.marker == "AGENT_DELEGATED_INITIATIVE_COMPENSATED" })
    }

    @Test
    fun binding_rejection_rolls_back_new_autonomy_without_predicting_generation() {
        val f = fixture()
        val p = prepare(f)
        val bridge = ControlledAgentDelegatedInitiative(
            foundation = f.foundation,
            preflight = AgentDelegationPreflightChecker(f.preflight::check),
            childInitiative = AgentChildInitiativeCreator(f.childInitiative::create),
            bindings = AgentDelegatedBindingInstaller {
                AgentDelegatedWorkBindingInstallResult.Rejected("forced exact binding conflict")
            },
            testOnly = Unit
        )

        assertIs<AgentDelegatedInitiativeResult.Rejected>(bridge.create(request(p)))
        assertFalse(f.autonomy.contains(request(p).autonomyProposalId))
        assertTrue(f.bindings.snapshot().isEmpty())
        assertTrue(f.logs.snapshot().any { it.marker == "AGENT_DELEGATED_INITIATIVE_COMPENSATED" })
    }

    @Test
    fun failed_compensation_is_explicit_failure_not_normal_rejection() {
        val f = fixture()
        val p = prepare(f)
        val proposal = AutonomyProposal(
            id = request(p).autonomyProposalId,
            origin = AutonomyOrigin.Declared(
                sourceId = AutonomySourceId("agent"),
                sourceReference = AutonomySourceReference("agent:child@${p.child.generation.value}")
            ),
            objective = request(p).objective,
            triggerDescription = request(p).triggerDescription,
            priority = request(p).priority,
            budget = request(p).budget,
            createdAt = request(p).createdAt
        )
        val unremovable = object : AutonomyOwnership {
            override val proposal: AutonomyProposal = proposal
            override val generation: AutonomyGeneration = AutonomyGeneration(77)
            override fun remove(): Boolean = false
        }
        val bridge = ControlledAgentDelegatedInitiative(
            foundation = f.foundation,
            preflight = AgentDelegationPreflightChecker { AgentDelegationPreflightResult.Ready(readyEvidence(p)) },
            childInitiative = AgentChildInitiativeCreator { AgentInitiativeResult.Created(unremovable) },
            bindings = AgentDelegatedBindingInstaller {
                AgentDelegatedWorkBindingInstallResult.Rejected("forced binding failure")
            },
            testOnly = Unit
        )

        assertIs<AgentDelegatedInitiativeResult.Failed>(bridge.create(request(p)))
        assertTrue(f.logs.snapshot().any { it.marker == "AGENT_DELEGATED_INITIATIVE_COMPENSATION_FAILED" })
    }

    @Test
    fun removed_delegation_causes_zero_autonomy_and_binding_writes() {
        val f = fixture()
        val p = prepare(f)
        assertTrue(p.delegation.remove())

        assertIs<AgentDelegatedInitiativeResult.Rejected>(f.bridge.create(request(p)))
        assertTrue(f.autonomy.snapshot().isEmpty())
        assertTrue(f.bindings.snapshot().isEmpty())
    }

    @Test
    fun delegated_creation_does_not_copy_delegation_purpose_into_autonomy_or_observability() {
        val f = fixture()
        val p = prepare(f)
        val secret = p.delegation.delegation.purpose

        val receipt = assertIs<AgentDelegatedInitiativeResult.Created>(
            f.bridge.create(request(p))
        ).ownership.receipt
        val proposal = f.autonomy.find(receipt.autonomy.proposalId)!!

        assertFalse(proposal.objective.contains(secret))
        assertFalse(proposal.triggerDescription.contains(secret))
        assertFalse(f.logs.snapshot().any { event ->
            event.message == secret || event.metadata.values.any { it == secret }
        })
    }
}
