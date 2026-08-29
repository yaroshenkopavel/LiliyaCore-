package pro.liliya.core.agent

import java.util.concurrent.atomic.AtomicInteger
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

class AgentCoordinationWorkBindingCompositionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val composition: AgentCoordinationWorkBindingComposition
    )

    private fun fixture(prefix: String = "coordination-binding-composition"): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "$prefix-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, AgentCoordinationWorkBindingComposition(foundation))
    }

    private fun binding(
        coordinationId: String = "coordination-1",
        coordinationGeneration: Long = 3,
        suffix: String = "base"
    ) = AgentCoordinationWorkBinding(
        coordination = ExactAgentCoordinationReference(
            AgentCoordinationId(coordinationId),
            AgentCoordinationGeneration(coordinationGeneration)
        ),
        assignments = listOf(
            AgentCoordinationWorkAssignment(
                ExactAgentReference(AgentId("agent-a"), AgentGeneration(5)),
                ExactAutonomyReference(AutonomyProposalId("autonomy-a-$suffix"), AutonomyGeneration(7))
            ),
            AgentCoordinationWorkAssignment(
                ExactAgentReference(AgentId("agent-b"), AgentGeneration(11)),
                ExactAutonomyReference(AutonomyProposalId("autonomy-b-$suffix"), AutonomyGeneration(13))
            )
        )
    )

    @Test
    fun install_exposes_exact_generation_ownership_secondary_lookup_and_one_shot_remove() {
        val f = fixture()
        val value = binding()
        val ownership = assertIs<AgentCoordinationWorkBindingInstallResult.Installed>(
            f.composition.install(value)
        ).ownership

        assertEquals(value, ownership.binding)
        assertTrue(ownership.generation.value > 0)
        assertEquals(ownership.generation, f.composition.inspect(value.coordination)?.generation)
        value.assignments.forEach { assignment ->
            assertEquals(value, f.composition.findByAutonomy(assignment.autonomy))
        }

        assertTrue(ownership.remove())
        assertFalse(ownership.remove())
        assertNull(f.composition.find(value.coordination))
        value.assignments.forEach { assignment ->
            assertNull(f.composition.findByAutonomy(assignment.autonomy))
        }
    }

    @Test
    fun stale_ownership_cannot_remove_replacement() {
        val f = fixture()
        val first = assertIs<AgentCoordinationWorkBindingInstallResult.Installed>(
            f.composition.install(binding(suffix = "first"))
        ).ownership
        assertTrue(first.remove())

        val replacement = assertIs<AgentCoordinationWorkBindingInstallResult.Installed>(
            f.composition.install(binding(suffix = "replacement"))
        ).ownership

        assertNotEquals(first.generation, replacement.generation)
        assertFalse(first.remove())
        assertEquals(replacement.binding, f.composition.find(replacement.binding.coordination))
        replacement.binding.assignments.forEach { assignment ->
            assertEquals(replacement.binding, f.composition.findByAutonomy(assignment.autonomy))
        }
    }

    @Test
    fun same_exact_binding_is_isolated_across_compositions() {
        val left = fixture("coordination-binding-left")
        val right = fixture("coordination-binding-right")
        val value = binding()

        val leftOwnership = assertIs<AgentCoordinationWorkBindingInstallResult.Installed>(
            left.composition.install(value)
        ).ownership
        val rightOwnership = assertIs<AgentCoordinationWorkBindingInstallResult.Installed>(
            right.composition.install(value)
        ).ownership

        assertTrue(leftOwnership.remove())
        assertNull(left.composition.find(value.coordination))
        assertEquals(rightOwnership.binding, right.composition.find(value.coordination))
    }

    @Test
    fun snapshots_are_detached_and_deterministic() {
        val f = fixture()
        val z = binding("coordination-z", 2, "z")
        val a = binding("coordination-a", 3, "a")
        val b = binding("coordination-b", 4, "b")
        f.composition.install(z)
        f.composition.install(a)
        val detached = f.composition.snapshot()
        f.composition.install(b)

        assertEquals(
            listOf("coordination-a", "coordination-z"),
            detached.map { it.binding.coordination.id.value }
        )
        assertEquals(
            listOf("coordination-a", "coordination-b", "coordination-z"),
            f.composition.snapshot().map { it.binding.coordination.id.value }
        )
    }

    @Test
    fun install_remove_correlation_is_root_to_child() {
        val f = fixture()
        val ownership = assertIs<AgentCoordinationWorkBindingInstallResult.Installed>(
            f.composition.install(binding())
        ).ownership
        assertTrue(ownership.remove())

        val installed = f.logs.snapshot().first {
            it.context.operation == "installAgentCoordinationWorkBinding"
        }
        val removed = f.logs.snapshot().first {
            it.context.operation == "removeAgentCoordinationWorkBinding"
        }
        assertEquals(installed.context.correlationId, removed.context.parentCorrelationId)
    }

    @Test
    fun composition_has_no_live_agent_lifecycle_authority_or_execution_dependency() {
        val fieldTypes = AgentCoordinationWorkBindingComposition::class.java.declaredFields.map { it.type }
        val forbiddenTypes = setOf(
            AgentComposition::class.java,
            ControlledAgentLifecycle::class.java,
            AgentCoordinationComposition::class.java,
            AgentDelegationComposition::class.java
        )
        assertFalse(fieldTypes.any { it in forbiddenTypes })

        val forbiddenNames = setOf(
            "authority", "authorize", "permission", "capability", "execution", "execute",
            "scheduler", "fanout", "vote", "consensus", "grant"
        )
        val publicNames = AgentCoordinationWorkBindingOwnership::class.java.methods.map { it.name.lowercase() }
        assertFalse(publicNames.any { name -> forbiddenNames.any { token -> name.contains(token) } })
    }
}
