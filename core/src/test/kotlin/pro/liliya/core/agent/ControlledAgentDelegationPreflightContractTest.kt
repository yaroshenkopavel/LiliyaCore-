package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.autonomy.AutonomyComposition
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class ControlledAgentDelegationPreflightContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val agents: AgentComposition,
        val lifecycle: ControlledAgentLifecycle,
        val delegations: AgentDelegationComposition,
        val preflight: ControlledAgentDelegationPreflight
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "agent-delegation-preflight-${sequence.incrementAndGet()}" }
        )
        val agents = AgentComposition(foundation)
        val lifecycle = ControlledAgentLifecycle(foundation, agents)
        val delegations = AgentDelegationComposition(foundation)
        return Fixture(
            logs = logs,
            agents = agents,
            lifecycle = lifecycle,
            delegations = delegations,
            preflight = ControlledAgentDelegationPreflight(
                foundation = foundation,
                delegations = delegations,
                agents = agents,
                lifecycle = lifecycle
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
                    createdAt = Instant.parse("2026-08-29T20:30:00Z")
                )
            )
        ).ownership

    private fun activate(f: Fixture, agent: AgentOwnership): AgentLifecycleOwnership =
        assertIs<AgentLifecycleActivationResult.Activated>(
            f.lifecycle.activate(agent.agent.id, agent.generation)
        ).ownership

    private fun installDelegation(
        f: Fixture,
        parent: AgentOwnership,
        child: AgentOwnership,
        purpose: String = "private delegation purpose"
    ): AgentDelegationOwnership = assertIs<AgentDelegationInstallResult.Installed>(
        f.delegations.install(
            AgentDelegationRecord(
                id = AgentDelegationId("delegation-1"),
                parent = ExactAgentReference(parent.agent.id, parent.generation),
                child = ExactAgentReference(child.agent.id, child.generation),
                purpose = purpose,
                createdAt = Instant.parse("2026-08-29T20:31:00Z")
            )
        )
    ).ownership

    private data class ReadyFixture(
        val fixture: Fixture,
        val parent: AgentOwnership,
        val parentLifecycle: AgentLifecycleOwnership,
        val child: AgentOwnership,
        val childLifecycle: AgentLifecycleOwnership,
        val delegation: AgentDelegationOwnership
    )

    private fun readyFixture(): ReadyFixture {
        val f = fixture()
        val parent = installAgent(f, "parent-1")
        val child = installAgent(f, "child-1")
        val parentLifecycle = activate(f, parent)
        val childLifecycle = activate(f, child)
        val delegation = installDelegation(f, parent, child)
        return ReadyFixture(f, parent, parentLifecycle, child, childLifecycle, delegation)
    }

    private fun request(delegation: AgentDelegationOwnership) = AgentDelegationPreflightRequest(
        delegationId = delegation.delegation.id,
        delegationGeneration = delegation.generation
    )

    @Test
    fun exact_live_delegation_with_both_active_endpoints_returns_structural_ready_evidence() {
        val r = readyFixture()

        val ready = assertIs<AgentDelegationPreflightResult.Ready>(
            r.fixture.preflight.check(request(r.delegation))
        ).evidence

        assertEquals(r.delegation.delegation.id, ready.delegationId)
        assertEquals(r.delegation.generation, ready.delegationGeneration)
        assertEquals(r.delegation.delegation.parent, ready.parent)
        assertEquals(r.delegation.delegation.child, ready.child)
    }

    @Test
    fun stale_delegation_generation_fails_closed() {
        val r = readyFixture()
        val staleGeneration = r.delegation.generation
        assertTrue(r.delegation.remove())
        val replacement = installDelegation(r.fixture, r.parent, r.child)

        assertIs<AgentDelegationPreflightResult.Rejected>(
            r.fixture.preflight.check(
                AgentDelegationPreflightRequest(
                    delegationId = replacement.delegation.id,
                    delegationGeneration = staleGeneration
                )
            )
        )
    }

    @Test
    fun removed_or_replaced_parent_generation_fails_closed() {
        val r = readyFixture()
        assertTrue(r.parent.remove())
        val replacement = installAgent(r.fixture, "parent-1")
        activate(r.fixture, replacement)

        assertIs<AgentDelegationPreflightResult.Rejected>(
            r.fixture.preflight.check(request(r.delegation))
        )
    }

    @Test
    fun removed_or_replaced_child_generation_fails_closed() {
        val r = readyFixture()
        assertTrue(r.child.remove())
        val replacement = installAgent(r.fixture, "child-1")
        activate(r.fixture, replacement)

        assertIs<AgentDelegationPreflightResult.Rejected>(
            r.fixture.preflight.check(request(r.delegation))
        )
    }

    @Test
    fun missing_parent_lifecycle_fails_closed() {
        val f = fixture()
        val parent = installAgent(f, "parent-1")
        val child = installAgent(f, "child-1")
        activate(f, child)
        val delegation = installDelegation(f, parent, child)

        assertIs<AgentDelegationPreflightResult.Rejected>(
            f.preflight.check(request(delegation))
        )
    }

    @Test
    fun cancelled_parent_lifecycle_fails_closed() {
        val r = readyFixture()
        assertTrue(r.parentLifecycle.cancel())

        assertIs<AgentDelegationPreflightResult.Rejected>(
            r.fixture.preflight.check(request(r.delegation))
        )
    }

    @Test
    fun stopped_child_lifecycle_fails_closed() {
        val r = readyFixture()
        assertTrue(r.childLifecycle.stop())

        assertIs<AgentDelegationPreflightResult.Rejected>(
            r.fixture.preflight.check(request(r.delegation))
        )
    }

    @Test
    fun private_delegation_purpose_is_absent_from_ready_evidence_and_observability() {
        val f = fixture()
        val parent = installAgent(f, "parent-1")
        val child = installAgent(f, "child-1")
        activate(f, parent)
        activate(f, child)
        val secret = "never-log-delegation-purpose"
        val delegation = installDelegation(f, parent, child, purpose = secret)

        val ready = assertIs<AgentDelegationPreflightResult.Ready>(
            f.preflight.check(request(delegation))
        ).evidence

        assertFalse(ready.toString().contains(secret))
        assertFalse(f.logs.snapshot().any { event ->
            event.message.contains(secret) || event.metadata.values.any { it.contains(secret) }
        })
    }

    @Test
    fun preflight_has_no_autonomy_initiative_authority_execution_or_scheduler_dependency() {
        val constructorTypes = ControlledAgentDelegationPreflight::class.java.constructors
            .flatMap { it.parameterTypes.toList() }
        val fieldTypes = ControlledAgentDelegationPreflight::class.java.declaredFields
            .map { it.type }
        val forbiddenTypes = setOf(
            AutonomyComposition::class.java,
            ControlledAgentInitiative::class.java,
            ControlledAgentInitiativeGate::class.java,
            ControlledAgentExecution::class.java
        )

        assertFalse(constructorTypes.any { it in forbiddenTypes })
        assertFalse(fieldTypes.any { it in forbiddenTypes })

        val forbiddenNames = setOf(
            "authority", "authorize", "permission", "capability", "execution", "execute",
            "executor", "scheduler", "schedule", "spawn", "replicate", "tool", "grant", "initiative"
        )
        val publicNames = AgentDelegationReadyEvidence::class.java.methods.map { it.name.lowercase() }
        assertFalse(publicNames.any { name -> forbiddenNames.any { token -> name.contains(token) } })
    }
}
