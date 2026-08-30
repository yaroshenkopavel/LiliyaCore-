package pro.liliya.core.knowledge

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.LogContextPropagation
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.observability.CoreObservability
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion

class KnowledgePersistenceCodecRestorationContractTest {
    private fun observability(): CoreObservability = CoreObservability(
        loggerProvider = LoggerProvider { context ->
            StructuredLogger(context, InMemoryLogWriter())
        },
        diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink())
    )

    private fun context() = LogContextPropagation.root(
        module = "CORE",
        component = "Knowledge",
        operation = "knowledge-persistence-codec-restoration",
        generator = CorrelationIdGenerator { "knowledge-persistence-test" }
    )

    private fun memoryItem(
        id: String = "knowledge-memory",
        content: String = "private memory-derived knowledge"
    ) = KnowledgeItem(
        id = KnowledgeItemId(id),
        origin = KnowledgeOrigin.Memory(
            recordId = MemoryRecordId("memory-42"),
            generation = MemoryGeneration(7)
        ),
        content = content,
        createdAt = Instant.parse("2026-08-30T15:10:00Z")
    )

    private fun declaredItem(
        id: String = "knowledge-declared",
        reference: KnowledgeSourceReference? = KnowledgeSourceReference("note-9")
    ) = KnowledgeItem(
        id = KnowledgeItemId(id),
        origin = KnowledgeOrigin.Declared(
            sourceId = KnowledgeSourceId("operator"),
            sourceReference = reference
        ),
        content = "private declared knowledge",
        createdAt = Instant.parse("2026-08-30T15:11:00Z")
    )

    @Test
    fun memory_origin_codec_round_trip_preserves_exact_identity_origin_content_and_time() {
        val item = memoryItem()
        val persistent = KnowledgePersistentRecordCodec.encode(item)
        val decoded = assertIs<KnowledgePersistentDecodeResult.Decoded>(
            KnowledgePersistentRecordCodec.decode(persistent)
        )

        assertEquals(item, decoded.item)
        assertEquals(item.id.value, persistent.id.value)
        assertEquals(KnowledgePersistentRecordCodec.schemaId, persistent.schemaId)
        assertEquals(KnowledgePersistentRecordCodec.schemaVersion, persistent.schemaVersion)
    }

    @Test
    fun declared_origin_codec_round_trip_preserves_optional_source_reference() {
        listOf(
            declaredItem(id = "with-reference"),
            declaredItem(id = "without-reference", reference = null)
        ).forEach { item ->
            val decoded = assertIs<KnowledgePersistentDecodeResult.Decoded>(
                KnowledgePersistentRecordCodec.decode(
                    KnowledgePersistentRecordCodec.encode(item)
                )
            )
            assertEquals(item, decoded.item)
        }
    }

    @Test
    fun malformed_mismatched_trailing_and_incompatible_records_fail_closed() {
        val encoded = KnowledgePersistentRecordCodec.encode(memoryItem())

        assertIs<KnowledgePersistentDecodeResult.Corrupt>(
            KnowledgePersistentRecordCodec.decode(
                encoded.copy(payload = PersistentPayload(byteArrayOf(1, 2, 3)))
            )
        )
        assertIs<KnowledgePersistentDecodeResult.Corrupt>(
            KnowledgePersistentRecordCodec.decode(
                encoded.copy(
                    payload = PersistentPayload(encoded.payload.copyBytes() + byteArrayOf(0))
                )
            )
        )
        assertIs<KnowledgePersistentDecodeResult.Corrupt>(
            KnowledgePersistentRecordCodec.decode(
                encoded.copy(id = PersistentEntityId("different-id"))
            )
        )
        assertIs<KnowledgePersistentDecodeResult.Corrupt>(
            KnowledgePersistentRecordCodec.decode(
                encoded.copy(createdAt = encoded.createdAt.plusSeconds(1))
            )
        )
        assertIs<KnowledgePersistentDecodeResult.Incompatible>(
            KnowledgePersistentRecordCodec.decode(
                encoded.copy(schemaId = PersistentSchemaId("other-schema"))
            )
        )
        assertIs<KnowledgePersistentDecodeResult.Incompatible>(
            KnowledgePersistentRecordCodec.decode(
                encoded.copy(schemaVersion = PersistentSchemaVersion(2))
            )
        )
    }

    @Test
    fun decode_rendering_redacts_private_knowledge_content() {
        val privateContent = "ultra-private-knowledge-content"
        val decoded = assertIs<KnowledgePersistentDecodeResult.Decoded>(
            KnowledgePersistentRecordCodec.decode(
                KnowledgePersistentRecordCodec.encode(memoryItem(content = privateContent))
            )
        )
        val rendered = decoded.toString()

        assertFalse(rendered.contains(privateContent))
        assertTrue(rendered.contains("content=<redacted>"))
    }

    @Test
    fun restoration_preserves_exact_generations_and_high_watermark() {
        val first = memoryItem(id = "a")
        val second = declaredItem(id = "b")
        val restored = assertIs<KnowledgeRestorationResult.Restored>(
            KnowledgeStore.restore(
                observability = observability(),
                entries = listOf(
                    KnowledgeItemSnapshot(first, KnowledgeGeneration(4)),
                    KnowledgeItemSnapshot(second, KnowledgeGeneration(9))
                ),
                highWatermark = 12
            )
        ).store

        assertEquals(KnowledgeGeneration(4), restored.inspect(first.id)?.generation)
        assertEquals(KnowledgeGeneration(9), restored.inspect(second.id)?.generation)

        val newItem = declaredItem(id = "c", reference = null)
        val registration = assertIs<KnowledgeRegistrationResult.Registered>(
            restored.register(newItem, context())
        ).registration
        assertEquals(KnowledgeGeneration(13), registration.generation)
    }

    @Test
    fun restoration_rejects_invalid_generation_shape_without_partial_store() {
        val item = memoryItem()

        assertIs<KnowledgeRestorationResult.Rejected>(
            KnowledgeStore.restore(
                observability = observability(),
                entries = listOf(KnowledgeItemSnapshot(item, KnowledgeGeneration(2))),
                highWatermark = 1
            )
        )

        assertIs<KnowledgeRestorationResult.Rejected>(
            KnowledgeStore.restore(
                observability = observability(),
                entries = listOf(
                    KnowledgeItemSnapshot(item, KnowledgeGeneration(2)),
                    KnowledgeItemSnapshot(item.copy(content = "replacement"), KnowledgeGeneration(3))
                ),
                highWatermark = 3
            )
        )

        val other = declaredItem(id = "other")
        assertIs<KnowledgeRestorationResult.Rejected>(
            KnowledgeStore.restore(
                observability = observability(),
                entries = listOf(
                    KnowledgeItemSnapshot(item, KnowledgeGeneration(2)),
                    KnowledgeItemSnapshot(other, KnowledgeGeneration(2))
                ),
                highWatermark = 2
            )
        )
    }
}
