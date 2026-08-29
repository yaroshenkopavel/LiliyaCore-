package pro.liliya.core.planning

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanningProposalStoreContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val store: PlanningProposalStore
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "planning-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, foundation, PlanningProposalStore(foundation.observability))
    }

    private fun proposal(
        id: String = "plan-1",
        goal: String = "reach a declared goal",
        steps: List<PlanningStep> = listOf(
            PlanningStep(PlanningStepId("step-1"), "first private step"),
            PlanningStep(PlanningStepId("step-2"), "second private step")
        ),
        createdAt: Instant = Instant.parse("2026-08-29T11:30:00Z")
    ) = PlanningProposal(
        id = PlanningProposalId(id),
        origin = PlanningOrigin(
            sourceId = PlanningSourceId("caller"),
            sourceReference = PlanningSourceReference("contract")
        ),
        goal = goal,
        steps = steps,
        createdAt = createdAt
    )

    private fun Fixture.context(operation: String) =
        foundation.rootContext(operation = operation, component = "Planning")

    @Test
    fun register_read_and_remove_use_exact_ownership() {
        val f = fixture()
        val p = proposal()
        val registration = assertIs<PlanningProposalRegistrationResult.Registered>(
            f.store.register(p, f.context("register"))
        ).registration

        assertEquals(p, f.store.find(p.id))
        assertEquals(registration.generation, f.store.inspect(p.id)?.generation)
        assertTrue(registration.remove(f.context("remove")))
        assertNull(f.store.find(p.id))
    }

    @Test
    fun duplicate_id_is_rejected_without_replacement() {
        val f = fixture()
        val first = proposal(goal = "first goal")
        val second = proposal(goal = "second goal")

        assertIs<PlanningProposalRegistrationResult.Registered>(f.store.register(first, f.context("first")))
        assertIs<PlanningProposalRegistrationResult.Rejected>(f.store.register(second, f.context("second")))
        assertEquals(first, f.store.find(first.id))
    }

    @Test
    fun stale_registration_cannot_remove_replacement() {
        val f = fixture()
        val stale = assertIs<PlanningProposalRegistrationResult.Registered>(
            f.store.register(proposal(), f.context("stale"))
        ).registration
        assertTrue(stale.remove(f.context("remove-stale")))

        val replacement = proposal(goal = "replacement goal")
        val current = assertIs<PlanningProposalRegistrationResult.Registered>(
            f.store.register(replacement, f.context("replacement"))
        ).registration

        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove(f.context("stale-again")))
        assertEquals(replacement, f.store.find(replacement.id))
    }

    @Test
    fun caller_step_list_is_defensively_copied() {
        val mutable = mutableListOf(
            PlanningStep(PlanningStepId("step-1"), "private step")
        )
        val p = proposal(steps = mutable)

        mutable += PlanningStep(PlanningStepId("step-2"), "late mutation")

        assertEquals(listOf("step-1"), p.steps.map { it.id.value })
    }

    @Test
    fun duplicate_step_ids_are_rejected() {
        assertFailsWith<IllegalArgumentException> {
            proposal(
                steps = listOf(
                    PlanningStep(PlanningStepId("same"), "one"),
                    PlanningStep(PlanningStepId("same"), "two")
                )
            )
        }
    }

    @Test
    fun goal_and_step_descriptions_are_redacted_from_rendering_and_observability() {
        val f = fixture()
        val secretGoal = "secret-planning-goal"
        val secretStep = "secret-planning-step"
        val p = proposal(
            goal = secretGoal,
            steps = listOf(PlanningStep(PlanningStepId("step-secret"), secretStep))
        )

        assertFalse(p.toString().contains(secretGoal))
        assertFalse(p.toString().contains(secretStep))
        assertFalse(p.steps.single().toString().contains(secretStep))
        assertIs<PlanningProposalRegistrationResult.Registered>(
            f.store.register(p, f.context("privacy"))
        )
        assertFalse(f.logs.snapshot().any { event ->
            event.metadata.values.any { value -> value == secretGoal || value == secretStep }
        })
        assertFalse(f.logs.snapshot().any { event ->
            event.metadata.keys.any { key ->
                key.contains("authority", ignoreCase = true) ||
                    key.contains("execution", ignoreCase = true) ||
                    key.contains("approved", ignoreCase = true) ||
                    key.contains("decision", ignoreCase = true)
            }
        })
    }

    @Test
    fun snapshot_is_deterministic_by_created_at_then_id() {
        val f = fixture()
        val earlier = Instant.parse("2026-08-29T11:30:00Z")
        val later = Instant.parse("2026-08-29T11:31:00Z")

        listOf(
            proposal(id = "b", createdAt = earlier),
            proposal(id = "c", createdAt = later),
            proposal(id = "a", createdAt = earlier)
        ).forEachIndexed { index, item ->
            assertIs<PlanningProposalRegistrationResult.Registered>(
                f.store.register(item, f.context("snapshot-$index"))
            )
        }

        assertEquals(listOf("a", "b", "c"), f.store.snapshot().map { it.id.value })
    }

    @Test
    fun concurrent_same_id_registration_has_exactly_one_winner() {
        val f = fixture()
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val winners = AtomicInteger(0)

        repeat(threads) { index ->
            pool.execute {
                try {
                    ready.countDown()
                    start.await()
                    val result = f.store.register(
                        proposal(goal = "goal-$index"),
                        f.context("concurrent-$index")
                    )
                    if (result is PlanningProposalRegistrationResult.Registered) {
                        winners.incrementAndGet()
                    }
                } finally {
                    done.countDown()
                }
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals(1, winners.get())
        assertTrue(f.store.contains(PlanningProposalId("plan-1")))
    }
}
