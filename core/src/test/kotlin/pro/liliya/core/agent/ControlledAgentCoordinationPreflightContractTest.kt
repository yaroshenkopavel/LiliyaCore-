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

class ControlledAgentCoordinationPreflightContractTest {
    private data class ActiveAgent(
        val ownership: AgentOwnership,
        val lifecycle: AgentLifecycleOwnership
    )

    private data class Fixture(
        val logs: InMemoryLogWriter,
        val agents: AgentComposition,
        val lifecycle: ControlledAgentLifecycle,
        val coordinations: AgentCoordinationComposition,
        val preflight: ControlledAgentCoordinationPreflight
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "coordination-preflight-${sequence.incrementAndGet()}" }
        )
        val agents = AgentComposition(foundation)
        val lifecycle = ControlledAgentLifecycle(foundation, agents)
        val coordinations = AgentCoordinationComposition(foundation)
        return Fixture(
            logs = logs,
            agents = agents,
            lifecycle = lifecycle,
            coordinations = coordinations,
            preflight = ControlledAgentCoordinationPreflight(
                foundation = foundation,
                coordinations = coordinations,
                agents = agents,
                lifecycle = lifecycle
            )
        )
    }

    private fun installAgent(
        f: Fixture,
        id: String,
        activate: Boolean = true
    ): ActiveAgent {
        val ownership = assertIs<AgentInstallResult.Installed>(
            f.agents.install(
                AgentRecord(
                    id = AgentId(id),
                    origin = AgentOrigin.Declared(AgentSourceId("coordination-test")),
                    role = "private role $id",
                    purpose = "private purpose $id",
                    createdAt = Instant.parse("2026-08-30T00:00:00Z")
                )
            )
        ).ownership
        val lifecycle = if (activate) {
            assertIs<AgentLifecycleActivationResult.Activated>(
                f.lifecycle.activate(ownership.agent.id, ownership.generation)
            ).ownership
        } else {
            object : AgentLifecycleOwnership {
                override val agentId: AgentId = ownership.agent.id
                override val agentGeneration: AgentGeneration = ownership.generation
                override fun status(): AgentLifecycleStatus? = null
                override fun cancel(): Boolean = false
                override fun stop(): Boolean = false
            }
        }
        return ActiveAgent(ownership, lifecycle)
    }

    private fun installCoordination(
        f: Fixture,
        participants: List<ExactAgentReference>,
        id: String = "coordination-1",
        purpose: String = "private coordination purpose"
    ): AgentCoordinationOwnership = assertIs<AgentCoordinationInstallResult.Installed>(
        f.coordinations.install(
            AgentCoordinationRecord(
                id = AgentCoordinationId(id),
                participants = participants,
                purpose = purpose,
                createdAt = Instant.parse("2026-08-30T00:01:00Z")
            )
        )
    ).ownership

    private fun reference(agent: ActiveAgent): ExactAgentReference = ExactAgentReference(
        id = agent.ownership.agent.id,
        generation = agent.ownership.generation
    )

    @Test
    fun exact_live_active_participants_produce_structural_ready_evidence() {
        val f = fixture()
        val a = installAgent(f, "agent-a")
        val b = installAgent(f, "agent-b")
        val coordination = installCoordination(f, listOf(reference(b), reference(a)))

        val ready = assertIs<AgentCoordinationPreflightResult.Ready>(
            f.preflight.check(
                AgentCoordinationPreflightRequest(
                    coordinationId = coordination.coordination.id,
                    coordinationGeneration = coordination.generation
                )
            )
        )

        assertEquals(coordination.coordination.id, ready.evidence.coordinationId)
        assertEquals(coordination.generation, ready.evidence.coordinationGeneration)
        assertEquals(coordination.coordination.participants, ready.evidence.participants)
    }

    @Test
    fun stale_or_removed_coordination_generation_fails_closed() {
        val f = fixture()
        val a = installAgent(f, "agent-a")
        val b = installAgent(f, "agent-b")
        val stale = installCoordination(f, listOf(reference(a), reference(b)))
        assertTrue(stale.remove())

        assertIs<AgentCoordinationPreflightResult.Rejected>(
            f.preflight.check(
                AgentCoordinationPreflightRequest(stale.coordination.id, stale.generation)
            )
        )

        val replacement = installCoordination(f, listOf(reference(a), reference(b)))
        assertIs<AgentCoordinationPreflightResult.Rejected>(
            f.preflight.check(
                AgentCoordinationPreflightRequest(replacement.coordination.id, stale.generation)
            )
        )
    }

    @Test
    fun removed_or_replaced_participant_generation_fails_closed() {
        val f = fixture()
        val stale = installAgent(f, "agent-a")
        val b = installAgent(f, "agent-b")
        val coordination = installCoordination(f, listOf(reference(stale), reference(b)))
        assertTrue(stale.ownership.remove())
        installAgent(f, "agent-a")

        assertIs<AgentCoordinationPreflightResult.Rejected>(
            f.preflight.check(
                AgentCoordinationPreflightRequest(
                    coordination.coordination.id,
                    coordination.generation
                )
            )
        )
    }

    @Test
    fun missing_cancelled_or_stopped_participant_lifecycle_fails_closed() {
        listOf("missing", "cancelled", "stopped").forEach { mode ->
            val f = fixture()
            val a = installAgent(f, "agent-a", activate = mode != "missing")
            val b = installAgent(f, "agent-b")
            if (mode == "cancelled") assertTrue(a.lifecycle.cancel())
            if (mode == "stopped") assertTrue(a.lifecycle.stop())
            val coordination = installCoordination(f, listOf(reference(a), reference(b)))

            assertIs<AgentCoordinationPreflightResult.Rejected>(
                f.preflight.check(
                    AgentCoordinationPreflightRequest(
                        coordination.coordination.id,
                        coordination.generation
                    )
                )
            )
        }
    }

    @Test
    fun private_coordination_purpose_is_absent_from_evidence_and_observability() {
        val f = fixture()
        val a = installAgent(f, "agent-a")
        val b = installAgent(f, "agent-b")
        val secret = "never-expose-coordination-purpose"
        val coordination = installCoordination(
            f,
            listOf(reference(a), reference(b)),
            purpose = secret
        )

        val ready = assertIs<AgentCoordinationPreflightResult.Ready>(
            f.preflight.check(
                AgentCoordinationPreflightRequest(
                    coordination.coordination.id,
                    coordination.generation
                )
            )
        )

        assertFalse(ready.evidence.toString().contains(secret))
        assertFalse(f.logs.snapshot().any { event ->
            event.message == secret || event.metadata.values.any { it == secret }
        })
    }

    @Test
    fun preflight_exposes_no_work_authority_execution_or_consensus_dependency() {
        val forbidden = setOf(
            "authority", "authorize", "permission", "capability", "execution", "execute",
            "executor", "scheduler", "schedule", "fanout", "vote", "voting", "consensus",
            "initiative", "autonomy", "tool", "grant"
        )
        val dataTypes = listOf(
            AgentCoordinationPreflightRequest::class.java,
            AgentCoordinationReadyEvidence::class.java,
            AgentCoordinationPreflightResult::class.java
        )
        dataTypes.forEach { type ->
            assertFalse(type.methods.any { method ->
                forbidden.any { token -> method.name.lowercase().contains(token) }
            })
        }

        val dependencyTypes = ControlledAgentCoordinationPreflight::class.java.declaredFields
            .map { it.type }
        assertFalse(dependencyTypes.contains(AgentDelegationComposition::class.java))
        assertTrue(dependencyTypes.contains(AgentCoordinationComposition::class.java))
        assertTrue(dependencyTypes.contains(AgentComposition::class.java))
        assertTrue(dependencyTypes.contains(ControlledAgentLifecycle::class.java))
    }
}
