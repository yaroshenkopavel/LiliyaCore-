package pro.liliya.core.learning

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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearningReadinessContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val composition: LearningComposition
    )

    private fun fixture(prefix: String): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "$prefix-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, LearningComposition(foundation))
    }

    private fun candidate(
        id: String = "candidate-1",
        proposal: String = "candidate proposal",
        createdAt: Instant = Instant.parse("2026-08-29T08:00:00Z"),
        origin: LearningOrigin = LearningOrigin.Declared(
            LearningSourceId("caller"),
            LearningSourceReference("readiness")
        )
    ) = LearningCandidate(
        id = LearningCandidateId(id),
        origin = origin,
        proposal = proposal,
        createdAt = createdAt
    )

    @Test
    fun created_at_is_caller_supplied_and_preserved_unchanged() {
        val f = fixture("created-at")
        val createdAt = Instant.parse("2001-02-03T04:05:06Z")
        val c = candidate(createdAt = createdAt)

        assertIs<LearningInstallResult.Installed>(f.composition.install(c))

        assertEquals(createdAt, f.composition.find(c.id)?.createdAt)
        assertEquals(createdAt, f.composition.inspect(c.id)?.candidate?.createdAt)
    }

    @Test
    fun independent_compositions_isolate_same_candidate_id() {
        val first = fixture("first")
        val second = fixture("second")
        val firstCandidate = candidate(proposal = "first proposal")
        val secondCandidate = candidate(proposal = "second proposal")

        val firstOwnership = assertIs<LearningInstallResult.Installed>(
            first.composition.install(firstCandidate)
        ).ownership
        val secondOwnership = assertIs<LearningInstallResult.Installed>(
            second.composition.install(secondCandidate)
        ).ownership

        assertEquals(firstCandidate, first.composition.find(firstCandidate.id))
        assertEquals(secondCandidate, second.composition.find(secondCandidate.id))
        assertTrue(firstOwnership.remove())
        assertNull(first.composition.find(firstCandidate.id))
        assertEquals(secondCandidate, second.composition.find(secondCandidate.id))
        assertTrue(secondOwnership.remove())
    }

    @Test
    fun equal_numeric_generations_across_compositions_are_local_not_shared_ownership() {
        val first = fixture("generation-first")
        val second = fixture("generation-second")
        val c = candidate()

        val firstOwnership = assertIs<LearningInstallResult.Installed>(first.composition.install(c)).ownership
        val secondOwnership = assertIs<LearningInstallResult.Installed>(second.composition.install(c)).ownership

        assertEquals(firstOwnership.generation.value, secondOwnership.generation.value)
        assertTrue(firstOwnership.remove())
        assertNotNull(second.composition.find(c.id))
        assertTrue(secondOwnership.remove())
    }

    @Test
    fun reflection_origin_remains_structural_without_hidden_lookup() {
        val f = fixture("structural")
        val c = candidate(
            origin = LearningOrigin.Reflection(
                recordId = ReflectionRecordId("reflection-does-not-exist"),
                generation = ReflectionGeneration(999L)
            )
        )

        assertIs<LearningInstallResult.Installed>(f.composition.install(c))
        assertEquals(c, f.composition.find(c.id))
    }

    @Test
    fun candidate_presence_creates_no_implicit_acceptance_application_or_downstream_semantics() {
        val f = fixture("semantic-boundary")
        val secret = "proposal-must-remain-only-a-candidate"
        val c = candidate(proposal = secret)

        assertIs<LearningInstallResult.Installed>(f.composition.install(c))

        val events = f.logs.snapshot()
        assertFalse(events.any { event -> event.metadata.values.any { value -> value == secret } })
        assertFalse(events.any { event ->
            event.metadata.keys.any { key ->
                key.contains("accepted", ignoreCase = true) ||
                    key.contains("approved", ignoreCase = true) ||
                    key.contains("applied", ignoreCase = true) ||
                    key.contains("consolidat", ignoreCase = true) ||
                    key.contains("memory", ignoreCase = true) ||
                    key.contains("knowledge", ignoreCase = true) ||
                    key.contains("personality", ignoreCase = true) ||
                    key.contains("self", ignoreCase = true) ||
                    key.contains("truth", ignoreCase = true) ||
                    key.contains("confidence", ignoreCase = true) ||
                    key.contains("trust", ignoreCase = true) ||
                    key.contains("authority", ignoreCase = true) ||
                    key.contains("execution", ignoreCase = true)
            }
        })
        assertFalse(c.toString().contains(secret))
        assertTrue(c.toString().contains("<redacted>"))
    }
}
