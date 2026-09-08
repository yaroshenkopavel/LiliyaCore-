package pro.liliya.android.runtime

import java.time.Instant
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import pro.liliya.core.cognitive.CognitiveFinalizationResult
import pro.liliya.core.cognitive.CognitiveInferenceChunk
import pro.liliya.core.cognitive.CognitiveLearningReference
import pro.liliya.core.cognitive.CognitiveReflectionReference
import pro.liliya.core.cognitive.CognitiveResult
import pro.liliya.core.cognitive.CognitiveStreamControl
import pro.liliya.core.cognitive.CognitiveStreamingSink
import pro.liliya.core.cognitive.CognitiveTurnGeneration
import pro.liliya.core.cognitive.CognitiveTurnId
import pro.liliya.core.cognitive.CognitiveTurnReference
import pro.liliya.core.cognitive.DecisionReference
import pro.liliya.core.cognitive.PlanningReference
import pro.liliya.core.cognitive.ReasoningReference
import pro.liliya.core.decision.DecisionGeneration
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.learning.LearningCandidateId
import pro.liliya.core.learning.LearningGeneration
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningGeneration
import pro.liliya.core.reflection.ReflectionGeneration
import pro.liliya.core.reflection.ReflectionRecordId

class ProductChatHostContractTest {

    @Test
    fun blank_and_over_limit_input_reject_before_id_or_product_turn() {
        var idCalls = 0
        var turnCalls = 0
        val host = host(
            maxInputChars = 4,
            turnIds = ProductChatTurnIdSource {
                idCalls += 1
                "chat-test"
            },
            runner = ProductChatTurnRunner { _, _ ->
                turnCalls += 1
                completed("unused")
            }
        )

        assertEquals(
            ProductChatFailure.INPUT_REJECTED,
            assertIs<ProductChatResult.Rejected>(
                host.send(ProductChatRequest("", ProductChatGenerationMode.ONE_SHOT))
            ).reason
        )
        assertEquals(
            ProductChatFailure.INPUT_REJECTED,
            assertIs<ProductChatResult.Rejected>(
                host.send(ProductChatRequest("12345", ProductChatGenerationMode.ONE_SHOT))
            ).reason
        )
        assertEquals(0, idCalls)
        assertEquals(0, turnCalls)
    }

    @Test
    fun one_shot_allocates_one_id_runs_once_and_returns_finalized_reply() {
        var idCalls = 0
        var turnCalls = 0
        var captured: ProductTurnRequest? = null
        val host = host(
            turnIds = ProductChatTurnIdSource {
                idCalls += 1
                "chat-deterministic-1"
            },
            runner = ProductChatTurnRunner { request, sink ->
                turnCalls += 1
                captured = request
                assertNull(sink)
                completed("finalized reply")
            }
        )

        val result = assertIs<ProductChatResult.Completed>(
            host.send(ProductChatRequest("hello", ProductChatGenerationMode.ONE_SHOT))
        )

        assertEquals(1, idCalls)
        assertEquals(1, turnCalls)
        assertEquals("chat-deterministic-1", captured?.turnId?.value)
        assertEquals("hello", captured?.input?.text)
        assertEquals(ProductTurnGenerationMode.ONE_SHOT, captured?.mode)
        assertEquals("finalized reply", result.reply)
    }

    @Test
    fun streaming_requires_sink_before_id_allocation() {
        var idCalls = 0
        var turnCalls = 0
        val host = host(
            turnIds = ProductChatTurnIdSource {
                idCalls += 1
                "chat-stream"
            },
            runner = ProductChatTurnRunner { _, _ ->
                turnCalls += 1
                completed("unused")
            }
        )

        val result = assertIs<ProductChatResult.Rejected>(
            host.send(ProductChatRequest("hello", ProductChatGenerationMode.STREAMING))
        )

        assertEquals(ProductChatFailure.STREAMING_SINK_REQUIRED, result.reason)
        assertEquals(0, idCalls)
        assertEquals(0, turnCalls)
    }

    @Test
    fun streaming_maps_chunks_one_for_one_and_stop_control_exactly() {
        val delivered = mutableListOf<ProductChatChunk>()
        val host = host(
            runner = ProductChatTurnRunner { request, sink ->
                assertEquals(ProductTurnGenerationMode.STREAMING, request.mode)
                val active = requireNotNull(sink)
                assertEquals(
                    CognitiveStreamControl.CONTINUE,
                    active.onChunk(CognitiveInferenceChunk(reference(), 1, "one"))
                )
                assertEquals(
                    CognitiveStreamControl.STOP,
                    active.onChunk(CognitiveInferenceChunk(reference(), 2, "two"))
                )
                ProductTurnResult.Rejected(ProductTurnFailure.CANCELLED)
            }
        )

        val result = assertIs<ProductChatResult.Rejected>(
            host.send(
                ProductChatRequest("hello", ProductChatGenerationMode.STREAMING),
                ProductChatStreamingSink { chunk ->
                    delivered += chunk
                    if (chunk.sequence == 2L) ProductChatStreamControl.STOP
                    else ProductChatStreamControl.CONTINUE
                }
            )
        )

        assertEquals(ProductChatFailure.CANCELLED, result.reason)
        assertEquals(listOf(1L, 2L), delivered.map { it.sequence })
        assertEquals(listOf("one", "two"), delivered.map { it.text })
    }

    @Test
    fun terminal_reply_comes_from_finalization_not_stream_concat() {
        val host = host(
            runner = ProductChatTurnRunner { _, sink ->
                requireNotNull(sink).onChunk(CognitiveInferenceChunk(reference(), 1, "raw"))
                completed("materialized-final", chunks = 1, chars = 3)
            }
        )

        val result = assertIs<ProductChatResult.Completed>(
            host.send(
                ProductChatRequest("hello", ProductChatGenerationMode.STREAMING),
                ProductChatStreamingSink { ProductChatStreamControl.CONTINUE }
            )
        )

        assertEquals("materialized-final", result.reply)
        assertEquals(1, result.streamedChunkCount)
        assertEquals(3, result.streamedCharacterCount)
    }

    @Test
    fun product_turn_failures_map_to_bounded_chat_failures() {
        val cases = listOf(
            ProductTurnFailure.HEART_NOT_READY to ProductChatFailure.HEART_NOT_READY,
            ProductTurnFailure.TURN_REGISTRATION_REJECTED to ProductChatFailure.BUSY_OR_TURN_REJECTED,
            ProductTurnFailure.CONTEXT_REJECTED to ProductChatFailure.CONTEXT_REJECTED,
            ProductTurnFailure.GENERATION_REJECTED to ProductChatFailure.GENERATION_REJECTED,
            ProductTurnFailure.CANCELLED to ProductChatFailure.CANCELLED,
            ProductTurnFailure.FINALIZATION_REJECTED to ProductChatFailure.FINALIZATION_REJECTED,
            ProductTurnFailure.STALE to ProductChatFailure.STALE,
            ProductTurnFailure.INTERNAL_FAILURE to ProductChatFailure.INTERNAL_FAILURE
        )

        for ((product, expected) in cases) {
            val host = host(
                runner = ProductChatTurnRunner { _, _ -> ProductTurnResult.Rejected(product) }
            )
            val actual = assertIs<ProductChatResult.Rejected>(
                host.send(ProductChatRequest("hello", ProductChatGenerationMode.ONE_SHOT))
            )
            assertEquals(expected, actual.reason)
        }
    }

    @Test
    fun invalid_generated_id_fails_closed_before_product_turn() {
        var turnCalls = 0
        val host = host(
            turnIds = ProductChatTurnIdSource { "x".repeat(65) },
            runner = ProductChatTurnRunner { _, _ ->
                turnCalls += 1
                completed("unused")
            }
        )

        val result = assertIs<ProductChatResult.Rejected>(
            host.send(ProductChatRequest("hello", ProductChatGenerationMode.ONE_SHOT))
        )

        assertEquals(ProductChatFailure.INTERNAL_FAILURE, result.reason)
        assertEquals(0, turnCalls)
    }

    @Test
    fun public_rendering_redacts_request_chunk_reply_and_turn_source() {
        val request = ProductChatRequest("PRIVATE REQUEST", ProductChatGenerationMode.ONE_SHOT)
        val chunk = ProductChatChunk(1, "PRIVATE CHUNK")
        val completed = ProductChatResult.Completed("PRIVATE REPLY", 1, 13)
        val host = host()

        assertFalse("PRIVATE REQUEST" in request.toString())
        assertFalse("PRIVATE CHUNK" in chunk.toString())
        assertFalse("PRIVATE REPLY" in completed.toString())
        assertFalse("chat-fixed" in host.toString())
    }

    private fun host(
        maxInputChars: Int = 16,
        turnIds: ProductChatTurnIdSource = ProductChatTurnIdSource { "chat-fixed" },
        runner: ProductChatTurnRunner = ProductChatTurnRunner { _, _ -> completed("reply") }
    ): ProductChatHost = ProductChatHost(maxInputChars, turnIds, runner)

    private fun reference(): CognitiveTurnReference =
        CognitiveTurnReference(CognitiveTurnId("chat-fixed"), CognitiveTurnGeneration(1))

    private fun completed(
        reply: String,
        chunks: Int = 0,
        chars: Int = 0
    ): ProductTurnResult.Completed {
        val turn = reference()
        val planning = PlanningReference(PlanningProposalId("planning"), PlanningGeneration(1))
        val reasoning = ReasoningReference(ReasoningArtifactId("reasoning"), ReasoningGeneration(1))
        val decision = DecisionReference(DecisionId("decision"), DecisionGeneration(1))
        val finalization = CognitiveFinalizationResult.Completed(
            result = CognitiveResult(
                turn = turn,
                planning = planning,
                reasoning = reasoning,
                decision = decision,
                content = reply,
                createdAt = Instant.parse("2026-09-08T00:00:00Z")
            ),
            reflection = CognitiveReflectionReference(
                ReflectionRecordId("reflection"),
                ReflectionGeneration(1)
            ),
            learning = CognitiveLearningReference(
                LearningCandidateId("learning"),
                LearningGeneration(1)
            )
        )
        return ProductTurnResult.Completed(turn, finalization, chunks, chars)
    }
}
