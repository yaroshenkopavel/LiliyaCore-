package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class AgentCoordinationCompositionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val composition: AgentCoordinationComposition
    )

    private fun fixture(prefix: String = "coordination-composition"): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "$prefix-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, AgentCoordinationComposition(foundation))
    }

    private fun record(
        id: String = "coordination-1",
        purpose: String = "private coordination purpose"
    ) = AgentCoordinationRecord(
        id = AgentCoordinationId(id),
        participants = listOf(
            ExactAgentReference(AgentId("agent-a"), AgentGeneration(3)),
            ExactAgentReference(AgentId("agent-b"), AgentGeneration(5))
        ),
        purpose = purpose,
        createdAt = Instant.parse("2026-08-29T21:45:00Z")
    )

    @Test
    fun install_exposes_exact_ownership_and_one_shot_remove() {
        val f = fixture()
        val value = record()
        val ownership = assertIs<AgentCoordinationInstallResult.Installed>(
            f.composition.install(value)
        ).ownership

        assertEquals(value, ownership.coordination)
        assertTrue(ownership.generation.value > 0)
        assertEquals(ownership.generation, f.composition.inspect(value.id)?.generation)
        assertTrue(ownership.remove())
        assertFalse(ownership.remove())
        assertNull(f.composition.find(value.id))
    }

    @Test
    fun stale_ownership_cannot_remove_replacement() {
        val f = fixture()
        val first = assertIs<AgentCoordinationInstallResult.Installed>(
            f.composition.install(record())
        ).ownership
        assertTrue(first.remove())

        val replacement = assertIs<AgentCoordinationInstallResult.Installed>(
            f.composition.install(record(purpose = "replacement private purpose"))
        ).ownership

        assertNotEquals(first.generation, replacement.generation)
        assertFalse(first.remove())
        assertEquals(replacement.generation, f.composition.inspect(replacement.coordination.id)?.generation)
    }

    @Test
    fun same_id_is_isolated_across_compositions() {
        val left = fixture("coordination-left")
        val right = fixture("coordination-right")
        val value = record()

        val leftOwnership = assertIs<AgentCoordinationInstallResult.Installed>(
            left.composition.install(value)
        ).ownership
        val rightOwnership = assertIs<AgentCoordinationInstallResult.Installed>(
            right.composition.install(value)
        ).ownership

        assertTrue(leftOwnership.remove())
        assertFalse(left.composition.contains(value.id))
        assertTrue(right.composition.contains(value.id))
        assertEquals(rightOwnership.generation, right.composition.inspect(value.id)?.generation)
    }

    @Test
    fun snapshots_are_detached_from_later_mutation() {
        val f = fixture()
        val first = record(id = "coordination-a")
        val second = record(id = "coordination-b")
        f.composition.install(first)
        val earlier = f.composition.snapshot()
        f.composition.install(second)

        assertEquals(listOf(first), earlier)
        assertEquals(listOf(first, second), f.composition.snapshot())
    }

    @Test
    fun private_purpose_is_absent_from_lifecycle_observability() {
        val f = fixture()
        val secret = "never-log-composition-purpose"
        val ownership = assertIs<AgentCoordinationInstallResult.Installed>(
            f.composition.install(record(purpose = secret))
        ).ownership
        ownership.remove()

        assertFalse(f.logs.snapshot().any { event ->
            event.message == secret || event.metadata.values.any { it == secret }
        })
    }

    @Test
    fun install_remove_correlation_is_root_to_child() {
        val f = fixture()
        val ownership = assertIs<AgentCoordinationInstallResult.Installed>(
            f.composition.install(record())
        ).ownership
        assertTrue(ownership.remove())

        val installed = f.logs.snapshot().first { it.context.operation == "installAgentCoordination" }
        val removed = f.logs.snapshot().first { it.context.operation == "removeAgentCoordination" }
        assertEquals(installed.context.correlationId, removed.context.parentCorrelationId)
    }

    @Test
    fun coordination_data_and_ownership_expose_no_runtime_power_semantics() {
        val forbidden = setOf(
            "authority", "authorize", "permission", "capability", "execution", "execute",
            "executor", "scheduler", "schedule", "spawn", "fanout", "vote", "voting",
            "consensus", "tool", "grant", "delegate", "initiative", "autonomy"
        )
        val types = listOf(
            AgentCoordinationRecord::class.java,
            AgentCoordinationSnapshot::class.java,
            AgentCoordinationOwnership::class.java,
            AgentCoordinationInstallResult::class.java
        )

        types.forEach { type ->
            val names = type.methods.map { it.name.lowercase() }
            assertFalse(names.any { name -> forbidden.any { token -> name.contains(token) } })
        }
    }

    @Test
    fun structural_composition_has_no_agent_lifecycle_delegation_or_autonomy_dependency() {
        val constructorTypes = AgentCoordinationComposition::class.java.constructors
            .flatMap { it.parameterTypes.toList() }
        val fieldTypes = AgentCoordinationComposition::class.java.declaredFields.map { it.type }
        val forbiddenTypes = setOf(
            AgentComposition::class.java,
            ControlledAgentLifecycle::class.java,
            AgentDelegationComposition::class.java
        )

        assertFalse(constructorTypes.any { it in forbiddenTypes })
        assertFalse(fieldTypes.any { it in forbiddenTypes })
    }
}
