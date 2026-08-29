package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pro.liliya.core.autonomy.AutonomyGeneration
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class AgentStoreContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val store: AgentStore
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        var sequence = 0
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "agent-store-${++sequence}" }
        )
        return Fixture(logs, foundation, AgentStore(foundation.observability))
    }

    private fun agent(
        id: String = "agent-1",
        role: String = "private role",
        purpose: String = "private purpose",
        createdAt: String = "2026-08-29T16:45:00Z",
        origin: AgentOrigin = AgentOrigin.Declared(
            sourceId = AgentSourceId("declared-source"),
            sourceReference = AgentSourceReference("source-1")
        )
    ) = AgentRecord(
        id = AgentId(id),
        origin = origin,
        role = role,
        purpose = purpose,
        createdAt = Instant.parse(createdAt)
    )

    private fun context(f: Fixture, operation: String = "registerAgent") = f.foundation.rootContext(
        operation = operation,
        component = "Agent"
    )

    @Test
    fun exact_registration_read_and_remove_ownership() {
        val f = fixture()
        val value = agent()
        val registered = assertIs<AgentRegistrationResult.Registered>(
            f.store.register(value, context(f))
        ).registration

        assertEquals(value, registered.agent)
        assertTrue(registered.generation.value > 0)
        assertEquals(value, f.store.find(value.id))
        assertEquals(registered.generation, f.store.inspect(value.id)?.generation)
        assertTrue(f.store.contains(value.id))

        assertTrue(registered.remove(context(f, "removeAgent")))
        assertFalse(f.store.contains(value.id))
        assertNull(f.store.find(value.id))
        assertFalse(registered.remove(context(f, "removeAgentAgain")))
    }

    @Test
    fun duplicate_id_rejects_without_replacement() {
        val f = fixture()
        val first = agent(role = "first secret role")
        val second = agent(role = "second secret role")

        val firstRegistration = assertIs<AgentRegistrationResult.Registered>(
            f.store.register(first, context(f))
        ).registration
        assertIs<AgentRegistrationResult.Rejected>(
            f.store.register(second, context(f))
        )

        assertEquals(first, f.store.find(first.id))
        assertEquals(firstRegistration.generation, f.store.inspect(first.id)?.generation)
    }

    @Test
    fun stale_registration_cannot_remove_replacement() {
        val f = fixture()
        val first = assertIs<AgentRegistrationResult.Registered>(
            f.store.register(agent(), context(f))
        ).registration
        assertTrue(first.remove(context(f, "removeFirst")))

        val replacement = assertIs<AgentRegistrationResult.Registered>(
            f.store.register(agent(role = "replacement role"), context(f))
        ).registration

        assertNotEquals(first.generation, replacement.generation)
        assertFalse(first.remove(context(f, "staleRemove")))
        assertEquals(replacement.agent, f.store.find(replacement.agent.id))
        assertEquals(replacement.generation, f.store.inspect(replacement.agent.id)?.generation)
    }

    @Test
    fun exact_autonomy_origin_survives_as_data_only() {
        val f = fixture()
        val origin = AgentOrigin.Autonomy(
            proposalId = AutonomyProposalId("autonomy-17"),
            generation = AutonomyGeneration(19)
        )
        val value = agent(origin = origin)

        f.store.register(value, context(f))

        assertEquals(origin, f.store.find(value.id)?.origin)
    }

    @Test
    fun role_and_purpose_are_redacted_from_rendering_and_lifecycle_observability() {
        val f = fixture()
        val secretRole = "never-log-agent-role"
        val secretPurpose = "never-log-agent-purpose"
        val value = agent(role = secretRole, purpose = secretPurpose)
        val registration = assertIs<AgentRegistrationResult.Registered>(
            f.store.register(value, context(f))
        ).registration
        registration.remove(context(f, "removeAgent"))

        val rendered = value.toString()
        assertFalse(rendered.contains(secretRole))
        assertFalse(rendered.contains(secretPurpose))
        assertFalse(f.logs.snapshot().any { event ->
            event.message == secretRole || event.message == secretPurpose ||
                event.metadata.values.any { it == secretRole || it == secretPurpose }
        })
    }

    @Test
    fun snapshots_are_deterministic_detached_views() {
        val f = fixture()
        val laterIdEarlierTime = agent(
            id = "agent-z",
            createdAt = "2026-08-29T16:45:00Z"
        )
        val earlierIdSameTime = agent(
            id = "agent-a",
            createdAt = "2026-08-29T16:45:00Z"
        )
        val latest = agent(
            id = "agent-b",
            createdAt = "2026-08-29T16:46:00Z"
        )

        f.store.register(laterIdEarlierTime, context(f))
        f.store.register(earlierIdSameTime, context(f))
        val earlierSnapshot = f.store.snapshot()
        f.store.register(latest, context(f))

        assertEquals(listOf(earlierIdSameTime, laterIdEarlierTime), earlierSnapshot)
        assertEquals(listOf(earlierIdSameTime, laterIdEarlierTime, latest), f.store.snapshot())
    }

    @Test
    fun concurrent_same_id_registration_has_exactly_one_winner() {
        val f = fixture()
        val results = ConcurrentLinkedQueue<AgentRegistrationResult>()
        val workers = List(24) { index ->
            thread(start = false) {
                results += f.store.register(
                    agent(role = "role-$index", purpose = "purpose-$index"),
                    context(f, "concurrentRegisterAgent")
                )
            }
        }

        workers.forEach { it.start() }
        workers.forEach { it.join() }

        assertEquals(1, results.count { it is AgentRegistrationResult.Registered })
        assertEquals(23, results.count { it is AgentRegistrationResult.Rejected })
        assertTrue(f.store.contains(AgentId("agent-1")))
    }
}
