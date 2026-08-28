package pro.liliya.core.knowledge

import java.lang.reflect.Modifier
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
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

class KnowledgeCompositionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val diagnostics: InMemoryDiagnosticSink,
        val composition: KnowledgeComposition
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(diagnostics),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "knowledge-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, diagnostics, KnowledgeComposition(foundation))
    }

    private fun item(content: String = "offline maps are preferred") = KnowledgeItem(
        id = KnowledgeItemId("knowledge-1"),
        origin = KnowledgeOrigin.Memory(
            recordId = MemoryRecordId("memory-1"),
            generation = MemoryGeneration(4L)
        ),
        content = content,
        createdAt = Instant.parse("2026-08-28T21:00:00Z")
    )

    @Test
    fun create_read_and_remove_are_owned_by_composition() {
        val f = fixture()
        val knowledge = item()
        val ownership = assertIs<KnowledgeCreateResult.Created>(
            f.composition.create(knowledge)
        ).ownership

        assertEquals(knowledge, f.composition.find(knowledge.id))
        assertEquals(ownership.generation, f.composition.inspect(knowledge.id)?.generation)
        assertEquals(listOf(knowledge), f.composition.snapshot())
        assertTrue(ownership.remove())
        assertNull(f.composition.find(knowledge.id))
        assertFalse(f.composition.contains(knowledge.id))
        assertEquals(
            listOf("KNOWLEDGE_REGISTERED", "KNOWLEDGE_REMOVED"),
            f.logs.snapshot().map { it.marker }
        )
    }

    @Test
    fun duplicate_create_does_not_replace_current_knowledge() {
        val f = fixture()
        val first = item("first")
        val second = item("second")

        assertIs<KnowledgeCreateResult.Created>(f.composition.create(first))
        assertIs<KnowledgeCreateResult.Rejected>(f.composition.create(second))
        assertEquals(first, f.composition.find(first.id))
    }

    @Test
    fun stale_ownership_cannot_remove_same_id_replacement() {
        val f = fixture()
        val first = item("first")
        val stale = assertIs<KnowledgeCreateResult.Created>(
            f.composition.create(first)
        ).ownership
        assertTrue(stale.remove())

        val replacement = item("replacement")
        val current = assertIs<KnowledgeCreateResult.Created>(
            f.composition.create(replacement)
        ).ownership

        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove())
        assertEquals(replacement, f.composition.find(replacement.id))
        assertEquals(current.generation, f.composition.inspect(replacement.id)?.generation)
    }

    @Test
    fun create_and_remove_use_fresh_foundation_contexts() {
        val f = fixture()
        val ownership = assertIs<KnowledgeCreateResult.Created>(
            f.composition.create(item())
        ).ownership
        assertTrue(ownership.remove())

        val correlations = f.logs.snapshot().map { it.context.correlationId }
        assertEquals(2, correlations.size)
        assertNotEquals(correlations[0], correlations[1])
    }

    @Test
    fun composition_metadata_preserves_origin_without_content_or_trust_semantics() {
        val f = fixture()
        assertIs<KnowledgeCreateResult.Created>(f.composition.create(item()))

        val metadata = f.logs.snapshot().first().metadata
        assertEquals("knowledge-1", metadata["knowledgeItemId"])
        assertEquals("memory", metadata["knowledgeOriginType"])
        assertEquals("memory-1", metadata["memoryRecordId"])
        assertEquals("4", metadata["memoryGeneration"])
        assertFalse(metadata.containsKey("content"))
        assertFalse(metadata.keys.any { it.contains("trust", ignoreCase = true) })
        assertFalse(metadata.keys.any { it.contains("confidence", ignoreCase = true) })
        assertFalse(metadata.keys.any { it.contains("truth", ignoreCase = true) })
    }

    @Test
    fun public_api_does_not_expose_raw_store_or_registration() {
        val forbidden = setOf(
            KnowledgeStore::class.java,
            KnowledgeRegistration::class.java
        )
        val exposed = KnowledgeComposition::class.java.methods.filter { method ->
            Modifier.isPublic(method.modifiers) && method.returnType in forbidden
        }
        assertTrue(exposed.isEmpty(), "knowledge API must not expose raw store internals: $exposed")
    }
}
