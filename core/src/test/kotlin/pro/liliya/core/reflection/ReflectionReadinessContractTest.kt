package pro.liliya.core.reflection

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReflectionReadinessContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val composition: ReflectionComposition
    )

    private fun fixture(prefix: String): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "$prefix-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, ReflectionComposition(foundation))
    }

    private fun record(
        id: String = "reflection-readiness",
        content: String = "caller supplied reflection",
        createdAt: Instant = Instant.parse("2026-08-29T04:00:00Z"),
        origin: ReflectionOrigin = ReflectionOrigin.Declared(
            ReflectionSourceId("readiness-caller"),
            ReflectionSourceReference("readiness-contract")
        )
    ) = ReflectionRecord(
        id = ReflectionRecordId(id),
        origin = origin,
        content = content,
        createdAt = createdAt
    )

    @Test
    fun created_at_is_caller_supplied_and_preserved_without_runtime_replacement() {
        val f = fixture("created-at")
        val supplied = Instant.parse("2001-02-03T04:05:06Z")
        val r = record(createdAt = supplied)

        assertIs<ReflectionInstallResult.Installed>(f.composition.install(r))

        assertEquals(supplied, f.composition.find(r.id)?.createdAt)
        assertTrue(f.logs.snapshot().any { event -> event.metadata["createdAt"] == supplied.toString() })
    }

    @Test
    fun independent_compositions_do_not_share_records_even_with_same_record_id() {
        val first = fixture("first")
        val second = fixture("second")
        val firstRecord = record(content = "first composition")
        val secondRecord = record(content = "second composition")

        assertIs<ReflectionInstallResult.Installed>(first.composition.install(firstRecord))
        assertNull(second.composition.find(firstRecord.id))
        assertIs<ReflectionInstallResult.Installed>(second.composition.install(secondRecord))

        assertEquals(firstRecord, first.composition.find(firstRecord.id))
        assertEquals(secondRecord, second.composition.find(secondRecord.id))
    }

    @Test
    fun equal_numeric_generations_across_compositions_do_not_create_shared_ownership() {
        val first = fixture("generation-first")
        val second = fixture("generation-second")
        val firstRecord = record(content = "first")
        val secondRecord = record(content = "second")

        val firstOwnership = assertIs<ReflectionInstallResult.Installed>(
            first.composition.install(firstRecord)
        ).ownership
        val secondOwnership = assertIs<ReflectionInstallResult.Installed>(
            second.composition.install(secondRecord)
        ).ownership

        assertEquals(firstOwnership.generation.value, secondOwnership.generation.value)
        assertTrue(firstOwnership.remove())
        assertNull(first.composition.find(firstRecord.id))
        assertEquals(secondRecord, second.composition.find(secondRecord.id))
        assertTrue(secondOwnership.remove())
    }

    @Test
    fun structural_origin_does_not_create_implicit_learning_trust_authority_or_execution_effects() {
        val f = fixture("structural")
        val secret = "reflection-content-is-data-only"
        val r = record(
            content = secret,
            origin = ReflectionOrigin.Knowledge(
                KnowledgeItemId("knowledge-not-installed"),
                KnowledgeGeneration(999L)
            )
        )

        assertIs<ReflectionInstallResult.Installed>(f.composition.install(r))
        assertEquals(r, f.composition.find(r.id))

        val events = f.logs.snapshot()
        assertTrue(events.any { event -> event.metadata["knowledgeItemId"] == "knowledge-not-installed" })
        assertTrue(events.any { event -> event.metadata["knowledgeGeneration"] == "999" })
        assertFalse(events.any { event -> event.metadata.values.any { value -> value == secret } })
        assertFalse(events.any { event ->
            event.metadata.keys.any { key ->
                key.contains("learning", ignoreCase = true) ||
                    key.contains("trust", ignoreCase = true) ||
                    key.contains("authority", ignoreCase = true) ||
                    key.contains("execution", ignoreCase = true) ||
                    key.contains("truth", ignoreCase = true) ||
                    key.contains("confidence", ignoreCase = true) ||
                    key.contains("personality", ignoreCase = true)
            }
        })
    }

    @Test
    fun reflection_string_remains_redacted_at_readiness_boundary() {
        val secret = "never-render-this-reflection"
        val r = record(content = secret)

        assertFalse(r.toString().contains(secret))
        assertTrue(r.toString().contains("<redacted>"))
    }
}
