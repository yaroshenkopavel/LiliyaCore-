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
import pro.liliya.core.autonomy.AutonomyPriority
import pro.liliya.core.autonomy.AutonomyProposalId
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
        val agents: AgentComposition,
        val lifecycle: ControlledAgentLifecycle,
        val delegations: AgentDelegationComposition,
        val autonomy: AutonomyComposition,
        val bindings: AgentDelegatedWorkBindingComposition,
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
            agents = agents,
            lifecycle = lifecycle,
            delegations = delegations,
            autonomy = autonomy,
            bindings = bindings,
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

    @Test
    fun exact_live_active_delegation_creates_child_autonomy_and_exact_binding() {
        val f = fixture()
        val p = prepare(f)

        val created = assertIs<AgentDelegatedInitiativeResult.Created>(
            f.bridge.create(request(p))
        ).ownership

        val autonomyReference = ExactAutonomyReference(
            created.autonomy.proposal.id,
            created.autonomy.generation
        )
        val binding = f.bindings.find(autonomyReference)

        assertEquals(created.binding.binding, binding)
        assertEquals(
            ExactAgentDelegationReference(p.delegation.delegation.id, p.delegation.generation),
            binding?.delegation
        )
        assertEquals(
            ExactAgentReference(p.child.agent.id, p.child.generation),
            binding?.child
        )
        assertTrue(f.autonomy.contains(created.autonomy.proposal.id))

        val origin = assertIs<AutonomyOrigin.Declared>(created.autonomy.proposal.origin)
        assertEquals("agent", origin.sourceId.value)
        assertEquals("agent:child@${p.child.generation.value}", origin.sourceReference?.value)
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
    fun exact_binding_conflict_rolls_back_newly_created_autonomy() {
        val f = fixture()
        val p = prepare(f)
        val expectedAutonomy = ExactAutonomyReference(
            proposalId = AutonomyProposalId("delegated-autonomy"),
            generation = AutonomyGeneration(1)
        )
        val conflicting = AgentDelegatedWorkBinding(
            delegation = ExactAgentDelegationReference(
                AgentDelegationId("other-delegation"),
                AgentDelegationGeneration(99)
            ),
            child = ExactAgentReference(AgentId("other-child"), AgentGeneration(99)),
            autonomy = expectedAutonomy
        )
        assertIs<AgentDelegatedWorkBindingInstallResult.Installed>(
            f.bindings.install(conflicting)
        )

        assertIs<AgentDelegatedInitiativeResult.Rejected>(f.bridge.create(request(p)))

        assertFalse(f.autonomy.contains(AutonomyProposalId("delegated-autonomy")))
        assertEquals(conflicting, f.bindings.find(expectedAutonomy))
        assertEquals(1, f.bindings.snapshot().size)
        assertTrue(f.logs.snapshot().any { it.marker == "AUTONOMY_PROPOSAL_REMOVED" })
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
    fun delegated_creation_does_not_copy_delegation_purpose_into_autonomy_or_result_observability() {
        val f = fixture()
        val p = prepare(f)
        val secret = p.delegation.delegation.purpose

        val created = assertIs<AgentDelegatedInitiativeResult.Created>(
            f.bridge.create(request(p))
        ).ownership

        assertFalse(created.autonomy.proposal.objective.contains(secret))
        assertFalse(created.autonomy.proposal.triggerDescription.contains(secret))
        assertFalse(f.logs.snapshot().any { event ->
            event.message == secret || event.metadata.values.any { it == secret }
        })
    }
}
