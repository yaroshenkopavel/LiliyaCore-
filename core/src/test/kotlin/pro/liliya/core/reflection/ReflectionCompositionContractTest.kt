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
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReflectionCompositionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val composition: ReflectionComposition
    )

    private fun fixture(prefix: String = "reflection-composition"): Fixture {
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
        id: String = "reflection-1",
        content: String = "caller reflection",
        origin: ReflectionOrigin = ReflectionOrigin.Declared(
            ReflectionSourceId("caller"),
            ReflectionSourceReference("composition-contract")
        )
    ) = ReflectionRecord(
        id = ReflectionRecordId(id),
        origin = origin,
        content = content,
        createdAt = Instant.parse("2026-08-29T03:00:00Z")
    )

    @Test
    fun install_read_and_remove_are_owned_by_composition() {
        val f = fixture()
        val r = record()
        val installed = assertIs<ReflectionInstallResult.Installed>(f.composition.install(r))

        assertEquals(r, f.composition.find(r.id))
        assertEquals(installed.ownership.generation, f.composition.inspect(r.id)?.generation)
        assertTrue(installed.ownership.remove())
        assertNull(f.composition.find(r.id))
    }

    @Test
    fun duplicate_record_is_rejected_without_replacement() {
        val f = fixture()
        val first = record(content = "first")
        val second = record(content = "second")

        assertIs<ReflectionInstallResult.Installed>(f.composition.install(first))
        assertIs<ReflectionInstallResult.Rejected>(f.composition.install(second))
        assertEquals(first, f.composition.find(first.id))
    }

    @Test
    fun stale_ownership_cannot_remove_replacement_record() {
        val f = fixture()
        val stale = assertIs<ReflectionInstallResult.Installed>(f.composition.install(record())).ownership
        assertTrue(stale.remove())

        val replacement = record(content = "replacement")
        val current = assertIs<ReflectionInstallResult.Installed>(f.composition.install(replacement)).ownership
        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove())
        assertEquals(replacement, f.composition.find(replacement.id))
    }

    @Test
    fun install_and_remove_use_fresh_foundation_contexts() {
        val f = fixture("fresh")
        val ownership = assertIs<ReflectionInstallResult.Installed>(f.composition.install(record())).ownership
        assertTrue(ownership.remove())

        val correlations = f.logs.snapshot().map { event -> event.context.correlationId }.distinct()
        assertTrue(correlations.size >= 2)
    }

    @Test
    fun origins_remain_structural_and_content_stays_out_of_lifecycle_metadata() {
        val f = fixture()
        val secret = "private-reflection-content"
        val memoryRecord = record(
            id = "memory-origin",
            content = secret,
            origin = ReflectionOrigin.Memory(MemoryRecordId("missing-memory"), MemoryGeneration(999L))
        )
        val knowledgeRecord = record(
            id = "knowledge-origin",
            content = secret,
            origin = ReflectionOrigin.Knowledge(KnowledgeItemId("missing-knowledge"), KnowledgeGeneration(999L))
        )

        assertIs<ReflectionInstallResult.Installed>(f.composition.install(memoryRecord))
        assertIs<ReflectionInstallResult.Installed>(f.composition.install(knowledgeRecord))

        val events = f.logs.snapshot()
        assertFalse(events.flatMap { event -> event.metadata.values }.any { value -> value == secret })
        assertTrue(events.any { event -> event.metadata["memoryGeneration"] == "999" })
        assertTrue(events.any { event -> event.metadata["knowledgeGeneration"] == "999" })
        assertFalse(events.flatMap { event -> event.metadata.keys }.any { key ->
            key.contains("truth", ignoreCase = true) ||
                key.contains("confidence", ignoreCase = true) ||
                key.contains("learning", ignoreCase = true) ||
                key.contains("personality", ignoreCase = true) ||
                key.contains("authority", ignoreCase = true) ||
                key.contains("execution", ignoreCase = true)
        })
    }

    @Test
    fun public_api_does_not_expose_raw_store_or_registration() {
        val exposedTypes = ReflectionComposition::class.java.methods.flatMap { method ->
            listOf(method.returnType) + method.parameterTypes.toList()
        }

        assertFalse(exposedTypes.any { it.name.endsWith("ReflectionRecordStore") })
        assertFalse(exposedTypes.any { it.name.endsWith("ReflectionRecordRegistration") })
    }
}
