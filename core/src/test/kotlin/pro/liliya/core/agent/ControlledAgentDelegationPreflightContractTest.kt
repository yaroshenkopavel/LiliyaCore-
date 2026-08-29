package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
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
            correlationIds = CorrelationIdGenerator { "delegation-preflight-${sequence.incrementAndGet()}" }
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
                    origin = AgentOrigin.Declared(AgentSourceId("declared")),
                    role = "private $id role",
                    purpose = "private $id purpose",
                    createdAt = Instant.parse("2026-08-29T21:00:00Z")
                )
            )
        ).ownership

    private fun activate(f: Fixture, agent: AgentOwnership): AgentLifecycleOwnership =
        assertIs<AgentLifecycleActivationResult.Activated>(
            f.lifecycle.activate(agent.agent.id, agent.generation)
        ).ownership

    private fun prepare(f: Fixture, purpose: String = "private delegation purpose"): Prepared {
        val parent = installAgent(f, "parent")
        val parentLifecycle = activate(f, parent)
        val child = installAgent(f, "child")
        val childLifecycle = activate(f, child)
        val delegation = assertIs<AgentDelegationInstallResult.Installed>(
            f.delegations.install(
                AgentDelegationRecord(
                    id = AgentDelegationId("delegation-1"),
                    parent = ExactAgentReference(parent.agent.id, parent.generation),
                    child = ExactAgentReference(child.agent.id, child.generation),
                    purpose = purpose,
                    createdAt = Instant.parse("2026-08-29T21:01:00Z")
                )
            )
        ).ownership
        return Prepared(parent, parentLifecycle, child, childLifecycle, delegation)
    }

    @Test
    fun exact_live_active_delegation_returns_structural_evidence_only() {
        val f = fixture()
        val p = prepare(f)

        val ready = assertIs<AgentDelegationPreflightResult.Ready>(
            f.preflight.preflight(p.delegation.delegation.id, p.delegation.generation)
        )

        assertEquals(p.delegation.delegation.id, ready.evidence.delegationId)
        assertEquals(p.delegation.generation, ready.evidence.delegationGeneration)
        assertEquals(p.delegation.delegation.parent, ready.evidence.parent)
        assertEquals(p.delegation.delegation.child, ready.evidence.child)
    }

    @Test
    fun stale_or_removed_delegation_rejects() {
        val f = fixture()
        val p = prepare(f)
        assertTrue(p.delegation.remove())

        assertIs<AgentDelegationPreflightResult.Rejected>(
            f.preflight.preflight(p.delegation.delegation.id, p.delegation.generation)
        )
    }

    @Test
    fun missing_cancelled_or_stopped_parent_rejects() {
        listOf("removed", "cancelled", "stopped").forEach { mode ->
            val f = fixture()
            val p = prepare(f)
            when (mode) {
                "removed" -> assertTrue(p.parent.remove())
                "cancelled" -> assertTrue(p.parentLifecycle.cancel())
                else -> assertTrue(p.parentLifecycle.stop())
            }

            assertIs<AgentDelegationPreflightResult.Rejected>(
                f.preflight.preflight(p.delegation.delegation.id, p.delegation.generation)
            )
        }
    }

    @Test
    fun missing_cancelled_or_stopped_child_rejects() {
        listOf("removed", "cancelled", "stopped").forEach { mode ->
            val f = fixture()
            val p = prepare(f)
            when (mode) {
                "removed" -> assertTrue(p.child.remove())
                "cancelled" -> assertTrue(p.childLifecycle.cancel())
                else -> assertTrue(p.childLifecycle.stop())
            }

            assertIs<AgentDelegationPreflightResult.Rejected>(
                f.preflight.preflight(p.delegation.delegation.id, p.delegation.generation)
            )
        }
    }

    @Test
    fun stale_parent_or_child_replacement_rejects_exact_generation_relation() {
        val f = fixture()
        val p = prepare(f)
        assertTrue(p.child.remove())
        val replacement = installAgent(f, "child")
        activate(f, replacement)

        assertIs<AgentDelegationPreflightResult.Rejected>(
            f.preflight.preflight(p.delegation.delegation.id, p.delegation.generation)
        )
    }

    @Test
    fun private_delegation_purpose_does_not_enter_preflight_observability() {
        val f = fixture()
        val secret = "never-log-delegation-purpose"
        val p = prepare(f, purpose = secret)

        assertIs<AgentDelegationPreflightResult.Ready>(
            f.preflight.preflight(p.delegation.delegation.id, p.delegation.generation)
        )

        assertFalse(f.logs.snapshot().any { event ->
            event.message == secret || event.metadata.values.any { it == secret }
        })
    }

    @Test
    fun preflight_evidence_api_contains_no_permission_execution_or_scheduler_semantics() {
        val forbidden = setOf(
            "authority", "authorize", "permission", "capability", "execution", "execute",
            "executor", "scheduler", "schedule", "spawn", "replicate", "tool", "grant", "initiative"
        )
        val methodNames = AgentDelegationPreflightEvidence::class.java.methods.map { it.name.lowercase() }
        assertFalse(methodNames.any { name -> forbidden.any { token -> name.contains(token) } })
    }
}
