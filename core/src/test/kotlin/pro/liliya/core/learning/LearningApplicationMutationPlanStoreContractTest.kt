package pro.liliya.core.learning

import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearningApplicationMutationPlanStoreContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val store: LearningApplicationMutationPlanStore
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "mutation-plan-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, foundation, LearningApplicationMutationPlanStore(foundation.observability))
    }

    private fun plan(
        id: String = "plan-1",
        applicationId: String = "application-1",
        applicationGeneration: Long = 7L,
        destination: LearningApplicationMutationDestination =
            LearningApplicationMutationDestination.Memory(MemoryRecordId("memory-1")),
        createdAt: Instant = Instant.parse("2026-08-29T00:00:00Z")
    ) = LearningApplicationMutationPlan(
        id = LearningApplicationMutationPlanId(id),
        application = LearningApplicationIntentReference(
            applicationId = LearningApplicationId(applicationId),
            generation = LearningApplicationGeneration(applicationGeneration)
        ),
        destination = destination,
        createdAt = createdAt
    )

    private fun Fixture.context(operation: String) =
        foundation.rootContext(operation = operation, component = "LearningApplicationMutationPlan")

    @Test
    fun register_read_and_remove_use_exact_ownership() {
        val f = fixture()
        val value = plan()
        val registration = assertIs<LearningApplicationMutationPlanRegistrationResult.Registered>(
            f.store.register(value, f.context("register"))
        ).registration

        assertEquals(value, f.store.find(value.id))
        assertEquals(registration.generation, f.store.inspect(value.id)?.generation)
        assertTrue(registration.remove(f.context("remove")))
        assertNull(f.store.find(value.id))
    }

    @Test
    fun duplicate_plan_id_is_rejected_without_replacement() {
        val f = fixture()
        val first = plan()
        val second = plan(
            destination = LearningApplicationMutationDestination.Knowledge(KnowledgeItemId("knowledge-1"))
        )

        assertIs<LearningApplicationMutationPlanRegistrationResult.Registered>(
            f.store.register(first, f.context("first"))
        )
        assertIs<LearningApplicationMutationPlanRegistrationResult.Rejected>(
            f.store.register(second, f.context("second"))
        )
        assertEquals(first, f.store.find(first.id))
    }

    @Test
    fun stale_registration_cannot_remove_replacement() {
        val f = fixture()
        val stale = assertIs<LearningApplicationMutationPlanRegistrationResult.Registered>(
            f.store.register(plan(), f.context("stale"))
        ).registration
        assertTrue(stale.remove(f.context("remove-stale")))

        val replacement = plan(
            destination = LearningApplicationMutationDestination.Knowledge(KnowledgeItemId("knowledge-1"))
        )
        val current = assertIs<LearningApplicationMutationPlanRegistrationResult.Registered>(
            f.store.register(replacement, f.context("replacement"))
        ).registration

        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove(f.context("stale-again")))
        assertEquals(replacement, f.store.find(replacement.id))
    }

    @Test
    fun application_reference_and_destination_are_structural_only_without_authorization_or_mutation() {
        val f = fixture()
        val value = plan(
            applicationId = "missing-application",
            applicationGeneration = 99L,
            destination = LearningApplicationMutationDestination.Knowledge(KnowledgeItemId("missing-knowledge"))
        )

        assertIs<LearningApplicationMutationPlanRegistrationResult.Registered>(
            f.store.register(value, f.context("structural"))
        )
        assertEquals(value, f.store.find(value.id))

        val forbiddenMetadataTokens = listOf(
            "authorized", "authorizationReceipt", "applied", "executed", "executionResult",
            "consolidated", "consolidationResult", "memoryGeneration", "knowledgeGeneration",
            "truth", "confidence", "trust", "capabilityGrant"
        )
        val keys = f.logs.snapshot().flatMap { event -> event.metadata.keys }
        assertFalse(keys.any { key ->
            forbiddenMetadataTokens.any { token -> key.contains(token, ignoreCase = true) }
        })
    }

    @Test
    fun snapshot_is_deterministic_by_created_at_then_id() {
        val f = fixture()
        val earlier = Instant.parse("2026-08-28T23:00:00Z")
        val later = Instant.parse("2026-08-29T00:00:00Z")
        listOf(
            plan(id = "b", createdAt = earlier),
            plan(id = "c", createdAt = later),
            plan(id = "a", createdAt = earlier)
        ).forEachIndexed { index, value ->
            assertIs<LearningApplicationMutationPlanRegistrationResult.Registered>(
                f.store.register(value, f.context("snapshot-$index"))
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
                    val destination = if (index % 2 == 0) {
                        LearningApplicationMutationDestination.Memory(MemoryRecordId("memory-$index"))
                    } else {
                        LearningApplicationMutationDestination.Knowledge(KnowledgeItemId("knowledge-$index"))
                    }
                    if (
                        f.store.register(
                            plan(destination = destination),
                            f.context("concurrent-$index")
                        ) is LearningApplicationMutationPlanRegistrationResult.Registered
                    ) {
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
        assertTrue(f.store.contains(LearningApplicationMutationPlanId("plan-1")))
    }
}
