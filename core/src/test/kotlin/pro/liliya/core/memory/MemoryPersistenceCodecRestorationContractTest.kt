package pro.liliya.core.memory

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
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
import pro.liliya.core.observability.CoreObservability
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentSchemaVersion

class MemoryPersistenceCodecRestorationContractTest {
    private fun observability(): CoreObservability {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        return CoreObservability(
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            diagnostics = DiagnosticRecorder(diagnostics)
        )
    }

    private fun record(
        id: String = "memory-1",
        content: String = "private memory content"
    ) = MemoryRecord(
        id = MemoryRecordId(id),
        provenance = MemoryProvenance(
            sourceId = MemorySourceId("conversation"),
            sourceReference = MemorySourceReference("turn-42")
        ),
        content = content,
        createdAt = Instant.parse("2026-08-30T14:20:00.123456789Z")
    )

    @Test
    fun codec_round_trip_preserves_exact_memory_domain_fields() {
        val original = record()

        val persistent = MemoryPersistentRecordCodec.encode(original)
        val decoded = assertIs<MemoryPersistentDecodeResult.Decoded>(
            MemoryPersistentRecordCodec.decode(persistent)
        )

        assertEquals(original, decoded.record)
        assertEquals(original.id.value, persistent.id.value)
        assertEquals(original.createdAt, persistent.createdAt)
        assertEquals(MemoryPersistentRecordCodec.schemaId, persistent.schemaId)
        assertEquals(MemoryPersistentRecordCodec.schemaVersion, persistent.schemaVersion)
    }

    @Test
    fun malformed_or_incompatible_payload_fails_explicitly_without_content_rendering() {
        val secret = "never-render-this-private-memory"
        val encoded = MemoryPersistentRecordCodec.encode(record(content = secret))
        val malformed = encoded.copy(payload = PersistentPayload(byteArrayOf(1, 2, 3)))
        val incompatible = encoded.copy(schemaVersion = PersistentSchemaVersion(2))

        assertIs<MemoryPersistentDecodeResult.Corrupt>(
            MemoryPersistentRecordCodec.decode(malformed)
        )
        val incompatibleResult = assertIs<MemoryPersistentDecodeResult.Incompatible>(
            MemoryPersistentRecordCodec.decode(incompatible)
        )
        val decoded = assertIs<MemoryPersistentDecodeResult.Decoded>(
            MemoryPersistentRecordCodec.decode(encoded)
        )

        assertFalse(decoded.toString().contains(secret))
        assertFalse(incompatibleResult.toString().contains(secret))
        assertFalse(malformed.toString().contains(secret))
    }

    @Test
    fun codec_rejects_persistent_identity_or_timestamp_mismatch() {
        val encoded = MemoryPersistentRecordCodec.encode(record())

        assertIs<MemoryPersistentDecodeResult.Corrupt>(
            MemoryPersistentRecordCodec.decode(
                encoded.copy(id = PersistentEntityId("different-id"))
            )
        )
        assertIs<MemoryPersistentDecodeResult.Corrupt>(
            MemoryPersistentRecordCodec.decode(
                encoded.copy(createdAt = encoded.createdAt.plusSeconds(1))
            )
        )
    }

    @Test
    fun restoration_preserves_exact_generations_and_high_watermark() {
        val restored = assertIs<MemoryRestorationResult.Restored>(
            MemoryStore.restore(
                observability = observability(),
                entries = listOf(
                    MemoryRecordSnapshot(record("b"), MemoryGeneration(4)),
                    MemoryRecordSnapshot(record("a"), MemoryGeneration(7))
                ),
                highWatermark = 9
            )
        ).store

        assertEquals(4, restored.inspect(MemoryRecordId("b"))?.generation?.value)
        assertEquals(7, restored.inspect(MemoryRecordId("a"))?.generation?.value)
        assertEquals(listOf("a", "b"), restored.snapshot().map { it.id.value }.sorted())

        val next = assertIs<MemoryRegistrationResult.Registered>(
            restored.register(record("c"), context("next"))
        ).registration
        assertEquals(10, next.generation.value)
    }

    @Test
    fun restoration_rejects_duplicate_identity_generation_and_impossible_high_watermark() {
        val memory = record("same")
        val obs = observability()

        assertIs<MemoryRestorationResult.Rejected>(
            MemoryStore.restore(
                obs,
                listOf(
                    MemoryRecordSnapshot(memory, MemoryGeneration(1)),
                    MemoryRecordSnapshot(memory.copy(content = "replacement"), MemoryGeneration(2))
                ),
                highWatermark = 2
            )
        )
        assertIs<MemoryRestorationResult.Rejected>(
            MemoryStore.restore(
                obs,
                listOf(
                    MemoryRecordSnapshot(record("one"), MemoryGeneration(2)),
                    MemoryRecordSnapshot(record("two"), MemoryGeneration(2))
                ),
                highWatermark = 2
            )
        )
        assertIs<MemoryRestorationResult.Rejected>(
            MemoryStore.restore(
                obs,
                listOf(MemoryRecordSnapshot(record("future"), MemoryGeneration(3))),
                highWatermark = 2
            )
        )
    }

    @Test
    fun persistence_codec_api_does_not_introduce_permission_or_platform_semantics() {
        val forbidden = listOf(
            "authority",
            "permission",
            "license",
            "android",
            "keystore",
            "sqlite",
            "scheduler",
            "retry",
            "knowledge",
            "learning"
        )
        val names = MemoryPersistentRecordCodec::class.java.declaredMethods
            .map { it.name.lowercase() }

        forbidden.forEach { term ->
            assertTrue(names.none { it.contains(term) })
        }
    }

    private fun context(correlationId: String) = LogContextPropagation.root(
        module = "CORE",
        component = "Memory",
        operation = "memory-persistence-restoration-contract",
        generator = CorrelationIdGenerator { correlationId }
    )
}
