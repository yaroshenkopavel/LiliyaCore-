package pro.liliya.core.agent

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

class AgentCoordinationWorkBindingContractTest {
    private data class Fixture(
        val foundation: FoundationComposition,
        val store: AgentCoordinationWorkBindingStore
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        var sequence = 0
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "coordination-binding-${++sequence}" }
        )
        return Fixture(foundation, AgentCoordinationWorkBindingStore(foundation.observability))
    }

    private fun context(f: Fixture, operation: String = "bindCoordinationWork") =
        f.foundation.rootContext(operation = operation, component = "AgentCoordination")

    private fun coordination(
        id: String = "coordination-1",
        generation: Long = 7
    ) = ExactAgentCoordinationReference(
        AgentCoordinationId(id),
        AgentCoordinationGeneration(generation)
    )

    private fun assignment(
        agentId: String,
        agentGeneration: Long,
        autonomyId: String,
        autonomyGeneration: Long
    ) = AgentCoordinationWorkAssignment(
        participant = ExactAgentReference(AgentId(agentId), AgentGeneration(agentGeneration)),
        autonomy = ExactAutonomyReference(
            AutonomyProposalId(autonomyId),
            AutonomyGeneration(autonomyGeneration)
        )
    )

    private fun binding(
        coordination: ExactAgentCoordinationReference = coordination(),
        suffix: String = "base"
    ) = AgentCoordinationWorkBinding(
        coordination = coordination,
        assignments = listOf(
            assignment("agent-b", 5, "autonomy-b-$suffix", 13),
            assignment("agent-a", 3, "autonomy-a-$suffix", 11)
        )
    )

    @Test
    fun binding_requires_multi_agent_unique_participants_and_unique_autonomy() {
        assertFailsWith<IllegalArgumentException> {
            AgentCoordinationWorkBinding(
                coordination(),
                listOf(assignment("agent-a", 1, "autonomy-a", 1))
            )
        }

        val duplicateParticipant = assignment("agent-a", 1, "autonomy-a", 1)
        assertFailsWith<IllegalArgumentException> {
            AgentCoordinationWorkBinding(
                coordination(),
                listOf(
                    duplicateParticipant,
                    duplicateParticipant.copy(
                        autonomy = ExactAutonomyReference(AutonomyProposalId("autonomy-b"), AutonomyGeneration(2))
                    )
                )
            )
        }

        assertFailsWith<IllegalArgumentException> {
            AgentCoordinationWorkBinding(
                coordination(),
                listOf(
                    assignment("agent-a", 1, "autonomy-a", 1),
                    assignment("agent-a", 2, "autonomy-b", 2)
                )
            )
        }

        val sharedAutonomy = ExactAutonomyReference(AutonomyProposalId("shared"), AutonomyGeneration(9))
        assertFailsWith<IllegalArgumentException> {
            AgentCoordinationWorkBinding(
                coordination(),
                listOf(
                    AgentCoordinationWorkAssignment(
                        ExactAgentReference(AgentId("agent-a"), AgentGeneration(1)),
                        sharedAutonomy
                    ),
                    AgentCoordinationWorkAssignment(
                        ExactAgentReference(AgentId("agent-b"), AgentGeneration(2)),
                        sharedAutonomy
                    )
                )
            )
        }
    }

    @Test
    fun assignments_are_normalized_deterministically() {
        val value = binding()
        assertEquals(listOf("agent-a", "agent-b"), value.assignments.map { it.participant.id.value })
    }

    @Test
    fun exact_registration_read_secondary_lookup_and_one_shot_remove() {
        val f = fixture()
        val value = binding()
        val registration = assertIs<AgentCoordinationWorkBindingRegistrationResult.Registered>(
            f.store.register(value, context(f))
        ).registration

        assertTrue(registration.generation.value > 0)
        assertEquals(value, f.store.find(value.coordination))
        assertEquals(registration.generation, f.store.inspect(value.coordination)?.generation)
        value.assignments.forEach { assignment ->
            assertEquals(value, f.store.findByAutonomy(assignment.autonomy))
        }

        assertTrue(registration.remove(context(f, "unbindCoordinationWork")))
        assertFalse(registration.remove(context(f, "unbindCoordinationWorkAgain")))
        assertNull(f.store.find(value.coordination))
        value.assignments.forEach { assignment ->
            assertNull(f.store.findByAutonomy(assignment.autonomy))
        }
    }

    @Test
    fun duplicate_exact_coordination_rejects_without_replacement() {
        val f = fixture()
        val first = binding(suffix = "first")
        val firstRegistration = assertIs<AgentCoordinationWorkBindingRegistrationResult.Registered>(
            f.store.register(first, context(f))
        ).registration
        val second = binding(suffix = "second")

        assertIs<AgentCoordinationWorkBindingRegistrationResult.Rejected>(
            f.store.register(second, context(f))
        )
        assertEquals(first, f.store.find(first.coordination))
        assertEquals(firstRegistration.generation, f.store.inspect(first.coordination)?.generation)
    }

    @Test
    fun exact_autonomy_cannot_be_reused_across_coordination_work_sets() {
        val f = fixture()
        val first = binding(coordination("coordination-a", 1), suffix = "shared")
        assertIs<AgentCoordinationWorkBindingRegistrationResult.Registered>(
            f.store.register(first, context(f))
        )

        val reused = first.assignments.first().autonomy
        val second = AgentCoordinationWorkBinding(
            coordination = coordination("coordination-b", 2),
            assignments = listOf(
                AgentCoordinationWorkAssignment(
                    ExactAgentReference(AgentId("agent-c"), AgentGeneration(7)),
                    reused
                ),
                assignment("agent-d", 9, "autonomy-d", 17)
            )
        )

        assertIs<AgentCoordinationWorkBindingRegistrationResult.Rejected>(
            f.store.register(second, context(f))
        )
        assertNull(f.store.find(second.coordination))
        assertEquals(first, f.store.findByAutonomy(reused))
    }

    @Test
    fun stale_registration_cannot_remove_replacement_even_for_same_exact_coordination_key() {
        val f = fixture()
        val first = assertIs<AgentCoordinationWorkBindingRegistrationResult.Registered>(
            f.store.register(binding(suffix = "first"), context(f))
        ).registration
        assertTrue(first.remove(context(f, "removeFirst")))

        val replacementValue = binding(suffix = "replacement")
        val replacement = assertIs<AgentCoordinationWorkBindingRegistrationResult.Registered>(
            f.store.register(replacementValue, context(f))
        ).registration

        assertNotEquals(first.generation, replacement.generation)
        assertFalse(first.remove(context(f, "staleRemove")))
        assertEquals(replacementValue, f.store.find(replacementValue.coordination))
        assertEquals(replacement.generation, f.store.inspect(replacementValue.coordination)?.generation)
    }

    @Test
    fun snapshots_are_deterministic_detached_views() {
        val f = fixture()
        val later = binding(coordination("coordination-z", 2), suffix = "z")
        val earlier = binding(coordination("coordination-a", 3), suffix = "a")
        val latest = binding(coordination("coordination-b", 4), suffix = "b")

        f.store.register(later, context(f))
        f.store.register(earlier, context(f))
        val detached = f.store.snapshot()
        f.store.register(latest, context(f))

        assertEquals(listOf("coordination-a", "coordination-z"), detached.map { it.binding.coordination.id.value })
        assertEquals(
            listOf("coordination-a", "coordination-b", "coordination-z"),
            f.store.snapshot().map { it.binding.coordination.id.value }
        )
    }

    @Test
    fun concurrent_same_coordination_registration_has_exactly_one_winner() {
        val f = fixture()
        val results = ConcurrentLinkedQueue<AgentCoordinationWorkBindingRegistrationResult>()
        val workers = List(20) { index ->
            thread(start = false) {
                results += f.store.register(
                    binding(suffix = "worker-$index"),
                    context(f, "concurrentBindCoordinationWork")
                )
            }
        }

        workers.forEach { it.start() }
        workers.forEach { it.join() }

        assertEquals(1, results.count { it is AgentCoordinationWorkBindingRegistrationResult.Registered })
        assertEquals(19, results.count { it is AgentCoordinationWorkBindingRegistrationResult.Rejected })
        assertEquals(1, f.store.snapshot().size)
    }

    @Test
    fun binding_api_contains_no_permission_scheduler_authority_or_execution_semantics() {
        val forbidden = setOf(
            "authority", "authorize", "permission", "capability", "execution", "execute",
            "executor", "scheduler", "schedule", "fanout", "vote", "consensus", "tool", "grant"
        )
        val types = listOf(
            ExactAgentCoordinationReference::class.java,
            AgentCoordinationWorkAssignment::class.java,
            AgentCoordinationWorkBinding::class.java,
            AgentCoordinationWorkBindingSnapshot::class.java
        )
        types.forEach { type ->
            assertFalse(type.methods.any { method ->
                forbidden.any { token -> method.name.lowercase().contains(token) }
            })
        }
    }
}
