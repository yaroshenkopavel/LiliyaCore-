package pro.liliya.core.agent

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pro.liliya.core.autonomy.AutonomyAttemptReference
import pro.liliya.core.autonomy.AutonomyGeneration
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class AgentCoordinationAttemptBindingStoreContractTest {
    private data class Fixture(
        val foundation: FoundationComposition,
        val store: AgentCoordinationAttemptBindingStore
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "coord-attempt-binding-${sequence.incrementAndGet()}" }
        )
        return Fixture(foundation, AgentCoordinationAttemptBindingStore(foundation.observability))
    }

    private fun participant(id: String, generation: Long = 1) =
        ExactAgentReference(AgentId(id), AgentGeneration(generation))

    private fun attempt(id: String, generation: Long = 1, number: Int = 1) =
        AutonomyAttemptReference(
            proposalId = AutonomyProposalId(id),
            proposalGeneration = AutonomyGeneration(generation),
            attemptNumber = number
        )

    private fun binding(
        coordinationId: String = "coordination-1",
        coordinationGeneration: Long = 1,
        attemptSuffix: String = ""
    ) = AgentCoordinationAttemptBinding(
        coordination = ExactAgentCoordinationReference(
            AgentCoordinationId(coordinationId),
            AgentCoordinationGeneration(coordinationGeneration)
        ),
        assignments = listOf(
            AgentCoordinationAttemptAssignment(
                participant("agent-a"),
                attempt("autonomy-a$attemptSuffix")
            ),
            AgentCoordinationAttemptAssignment(
                participant("agent-b"),
                attempt("autonomy-b$attemptSuffix")
            )
        )
    )

    private fun context(f: Fixture, operation: String = "bindCoordinationAttempts") =
        f.foundation.rootContext(operation = operation, component = "AgentCoordination")

    @Test
    fun exact_binding_registers_secondary_attempt_index_and_removes_atomically() {
        val f = fixture()
        val value = binding()
        val registration = assertIs<AgentCoordinationAttemptBindingRegistrationResult.Registered>(
            f.store.register(value, context(f))
        ).registration

        assertTrue(registration.generation.value > 0)
        assertEquals(value, f.store.find(value.coordination))
        value.assignments.forEach { assignment ->
            assertEquals(value, f.store.findByAttempt(assignment.attempt))
        }

        assertTrue(registration.remove(context(f, "unbindCoordinationAttempts")))
        assertNull(f.store.find(value.coordination))
        value.assignments.forEach { assignment ->
            assertNull(f.store.findByAttempt(assignment.attempt))
        }
        assertFalse(registration.remove(context(f, "unbindAgain")))
    }

    @Test
    fun duplicate_exact_coordination_rejects_without_replacement() {
        val f = fixture()
        val first = binding(attemptSuffix = "-first")
        val second = binding(attemptSuffix = "-second")
        val firstRegistration = assertIs<AgentCoordinationAttemptBindingRegistrationResult.Registered>(
            f.store.register(first, context(f))
        ).registration

        assertIs<AgentCoordinationAttemptBindingRegistrationResult.Rejected>(
            f.store.register(second, context(f))
        )
        assertEquals(first, f.store.find(first.coordination))
        assertEquals(firstRegistration.generation, f.store.inspect(first.coordination)?.generation)
    }

    @Test
    fun exact_attempt_cannot_be_reused_by_another_coordination() {
        val f = fixture()
        val first = binding(coordinationId = "coordination-a")
        val reusedAttempt = first.assignments.first().attempt
        val second = AgentCoordinationAttemptBinding(
            coordination = ExactAgentCoordinationReference(
                AgentCoordinationId("coordination-b"),
                AgentCoordinationGeneration(1)
            ),
            assignments = listOf(
                AgentCoordinationAttemptAssignment(participant("agent-c"), reusedAttempt),
                AgentCoordinationAttemptAssignment(participant("agent-d"), attempt("autonomy-d"))
            )
        )

        assertIs<AgentCoordinationAttemptBindingRegistrationResult.Registered>(
            f.store.register(first, context(f))
        )
        assertIs<AgentCoordinationAttemptBindingRegistrationResult.Rejected>(
            f.store.register(second, context(f))
        )
        assertNull(f.store.find(second.coordination))
    }

    @Test
    fun stale_registration_cannot_remove_replacement() {
        val f = fixture()
        val first = binding()
        val stale = assertIs<AgentCoordinationAttemptBindingRegistrationResult.Registered>(
            f.store.register(first, context(f))
        ).registration
        assertTrue(stale.remove(context(f, "removeFirst")))

        val replacementValue = binding(attemptSuffix = "-replacement")
        val replacement = assertIs<AgentCoordinationAttemptBindingRegistrationResult.Registered>(
            f.store.register(replacementValue, context(f))
        ).registration

        assertNotEquals(stale.generation, replacement.generation)
        assertFalse(stale.remove(context(f, "staleRemove")))
        assertEquals(replacementValue, f.store.find(replacementValue.coordination))
    }

    @Test
    fun snapshots_are_deterministic_detached_views() {
        val f = fixture()
        val later = binding(coordinationId = "coord-z", attemptSuffix = "-z")
        val earlier = binding(coordinationId = "coord-a", attemptSuffix = "-a")
        f.store.register(later, context(f))
        val detached = f.store.snapshot()
        f.store.register(earlier, context(f))

        assertEquals(listOf(later), detached.map { it.binding })
        assertEquals(listOf(earlier, later), f.store.snapshot().map { it.binding })
    }

    @Test
    fun concurrent_same_coordination_registration_has_exactly_one_winner() {
        val f = fixture()
        val results = ConcurrentLinkedQueue<AgentCoordinationAttemptBindingRegistrationResult>()
        val workers = List(20) { index ->
            thread(start = false) {
                results += f.store.register(
                    binding(attemptSuffix = "-$index"),
                    context(f, "concurrentBindCoordinationAttempts")
                )
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }

        assertEquals(1, results.count { it is AgentCoordinationAttemptBindingRegistrationResult.Registered })
        assertEquals(19, results.count { it is AgentCoordinationAttemptBindingRegistrationResult.Rejected })
        assertEquals(1, f.store.snapshot().size)
    }
}
