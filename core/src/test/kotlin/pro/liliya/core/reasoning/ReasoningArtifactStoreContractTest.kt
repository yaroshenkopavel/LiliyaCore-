package pro.liliya.core.reasoning

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

class ReasoningArtifactStoreContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val foundation: FoundationComposition,
        val store: ReasoningArtifactStore
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "reasoning-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, foundation, ReasoningArtifactStore(foundation.observability))
    }

    private fun artifact(
        id: String = "reason-1",
        analysis: String = "private analysis",
        conclusion: String = "private conclusion",
        premises: List<ReasoningPremise> = listOf(
            ReasoningPremise(ReasoningPremiseId("premise-1"), "private premise one"),
            ReasoningPremise(ReasoningPremiseId("premise-2"), "private premise two")
        ),
        createdAt: Instant = Instant.parse("2026-08-29T12:30:00Z")
    ) = ReasoningArtifact(
        id = ReasoningArtifactId(id),
        origin = ReasoningOrigin(
            ReasoningSourceId("caller"),
            ReasoningSourceReference("contract")
        ),
        premises = premises,
        analysis = analysis,
        conclusion = conclusion,
        createdAt = createdAt
    )

    private fun Fixture.context(operation: String) =
        foundation.rootContext(operation = operation, component = "Reasoning")

    @Test
    fun register_read_and_remove_use_exact_ownership() {
        val f = fixture()
        val a = artifact()
        val registration = assertIs<ReasoningArtifactRegistrationResult.Registered>(
            f.store.register(a, f.context("register"))
        ).registration

        assertEquals(a, f.store.find(a.id))
        assertEquals(registration.generation, f.store.inspect(a.id)?.generation)
        assertTrue(registration.remove(f.context("remove")))
        assertNull(f.store.find(a.id))
    }

    @Test
    fun duplicate_id_rejects_without_replacement() {
        val f = fixture()
        val first = artifact(conclusion = "first private conclusion")
        val second = artifact(conclusion = "second private conclusion")

        assertIs<ReasoningArtifactRegistrationResult.Registered>(f.store.register(first, f.context("first")))
        assertIs<ReasoningArtifactRegistrationResult.Rejected>(f.store.register(second, f.context("second")))
        assertEquals(first, f.store.find(first.id))
    }

    @Test
    fun stale_registration_cannot_remove_replacement() {
        val f = fixture()
        val stale = assertIs<ReasoningArtifactRegistrationResult.Registered>(
            f.store.register(artifact(), f.context("stale"))
        ).registration
        assertTrue(stale.remove(f.context("remove-stale")))

        val replacement = artifact(conclusion = "replacement private conclusion")
        val current = assertIs<ReasoningArtifactRegistrationResult.Registered>(
            f.store.register(replacement, f.context("replacement"))
        ).registration

        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove(f.context("stale-again")))
        assertEquals(replacement, f.store.find(replacement.id))
    }

    @Test
    fun caller_premise_list_is_defensively_copied() {
        val mutable = mutableListOf(
            ReasoningPremise(ReasoningPremiseId("premise-1"), "private premise")
        )
        val a = artifact(premises = mutable)
        mutable += ReasoningPremise(ReasoningPremiseId("premise-2"), "late mutation")
        assertEquals(listOf("premise-1"), a.premises.map { it.id.value })
    }

    @Test
    fun duplicate_premise_ids_are_rejected() {
        assertFailsWith<IllegalArgumentException> {
            artifact(
                premises = listOf(
                    ReasoningPremise(ReasoningPremiseId("same"), "one"),
                    ReasoningPremise(ReasoningPremiseId("same"), "two")
                )
            )
        }
    }

    @Test
    fun premise_analysis_and_conclusion_are_redacted_from_rendering_and_observability() {
        val f = fixture()
        val secretPremise = "never-log-reasoning-premise"
        val secretAnalysis = "never-log-reasoning-analysis"
        val secretConclusion = "never-log-reasoning-conclusion"
        val a = artifact(
            premises = listOf(ReasoningPremise(ReasoningPremiseId("private"), secretPremise)),
            analysis = secretAnalysis,
            conclusion = secretConclusion
        )

        assertFalse(a.toString().contains(secretPremise))
        assertFalse(a.toString().contains(secretAnalysis))
        assertFalse(a.toString().contains(secretConclusion))
        assertFalse(a.premises.single().toString().contains(secretPremise))
        assertIs<ReasoningArtifactRegistrationResult.Registered>(f.store.register(a, f.context("privacy")))
        assertFalse(f.logs.snapshot().any { event ->
            event.metadata.values.any { value ->
                value == secretPremise || value == secretAnalysis || value == secretConclusion
            }
        })
    }

    @Test
    fun snapshot_order_is_deterministic() {
        val f = fixture()
        val later = artifact(id = "b", createdAt = Instant.parse("2026-08-29T12:31:00Z"))
        val firstB = artifact(id = "b-first", createdAt = Instant.parse("2026-08-29T12:30:00Z"))
        val firstA = artifact(id = "a-first", createdAt = Instant.parse("2026-08-29T12:30:00Z"))

        assertIs<ReasoningArtifactRegistrationResult.Registered>(f.store.register(later, f.context("later")))
        assertIs<ReasoningArtifactRegistrationResult.Registered>(f.store.register(firstB, f.context("first-b")))
        assertIs<ReasoningArtifactRegistrationResult.Registered>(f.store.register(firstA, f.context("first-a")))

        assertEquals(listOf("a-first", "b-first", "b"), f.store.snapshot().map { it.id.value })
    }

    @Test
    fun concurrent_same_id_has_exactly_one_winner() {
        val f = fixture()
        val workers = 8
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val futures = (0 until workers).map { index ->
                pool.submit<ReasoningArtifactRegistrationResult> {
                    ready.countDown()
                    start.await()
                    f.store.register(
                        artifact(conclusion = "private conclusion $index"),
                        f.context("concurrent-$index")
                    )
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is ReasoningArtifactRegistrationResult.Registered })
            assertEquals(workers - 1, results.count { it is ReasoningArtifactRegistrationResult.Rejected })
            assertTrue(f.store.contains(ReasoningArtifactId("reason-1")))
        } finally {
            pool.shutdownNow()
        }
    }
}
