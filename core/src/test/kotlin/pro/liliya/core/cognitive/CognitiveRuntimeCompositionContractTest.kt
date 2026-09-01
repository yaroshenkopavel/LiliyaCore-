package pro.liliya.core.cognitive

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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class CognitiveRuntimeCompositionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
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
            composition = CognitiveRuntimeComposition(
                foundation = foundation,
                memoryRetrieval = memory,
                knowledgeRetrieval = knowledge,
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
        ).ownership
        val secondTurn = assertIs<CognitiveTurnRegistrationResult.Registered>(
            second.composition.beginTurn(CognitiveTurnId("same"), CognitiveInput("two"))
        ).ownership

        assertNotSame(firstTurn, secondTurn)
        assertEquals(1L, firstTurn.reference.generation.value)
        assertEquals(1L, secondTurn.reference.generation.value)
        assertEquals(firstTurn.reference, first.composition.currentReference())
        assertEquals(secondTurn.reference, second.composition.currentReference())
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
    fun deterministic_fake_ports_preserve_exact_turn_reference() {
        val f = fixture("ports")
        val ownership = assertIs<CognitiveTurnRegistrationResult.Registered>(
            f.composition.beginTurn(CognitiveTurnId("turn-ports"), CognitiveInput("hello"))
        ).ownership

        val memory = f.composition.memoryRetrieval.retrieve(
            MemoryRetrievalRequest(ownership.reference, ownership.input, 2)
        )
        val knowledge = f.composition.knowledgeRetrieval.retrieve(
            KnowledgeRetrievalRequest(ownership.reference, ownership.input, 2)
        )
        assertTrue(memory.items.isEmpty())
        assertTrue(knowledge.items.isEmpty())

        val context = CognitiveContextSnapshot(ownership.reference, emptyList())
        val result = assertIs<CognitiveInferenceResult.Succeeded>(
            f.composition.inference.infer(
                CognitiveInferenceRequest(ownership.reference, ownership.input, context)
            )
        )
        assertEquals(ownership.reference, result.turn)
        assertEquals("fake:0", result.output)
    }

    @Test
    fun retrieval_results_detach_mutable_input_lists() {
        val mutableMemory = mutableListOf<pro.liliya.core.memory.MemoryRecordSnapshot>()
        val mutableKnowledge = mutableListOf<pro.liliya.core.knowledge.KnowledgeItemSnapshot>()
        val memoryResult = MemoryRetrievalResult(mutableMemory)
        val knowledgeResult = KnowledgeRetrievalResult(mutableKnowledge)

        mutableMemory.clear()
        mutableKnowledge.clear()

        assertEquals(emptyList(), memoryResult.items)
        assertEquals(emptyList(), knowledgeResult.items)
    }
}
