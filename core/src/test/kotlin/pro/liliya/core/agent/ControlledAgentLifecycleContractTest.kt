package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class ControlledAgentLifecycleContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val agents: AgentComposition,
        val lifecycle: ControlledAgentLifecycle
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "agent-lifecycle-${sequence.incrementAndGet()}" }
        )
        val agents = AgentComposition(foundation)
        return Fixture(logs, agents, ControlledAgentLifecycle(foundation, agents))
    }

    private fun install(
        f: Fixture,
        id: String = "agent-1",
        role: String = "private role",
        purpose: String = "private purpose"
    ): AgentOwnership = assertIs<AgentInstallResult.Installed>(
        f.agents.install(
            AgentRecord(
                id = AgentId(id),
                origin = AgentOrigin.Declared(AgentSourceId("declared")),
                role = role,
                purpose = purpose,
                createdAt = Instant.parse("2026-08-29T18:30:00Z")
            )
        )
    ).ownership

    @Test
    fun exact_live_agent_activates_explicit_lifecycle() {
        val f = fixture()
        val agent = install(f)

        val lifecycle = assertIs<AgentLifecycleActivationResult.Activated>(
            f.lifecycle.activate(agent.agent.id, agent.generation)
        ).ownership

        assertEquals(AgentLifecycleStatus.ACTIVE, lifecycle.status())
        assertEquals(
            AgentLifecycleSnapshot(agent.agent.id, agent.generation, AgentLifecycleStatus.ACTIVE),
            f.lifecycle.inspect(agent.agent.id, agent.generation)
        )
    }

    @Test
    fun missing_or_stale_agent_generation_cannot_activate_lifecycle() {
        val f = fixture()
        val stale = install(f)
        assertTrue(stale.remove())
        val replacement = install(f)

        assertIs<AgentLifecycleActivationResult.Rejected>(
            f.lifecycle.activate(stale.agent.id, stale.generation)
        )
        assertNull(f.lifecycle.inspect(stale.agent.id, stale.generation))
        assertIs<AgentLifecycleActivationResult.Activated>(
            f.lifecycle.activate(replacement.agent.id, replacement.generation)
        )
    }

    @Test
    fun cancellation_is_exact_generation_terminal_and_repeated_transition_fails_closed() {
        val f = fixture()
        val agent = install(f)
        val lifecycle = assertIs<AgentLifecycleActivationResult.Activated>(
            f.lifecycle.activate(agent.agent.id, agent.generation)
        ).ownership

        assertTrue(lifecycle.cancel())
        assertEquals(AgentLifecycleStatus.CANCELLED, lifecycle.status())
        assertFalse(lifecycle.cancel())
        assertFalse(lifecycle.stop())
        assertEquals(AgentLifecycleStatus.CANCELLED, lifecycle.status())
    }

    @Test
    fun stop_is_exact_generation_terminal_and_repeated_transition_fails_closed() {
        val f = fixture()
        val agent = install(f)
        val lifecycle = assertIs<AgentLifecycleActivationResult.Activated>(
            f.lifecycle.activate(agent.agent.id, agent.generation)
        ).ownership

        assertTrue(lifecycle.stop())
        assertEquals(AgentLifecycleStatus.STOPPED, lifecycle.status())
        assertFalse(lifecycle.stop())
        assertFalse(lifecycle.cancel())
    }

    @Test
    fun stale_lifecycle_handle_cannot_affect_replacement_generation() {
        val f = fixture()
        val oldAgent = install(f)
        val oldLifecycle = assertIs<AgentLifecycleActivationResult.Activated>(
            f.lifecycle.activate(oldAgent.agent.id, oldAgent.generation)
        ).ownership
        assertTrue(oldAgent.remove())

        val replacement = install(f)
        val replacementLifecycle = assertIs<AgentLifecycleActivationResult.Activated>(
            f.lifecycle.activate(replacement.agent.id, replacement.generation)
        ).ownership

        assertTrue(oldLifecycle.cancel())
        assertEquals(AgentLifecycleStatus.CANCELLED, oldLifecycle.status())
        assertEquals(AgentLifecycleStatus.ACTIVE, replacementLifecycle.status())
    }

    @Test
    fun lifecycle_state_remains_explicit_after_agent_record_removal() {
        val f = fixture()
        val agent = install(f)
        val lifecycle = assertIs<AgentLifecycleActivationResult.Activated>(
            f.lifecycle.activate(agent.agent.id, agent.generation)
        ).ownership
        assertTrue(agent.remove())

        assertEquals(AgentLifecycleStatus.ACTIVE, lifecycle.status())
        assertTrue(lifecycle.cancel())
        assertEquals(AgentLifecycleStatus.CANCELLED, lifecycle.status())
    }

    @Test
    fun lifecycle_snapshot_is_deterministic_and_private_agent_payload_is_not_observed() {
        val f = fixture()
        val secretRole = "lifecycle-secret-role"
        val secretPurpose = "lifecycle-secret-purpose"
        val b = install(f, id = "agent-b", role = secretRole, purpose = secretPurpose)
        val a = install(f, id = "agent-a")
        assertIs<AgentLifecycleActivationResult.Activated>(f.lifecycle.activate(b.agent.id, b.generation))
        assertIs<AgentLifecycleActivationResult.Activated>(f.lifecycle.activate(a.agent.id, a.generation))

        assertEquals(listOf("agent-a", "agent-b"), f.lifecycle.snapshot().map { it.agentId.value })
        assertFalse(f.logs.snapshot().any { event ->
            event.message == secretRole || event.message == secretPurpose ||
                event.metadata.values.any { it == secretRole || it == secretPurpose }
        })
    }
}
