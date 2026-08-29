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

class ControlledAgentLifecycleReadinessContractTest {
    private data class Fixture(
        val agents: AgentComposition,
        val lifecycle: ControlledAgentLifecycle
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "agent-lifecycle-ready-${sequence.incrementAndGet()}" }
        )
        val agents = AgentComposition(foundation)
        return Fixture(
            agents = agents,
            lifecycle = ControlledAgentLifecycle(foundation, agents)
        )
    }

    private fun installAgent(f: Fixture): AgentOwnership =
        assertIs<AgentInstallResult.Installed>(
            f.agents.install(
                AgentRecord(
                    id = AgentId("agent-ready"),
                    origin = AgentOrigin.Declared(AgentSourceId("declared")),
                    role = "private role",
                    purpose = "private purpose",
                    createdAt = Instant.parse("2026-08-29T19:30:00Z")
                )
            )
        ).ownership

    @Test
    fun lifecycle_public_api_contains_no_scheduler_authority_execution_or_delegation_semantics() {
        val forbidden = setOf(
            "authority", "authorize", "permission", "capability", "execution", "execute",
            "executor", "scheduler", "schedule", "spawn", "replicate", "tool", "delegate"
        )
        val types = listOf(
            AgentLifecycleStatus::class.java,
            AgentLifecycleSnapshot::class.java,
            AgentLifecycleOwnership::class.java,
            AgentLifecycleActivationResult::class.java,
            ControlledAgentLifecycle::class.java
        )

        types.forEach { type ->
            val methodNames = type.methods.map { it.name.lowercase() }
            assertFalse(methodNames.any { name -> forbidden.any { token -> name.contains(token) } })
        }
    }

    @Test
    fun lifecycle_is_absent_until_explicit_exact_generation_activation() {
        val f = fixture()
        val agent = installAgent(f)

        assertNull(f.lifecycle.inspect(agent.agent.id, agent.generation))
        assertFalse(f.lifecycle.isActive(agent.agent.id, agent.generation))

        val lifecycle = assertIs<AgentLifecycleActivationResult.Activated>(
            f.lifecycle.activate(agent.agent.id, agent.generation)
        ).ownership

        assertEquals(AgentLifecycleStatus.ACTIVE, lifecycle.status())
        assertTrue(f.lifecycle.isActive(agent.agent.id, agent.generation))
    }

    @Test
    fun terminal_lifecycle_state_is_exact_generation_scoped_and_never_reactivates_implicitly() {
        val f = fixture()
        val stale = installAgent(f)
        val staleLifecycle = assertIs<AgentLifecycleActivationResult.Activated>(
            f.lifecycle.activate(stale.agent.id, stale.generation)
        ).ownership
        assertTrue(staleLifecycle.cancel())
        assertFalse(f.lifecycle.isActive(stale.agent.id, stale.generation))
        assertTrue(stale.remove())

        val replacement = installAgent(f)
        assertFalse(f.lifecycle.isActive(replacement.agent.id, replacement.generation))
        assertEquals(
            AgentLifecycleStatus.CANCELLED,
            f.lifecycle.inspect(stale.agent.id, stale.generation)?.status
        )

        val replacementLifecycle = assertIs<AgentLifecycleActivationResult.Activated>(
            f.lifecycle.activate(replacement.agent.id, replacement.generation)
        ).ownership
        assertEquals(AgentLifecycleStatus.ACTIVE, replacementLifecycle.status())
        assertEquals(AgentLifecycleStatus.CANCELLED, staleLifecycle.status())
    }

    @Test
    fun active_state_is_lifecycle_evidence_only_not_permission_or_execution_result() {
        val f = fixture()
        val agent = installAgent(f)
        val lifecycle = assertIs<AgentLifecycleActivationResult.Activated>(
            f.lifecycle.activate(agent.agent.id, agent.generation)
        ).ownership

        assertEquals(AgentLifecycleStatus.ACTIVE, lifecycle.status())
        assertFalse(lifecycle::class.java.methods.any { method ->
            val name = method.name.lowercase()
            name.contains("grant") || name.contains("authorize") || name.contains("execute") ||
                name.contains("permission") || name.contains("capability")
        })
    }
}
