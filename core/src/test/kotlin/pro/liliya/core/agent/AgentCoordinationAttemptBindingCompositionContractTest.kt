package pro.liliya.core.agent

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

class AgentCoordinationAttemptBindingCompositionContractTest {
    private fun composition(prefix: String): Pair<AgentCoordinationAttemptBindingComposition, InMemoryLogWriter> {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "$prefix-${sequence.incrementAndGet()}" }
        )
        return AgentCoordinationAttemptBindingComposition(foundation) to logs
    }

    private fun attempt(id: String) = AutonomyAttemptReference(
        AutonomyProposalId(id), AutonomyGeneration(1), 1
    )

    private fun binding() = AgentCoordinationAttemptBinding(
        coordination = ExactAgentCoordinationReference(
            AgentCoordinationId("coordination-1"), AgentCoordinationGeneration(1)
        ),
        assignments = listOf(
            AgentCoordinationAttemptAssignment(
                ExactAgentReference(AgentId("agent-a"), AgentGeneration(1)),
                attempt("autonomy-a")
            ),
            AgentCoordinationAttemptAssignment(
                ExactAgentReference(AgentId("agent-b"), AgentGeneration(1)),
                attempt("autonomy-b")
            )
        )
    )

    @Test
    fun install_exposes_exact_ownership_and_secondary_lookup_then_removes_once() {
        val (composition, _) = composition("coord-attempt-owner")
        val value = binding()
        val ownership = assertIs<AgentCoordinationAttemptBindingInstallResult.Installed>(
            composition.install(value)
        ).ownership

        assertEquals(value, ownership.binding)
        assertTrue(ownership.generation.value > 0)
        assertEquals(value, composition.find(value.coordination))
        value.assignments.forEach { assignment ->
            assertEquals(value, composition.findByAttempt(assignment.attempt))
        }

        assertTrue(ownership.remove())
        assertFalse(ownership.remove())
        assertNull(composition.find(value.coordination))
    }

    @Test
    fun same_exact_binding_is_isolated_between_compositions() {
        val (first, _) = composition("coord-attempt-first")
        val (second, _) = composition("coord-attempt-second")
        val value = binding()

        val firstOwnership = assertIs<AgentCoordinationAttemptBindingInstallResult.Installed>(
            first.install(value)
        ).ownership
        assertIs<AgentCoordinationAttemptBindingInstallResult.Installed>(second.install(value))

        assertTrue(firstOwnership.remove())
        assertNull(first.find(value.coordination))
        assertEquals(value, second.find(value.coordination))
    }

    @Test
    fun snapshots_are_detached_and_install_remove_correlation_is_root_to_child() {
        val (composition, logs) = composition("coord-attempt-correlation")
        val ownership = assertIs<AgentCoordinationAttemptBindingInstallResult.Installed>(
            composition.install(binding())
        ).ownership
        val before = composition.snapshot()
        assertTrue(ownership.remove())

        assertEquals(1, before.size)
        assertTrue(composition.snapshot().isEmpty())

        val installed = logs.snapshot().first { it.marker == "AGENT_COORDINATION_ATTEMPTS_BOUND" }
        val removed = logs.snapshot().first { it.marker == "AGENT_COORDINATION_ATTEMPTS_UNBOUND" }
        assertEquals(installed.context.correlationId, removed.context.parentCorrelationId)
    }
}
