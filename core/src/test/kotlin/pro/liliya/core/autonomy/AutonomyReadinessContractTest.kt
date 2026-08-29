package pro.liliya.core.autonomy

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.reflection.ReflectionGeneration
import pro.liliya.core.reflection.ReflectionRecordId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AutonomyReadinessContractTest {
    private fun fixture(): Pair<InMemoryLogWriter, AutonomyComposition> {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "autonomy-readiness-${sequence.incrementAndGet()}" }
        )
        return logs to AutonomyComposition(foundation)
    }

    private fun proposal(
        id: String,
        createdAt: String,
        origin: AutonomyOrigin = AutonomyOrigin.Reflection(
            recordId = ReflectionRecordId("reflection-1"),
            generation = ReflectionGeneration(9)
        )
    ) = AutonomyProposal(
        id = AutonomyProposalId(id),
        origin = origin,
        objective = "private objective for $id",
        triggerDescription = "private trigger for $id",
        priority = AutonomyPriority.HIGH,
        budget = AutonomyBudget(2),
        createdAt = Instant.parse(createdAt)
    )

    @Test
    fun ownership_remove_is_one_shot_and_repeated_remove_fails_closed() {
        val (_, composition) = fixture()
        val ownership = assertIs<AutonomyInstallResult.Installed>(
            composition.install(proposal("autonomy-1", "2026-08-29T15:00:00Z"))
        ).ownership

        assertTrue(ownership.remove())
        assertFalse(ownership.remove())
        assertFalse(composition.contains(ownership.proposal.id))
    }

    @Test
    fun snapshot_results_are_detached_list_views_of_store_state() {
        val (_, composition) = fixture()
        val a = proposal("autonomy-a", "2026-08-29T15:00:00Z")
        val b = proposal("autonomy-b", "2026-08-29T15:01:00Z")
        composition.install(a)

        val earlier = composition.snapshot()
        composition.install(b)

        assertEquals(listOf(a), earlier)
        assertEquals(listOf(a, b), composition.snapshot())
    }

    @Test
    fun exact_reflection_provenance_survives_as_data_only() {
        val (_, composition) = fixture()
        val origin = AutonomyOrigin.Reflection(
            recordId = ReflectionRecordId("reflection-exact"),
            generation = ReflectionGeneration(17)
        )
        val value = proposal("autonomy-1", "2026-08-29T15:00:00Z", origin)

        composition.install(value)

        assertEquals(origin, composition.find(value.id)?.origin)
    }

    @Test
    fun lifecycle_observability_contains_no_decision_authority_execution_scheduler_or_agent_semantics() {
        val (logs, composition) = fixture()
        val value = proposal("autonomy-1", "2026-08-29T15:00:00Z")
        val ownership = assertIs<AutonomyInstallResult.Installed>(composition.install(value)).ownership
        ownership.remove()

        val forbidden = setOf(
            "decision", "approved", "approval", "authority", "authorized", "capability",
            "permission", "execution", "execute", "executed", "executor", "scheduled",
            "scheduler", "agent", "truth", "confidence", "trusted"
        )

        assertFalse(logs.snapshot().any { event ->
            event.metadata.keys.any { key ->
                forbidden.any { token -> key.lowercase().contains(token) }
            }
        })
    }
}
