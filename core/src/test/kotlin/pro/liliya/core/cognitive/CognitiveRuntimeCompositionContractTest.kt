package pro.liliya.core.cognitive

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItem
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeItemSnapshot
import pro.liliya.core.knowledge.KnowledgeOrigin
import pro.liliya.core.knowledge.KnowledgeSourceId
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemoryRecordSnapshot
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class CognitiveRuntimeCompositionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val memory: MemoryRetrievalPort,
        val knowledge: KnowledgeRetrievalPort,
        val inference: CognitiveInferencePort,
        val composition: CognitiveRuntimeComposition
    )

    private fun fixture(prefix: String): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "$prefix-${sequence.incrementAndGet()}" }
        )
        val memory = MemoryRetrievalPort { MemoryRetrievalResult(emptyList()) }
        val knowledge = KnowledgeRetrievalPort { KnowledgeRetrievalResult(emptyList()) }
        val inference = CognitiveInferencePort { request ->
            CognitiveInferenceResult.Succeeded(request.turn, "fake:${request.context.items.size}")
        }
        return Fixture(
            logs = logs,
            memory = memory,
            knowledge = knowledge,
            inference = inference,
            composition = CognitiveRuntimeComposition(
                foundation = foundation,
                memoryRetrieval = memory,
                knowledgeRetrieval = knowledge,
                selfSnapshots = SelfSnapshotPort { null },
                personalitySnapshots = PersonalitySnapshotPort { emptyList() },
                inference = inference,
                limits = CognitiveRuntimeLimits(
                    maxInputChars = 64,
                    maxContextItems = 8,
                    maxContextItemChars = 64,
                    maxRetrievalResults = 4,
                    maxInferenceOutputChars = 64
                )
            )
        )
    }

    @Test
    fun compositions_are_isolated_even_for_same_turn_id() {
        val first = fixture("first")
        val second = fixture("second")

        val firstTurn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            first.composition.beginTurn(CognitiveTurnId("same"), CognitiveInput("one"))
        ).turn
        val secondTurn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            second.composition.beginTurn(CognitiveTurnId("same"), CognitiveInput("two"))
        ).turn

        assertNotSame(firstTurn, secondTurn)
        assertEquals(1L, firstTurn.reference.generation.value)
        assertEquals(1L, secondTurn.reference.generation.value)
        assertEquals(firstTurn.reference, first.composition.currentReference())
        assertEquals(secondTurn.reference, second.composition.currentReference())
    }

    @Test
    fun public_turn_handle_is_read_only_and_cannot_publish_or_terminate() {
        val methods = CognitiveTurnHandle::class.java.methods.map { it.name }.toSet()

        assertTrue("getReference" in methods)
        assertTrue("isCurrent" in methods)
        assertTrue("lifecycle" in methods)
        assertFalse("publishContextIfCurrent" in methods)
        assertFalse("beginGenerating" in methods)
        assertFalse("publishInferenceIfCurrent" in methods)
        assertFalse("complete" in methods)
        assertFalse("fail" in methods)
        assertFalse("getInput" in methods)
        assertFalse("context" in methods)
        assertFalse("inference" in methods)
    }

    @Test
    fun observability_contains_structural_identity_but_not_private_input() {
        val f = fixture("privacy")
        val privateInput = "never-log-this-private-input"

        assertIs<CognitiveTurnRegistrationResult.Registered>(
            f.composition.beginTurn(CognitiveTurnId("turn-private"), CognitiveInput(privateInput))
        )

        val events = f.logs.snapshot()
        assertTrue(events.isNotEmpty())
        events.forEach { event ->
            assertFalse(event.message.contains(privateInput))
            assertFalse(event.metadata.keys.any { it.contains("input", ignoreCase = true) })
            assertFalse(event.metadata.values.any { it.contains(privateInput) })
            assertFalse(event.metadata.keys.any { it.contains("authority", ignoreCase = true) })
            assertFalse(event.metadata.keys.any { it.contains("license", ignoreCase = true) })
        }
    }

    @Test
    fun deterministic_fake_ports_preserve_exact_turn_reference_without_public_mutation_handle() {
        val f = fixture("ports")
        val input = CognitiveInput("hello")
        val turn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            f.composition.beginTurn(CognitiveTurnId("turn-ports"), input)
        ).turn

        val memory = f.memory.retrieve(
            MemoryRetrievalRequest(turn.reference, input, 2)
        )
        val knowledge = f.knowledge.retrieve(
            KnowledgeRetrievalRequest(turn.reference, input, 2)
        )
        assertTrue(memory.items.isEmpty())
        assertTrue(knowledge.items.isEmpty())

        val context = CognitiveContextSnapshot(turn.reference, emptyList())
        val result = assertIs<CognitiveInferenceResult.Succeeded>(
            f.inference.infer(
                CognitiveInferenceRequest(turn.reference, input, context)
            )
        )
        assertEquals(turn.reference, result.turn)
        assertEquals("fake:0", result.output)
    }

    @Test
    fun retrieval_results_detach_mutable_input_lists() {
        val memorySnapshot = MemoryRecordSnapshot(
            record = MemoryRecord(
                id = MemoryRecordId("memory-1"),
                provenance = MemoryProvenance(MemorySourceId("test")),
                content = "private memory",
                createdAt = Instant.parse("2026-09-01T12:00:00Z")
            ),
            generation = MemoryGeneration(1)
        )
        val knowledgeSnapshot = KnowledgeItemSnapshot(
            item = KnowledgeItem(
                id = KnowledgeItemId("knowledge-1"),
                origin = KnowledgeOrigin.Declared(KnowledgeSourceId("test")),
                content = "private knowledge",
                createdAt = Instant.parse("2026-09-01T12:00:01Z")
            ),
            generation = KnowledgeGeneration(1)
        )
        val mutableMemory = mutableListOf(memorySnapshot)
        val mutableKnowledge = mutableListOf(knowledgeSnapshot)
        val memoryResult = MemoryRetrievalResult(mutableMemory)
        val knowledgeResult = KnowledgeRetrievalResult(mutableKnowledge)

        mutableMemory.clear()
        mutableKnowledge.clear()

        assertEquals(listOf(memorySnapshot), memoryResult.items)
        assertEquals(listOf(knowledgeSnapshot), knowledgeResult.items)
    }
}
