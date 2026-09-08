package pro.liliya.android.runtime

import java.time.Instant
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.cognitive.CognitiveConversationContextSnapshot
import pro.liliya.core.cognitive.CognitiveConversationRole
import pro.liliya.core.cognitive.CognitiveFinalizationResult
import pro.liliya.core.cognitive.CognitiveInferenceChunk
import pro.liliya.core.cognitive.CognitiveLearningReference
import pro.liliya.core.cognitive.CognitiveReflectionReference
import pro.liliya.core.cognitive.CognitiveResult
import pro.liliya.core.cognitive.CognitiveStreamControl
import pro.liliya.core.cognitive.CognitiveTurnGeneration
import pro.liliya.core.cognitive.CognitiveTurnId
import pro.liliya.core.cognitive.CognitiveTurnReference
import pro.liliya.core.cognitive.CognitiveConversationSessionId
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

class ProductConversationHostContractTest {

    @Test
    fun constructor_rejects_non_positive_bounds() {
        assertFailsWith<IllegalArgumentException> {
            ProductConversationHost(
                sessionId = CognitiveConversationSessionId("session"),
                maxInputChars = 0,
                maxTurnIdChars = 64,
                maxRetainedMessages = 2,
                maxRetainedCharacters = 32,
                maxMessageCharacters = 16,
                turnIds = ProductConversationTurnIdSource { "turn" },
                turns = ProductConversationTurnRunner { _, _, _ -> completed("reply") }
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProductConversationHost(
                sessionId = CognitiveConversationSessionId("session"),
                maxInputChars = 16,
                maxTurnIdChars = 64,
                maxRetainedMessages = 0,
                maxRetainedCharacters = 32,
                maxMessageCharacters = 16,
                turnIds = ProductConversationTurnIdSource { "turn" },
                turns = ProductConversationTurnRunner { _, _, _ -> completed("reply") }
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProductConversationHost(
                sessionId = CognitiveConversationSessionId("session"),
                maxInputChars = 16,
                maxTurnIdChars = 64,
                maxRetainedMessages = 2,
                maxRetainedCharacters = 0,
                maxMessageCharacters = 16,
                turnIds = ProductConversationTurnIdSource { "turn" },
                turns = ProductConversationTurnRunner { _, _, _ -> completed("reply") }
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProductConversationHost(
                sessionId = CognitiveConversationSessionId("session"),
                maxInputChars = 16,
                maxTurnIdChars = 64,
                maxRetainedMessages = 2,
                maxRetainedCharacters = 32,
                maxMessageCharacters = 0,
                turnIds = ProductConversationTurnIdSource { "turn" },
                turns = ProductConversationTurnRunner { _, _, _ -> completed("reply") }
            )
        }
    }

    @Test
    fun legacy_completed_constructor_remains_available_and_has_no_fabricated_learning_evidence() {
        val completed = ProductConversationResult.Completed(
            reply = "legacy reply",
            streamedChunkCount = 0,
            streamedCharacterCount = 0,
            conversationCommit = ProductConversationCommitStatus.COMMITTED
        )

        assertEquals(null, completed.learningFollowUpReference())
    }

    @Test
    fun one_shot_success_exposes_exact_opaque_learning_follow_up_evidence() {
        val host = host(
            runner = ProductConversationTurnRunner { _, _, _ ->
                completed(
                    reply = "reply",
                    candidateId = "PRIVATE-EXACT-CANDIDATE",
                    learningGeneration = 7
                )
            }
        )

        val result = assertIs<ProductConversationResult.Completed>(
            host.send(ProductChatRequest("hello", ProductChatGenerationMode.ONE_SHOT))
        )
        val evidence = requireNotNull(result.learningFollowUpReference())

        assertEquals("PRIVATE-EXACT-CANDIDATE", evidence.cognitive.id.value)
        assertEquals(7L, evidence.cognitive.generation.value)
        assertFalse("PRIVATE-EXACT-CANDIDATE" in evidence.toString())
        assertFalse("PRIVATE-EXACT-CANDIDATE" in result.toString())
    }

    @Test
    fun streaming_success_exposes_exact_finalization_learning_evidence() {
        val host = host(
            runner = ProductConversationTurnRunner { _, _, sink ->
                requireNotNull(sink).onChunk(CognitiveInferenceChunk(reference(), 1, "partial"))
                completed(
                    reply = "final",
                    chunks = 1,
                    chars = 7,
                    candidateId = "stream-candidate",
                    learningGeneration = 3
                )
            }
        )

        val result = assertIs<ProductConversationResult.Completed>(
            host.send(
                ProductChatRequest("hello", ProductChatGenerationMode.STREAMING),
                ProductChatStreamingSink { ProductChatStreamControl.CONTINUE }
            )
        )
        val evidence = requireNotNull(result.learningFollowUpReference())

        assertEquals("stream-candidate", evidence.cognitive.id.value)
        assertEquals(3L, evidence.generation)
    }

    @Test
    fun successful_non_retained_pair_still_exposes_exact_learning_evidence() {
        val host = host(
            maxRetainedMessages = 2,
            maxRetainedCharacters = 3,
            maxMessageCharacters = 8,
            runner = ProductConversationTurnRunner { _, _, _ ->
                completed(
                    reply = "reply",
                    candidateId = "non-retained-candidate",
                    learningGeneration = 5
                )
            }
        )

        val result = assertIs<ProductConversationResult.Completed>(
            host.send(ProductChatRequest("user", ProductChatGenerationMode.ONE_SHOT))
        )

        assertEquals(
            ProductConversationCommitStatus.NOT_RETAINED_RESOURCE_LIMIT,
            result.conversationCommit
        )
        assertEquals(
            "non-retained-candidate",
            requireNotNull(result.learningFollowUpReference()).cognitive.id.value
        )
    }

    @Test
    fun clear_does_not_erase_already_returned_learning_evidence() {
        val host = host(
            runner = ProductConversationTurnRunner { _, _, _ ->
                completed(
                    reply = "reply",
                    candidateId = "clear-independent-candidate",
                    learningGeneration = 9
                )
            }
        )

        val result = assertIs<ProductConversationResult.Completed>(
            host.send(ProductChatRequest("hello", ProductChatGenerationMode.ONE_SHOT))
        )
        val beforeClear = requireNotNull(result.learningFollowUpReference())

        assertEquals(ProductConversationClearResult.Cleared, host.clear())

        val afterClear = requireNotNull(result.learningFollowUpReference())
        assertEquals(beforeClear.cognitive, afterClear.cognitive)
        assertEquals(9L, afterClear.generation)
    }

    @Test
    fun transcript_eviction_does_not_invalidate_already_returned_learning_evidence() {
        var call = 0
        val host = host(
            maxRetainedMessages = 2,
            maxRetainedCharacters = 64,
            maxMessageCharacters = 16,
            runner = ProductConversationTurnRunner { _, _, _ ->
                call += 1
                completed(
                    reply = "r$call",
                    candidateId = "candidate-$call",
                    learningGeneration = call.toLong()
                )
            }
        )

        val first = assertIs<ProductConversationResult.Completed>(
            host.send(ProductChatRequest("u1", ProductChatGenerationMode.ONE_SHOT))
        )
        val firstEvidence = requireNotNull(first.learningFollowUpReference())

        host.send(ProductChatRequest("u2", ProductChatGenerationMode.ONE_SHOT))
        host.send(ProductChatRequest("u3", ProductChatGenerationMode.ONE_SHOT))

        assertEquals("candidate-1", firstEvidence.cognitive.id.value)
        assertEquals(1L, firstEvidence.generation)
    }

    @Test
    fun returned_evidence_can_be_processed_only_by_explicit_existing_follow_up_host_call() {
        var processedCalls = 0
        var seen: CognitiveLearningReference? = null
        val conversation = host(
            runner = ProductConversationTurnRunner { _, _, _ ->
                completed(
                    reply = "reply",
                    candidateId = "explicit-follow-up",
                    learningGeneration = 11
                )
            }
        )

        val completed = assertIs<ProductConversationResult.Completed>(
            conversation.send(
                ProductChatRequest("hello", ProductChatGenerationMode.ONE_SHOT)
            )
        )

        assertEquals(0, processedCalls)

        val followUp = ProductLearningFollowUpHost(
            ProductLearningFollowUpPort { reference ->
                processedCalls += 1
                seen = reference
                AndroidHeartProductionGovernedLearningProcessResult.NotReady
            }
        )

        assertEquals(
            ProductLearningFollowUpResult.NotReady,
            followUp.process(requireNotNull(completed.learningFollowUpReference()))
        )
        assertEquals(1, processedCalls)
        assertEquals("explicit-follow-up", seen?.id?.value)
        assertEquals(11L, seen?.generation?.value)
    }

    @Test
    fun first_success_uses_empty_context_and_second_success_sees_exact_committed_pair() {
        val snapshots = mutableListOf<CognitiveConversationContextSnapshot>()
        var calls = 0
        val host = host(
            runner = ProductConversationTurnRunner { _, conversation, _ ->
                snapshots += conversation
                calls += 1
                completed(if (calls == 1) "first reply" else "second reply")
            }
        )

        assertIs<ProductConversationResult.Completed>(
            host.send(ProductChatRequest("first user", ProductChatGenerationMode.ONE_SHOT))
        )
        assertIs<ProductConversationResult.Completed>(
            host.send(ProductChatRequest("second user", ProductChatGenerationMode.ONE_SHOT))
        )

        assertTrue(snapshots[0].messages.isEmpty())
        assertEquals(
            listOf(
                1L to CognitiveConversationRole.USER,
                2L to CognitiveConversationRole.ASSISTANT
            ),
            snapshots[1].messages.map { it.sequence.value to it.role }
        )
        assertEquals(
            listOf("first user", "first reply"),
            snapshots[1].messages.map { it.content }
        )
        assertFalse(snapshots[1].messages.any { it.content == "second user" })
    }

    @Test
    fun third_turn_sees_two_completed_pairs_in_exact_chronological_order() {
        val snapshots = mutableListOf<CognitiveConversationContextSnapshot>()
        var call = 0
        val host = host(
            runner = ProductConversationTurnRunner { _, conversation, _ ->
                snapshots += conversation
                call += 1
                completed("reply-$call")
            }
        )

        host.send(ProductChatRequest("user-1", ProductChatGenerationMode.ONE_SHOT))
        host.send(ProductChatRequest("user-2", ProductChatGenerationMode.ONE_SHOT))
        host.send(ProductChatRequest("user-3", ProductChatGenerationMode.ONE_SHOT))

        assertEquals(
            listOf("user-1", "reply-1", "user-2", "reply-2"),
            snapshots[2].messages.map { it.content }
        )
        assertEquals(
            listOf(
                CognitiveConversationRole.USER,
                CognitiveConversationRole.ASSISTANT,
                CognitiveConversationRole.USER,
                CognitiveConversationRole.ASSISTANT
            ),
            snapshots[2].messages.map { it.role }
        )
        assertEquals(listOf(1L, 2L, 3L, 4L), snapshots[2].messages.map { it.sequence.value })
    }

    @Test
    fun rejected_turn_and_stream_cancellation_commit_nothing() {
        val snapshots = mutableListOf<CognitiveConversationContextSnapshot>()
        var call = 0
        val host = host(
            runner = ProductConversationTurnRunner { request, conversation, sink ->
                snapshots += conversation
                call += 1
                when (call) {
                    1 -> completed("baseline")
                    2 -> ProductTurnResult.Rejected(ProductTurnFailure.FINALIZATION_REJECTED)
                    3 -> {
                        requireNotNull(sink).onChunk(CognitiveInferenceChunk(reference(), 1, "partial"))
                        ProductTurnResult.Rejected(ProductTurnFailure.CANCELLED)
                    }
                    else -> completed("after failures")
                }
            }
        )

        assertIs<ProductConversationResult.Completed>(
            host.send(ProductChatRequest("committed", ProductChatGenerationMode.ONE_SHOT))
        )
        assertEquals(
            ProductChatFailure.FINALIZATION_REJECTED,
            assertIs<ProductConversationResult.Rejected>(
                host.send(ProductChatRequest("rejected", ProductChatGenerationMode.ONE_SHOT))
            ).reason
        )
        assertEquals(
            ProductChatFailure.CANCELLED,
            assertIs<ProductConversationResult.Rejected>(
                host.send(
                    ProductChatRequest("cancelled", ProductChatGenerationMode.STREAMING),
                    ProductChatStreamingSink { ProductChatStreamControl.STOP }
                )
            ).reason
        )
        assertIs<ProductConversationResult.Completed>(
            host.send(ProductChatRequest("next", ProductChatGenerationMode.ONE_SHOT))
        )

        assertEquals(
            listOf("committed", "baseline"),
            snapshots.last().messages.map { it.content }
        )
        assertFalse(snapshots.last().messages.any { it.content == "rejected" || it.content == "cancelled" || it.content == "partial" })
    }

    @Test
    fun successful_stream_commits_finalized_reply_not_stream_chunks() {
        val snapshots = mutableListOf<CognitiveConversationContextSnapshot>()
        var call = 0
        val host = host(
            runner = ProductConversationTurnRunner { _, conversation, sink ->
                snapshots += conversation
                call += 1
                if (call == 1) {
                    requireNotNull(sink).onChunk(CognitiveInferenceChunk(reference(), 1, "raw-one"))
                    requireNotNull(sink).onChunk(CognitiveInferenceChunk(reference(), 2, "raw-two"))
                    completed("finalized reply", chunks = 2, chars = 14)
                } else {
                    completed("next reply")
                }
            }
        )

        val first = assertIs<ProductConversationResult.Completed>(
            host.send(
                ProductChatRequest("stream user", ProductChatGenerationMode.STREAMING),
                ProductChatStreamingSink { ProductChatStreamControl.CONTINUE }
            )
        )
        assertEquals(ProductConversationCommitStatus.COMMITTED, first.conversationCommit)

        host.send(ProductChatRequest("next user", ProductChatGenerationMode.ONE_SHOT))
        assertEquals(
            listOf("stream user", "finalized reply"),
            snapshots.last().messages.map { it.content }
        )
        assertFalse(snapshots.last().messages.any { it.content.startsWith("raw-") })
    }

    @Test
    fun message_count_and_character_bounds_evict_oldest_complete_pairs_deterministically() {
        val snapshots = mutableListOf<CognitiveConversationContextSnapshot>()
        val host = host(
            maxRetainedMessages = 4,
            maxRetainedCharacters = 20,
            maxMessageCharacters = 10,
            runner = ProductConversationTurnRunner { request, conversation, _ ->
                snapshots += conversation
                completed("r-" + request.input.text.takeLast(1))
            }
        )

        host.send(ProductChatRequest("u1", ProductChatGenerationMode.ONE_SHOT))
        host.send(ProductChatRequest("u2", ProductChatGenerationMode.ONE_SHOT))
        host.send(ProductChatRequest("u3", ProductChatGenerationMode.ONE_SHOT))
        host.send(ProductChatRequest("u4", ProductChatGenerationMode.ONE_SHOT))

        assertEquals(
            listOf("u2", "r-2", "u3", "r-3"),
            snapshots.last().messages.map { it.content }
        )
        assertEquals(listOf(3L, 4L, 5L, 6L), snapshots.last().messages.map { it.sequence.value })
    }

    @Test
    fun successful_pair_that_cannot_fit_returns_reply_but_retains_no_partial_pair() {
        val snapshots = mutableListOf<CognitiveConversationContextSnapshot>()
        var call = 0
        val host = host(
            maxRetainedMessages = 4,
            maxRetainedCharacters = 12,
            maxMessageCharacters = 5,
            runner = ProductConversationTurnRunner { _, conversation, _ ->
                snapshots += conversation
                call += 1
                if (call == 1) completed("reply") else completed("ok")
            }
        )

        val first = assertIs<ProductConversationResult.Completed>(
            host.send(ProductChatRequest("12345", ProductChatGenerationMode.ONE_SHOT))
        )
        assertEquals(ProductConversationCommitStatus.COMMITTED, first.conversationCommit)

        val oversized = assertIs<ProductConversationResult.Completed>(
            host.send(ProductChatRequest("abcde", ProductChatGenerationMode.ONE_SHOT))
        )
        assertEquals(ProductConversationCommitStatus.COMMITTED, oversized.conversationCommit)

        val tooLargeHost = host(
            maxRetainedMessages = 4,
            maxRetainedCharacters = 7,
            maxMessageCharacters = 5,
            runner = ProductConversationTurnRunner { _, conversation, _ ->
                snapshots += conversation
                completed("reply")
            }
        )
        val result = assertIs<ProductConversationResult.Completed>(
            tooLargeHost.send(ProductChatRequest("12345", ProductChatGenerationMode.ONE_SHOT))
        )
        assertEquals(ProductConversationCommitStatus.NOT_RETAINED_RESOURCE_LIMIT, result.conversationCommit)

        tooLargeHost.send(ProductChatRequest("x", ProductChatGenerationMode.ONE_SHOT))
        assertTrue(snapshots.last().messages.isEmpty())
    }

    @Test
    fun over_per_message_limit_returns_success_without_partial_retention() {
        val snapshots = mutableListOf<CognitiveConversationContextSnapshot>()
        var call = 0
        val host = host(
            maxRetainedMessages = 4,
            maxRetainedCharacters = 64,
            maxMessageCharacters = 4,
            runner = ProductConversationTurnRunner { _, conversation, _ ->
                snapshots += conversation
                call += 1
                if (call == 1) completed("12345") else completed("ok")
            }
        )

        val first = assertIs<ProductConversationResult.Completed>(
            host.send(ProductChatRequest("user", ProductChatGenerationMode.ONE_SHOT))
        )
        assertEquals(
            ProductConversationCommitStatus.NOT_RETAINED_RESOURCE_LIMIT,
            first.conversationCommit
        )

        host.send(ProductChatRequest("next", ProductChatGenerationMode.ONE_SHOT))
        assertTrue(snapshots.last().messages.isEmpty())
    }

    @Test
    fun every_bounded_product_turn_rejection_preserves_prior_committed_transcript() {
        val failures = listOf(
            ProductTurnFailure.HEART_NOT_READY,
            ProductTurnFailure.TURN_REGISTRATION_REJECTED,
            ProductTurnFailure.CONTEXT_REJECTED,
            ProductTurnFailure.GENERATION_REJECTED,
            ProductTurnFailure.CANCELLED,
            ProductTurnFailure.FINALIZATION_REJECTED,
            ProductTurnFailure.STALE,
            ProductTurnFailure.INTERNAL_FAILURE
        )

        for (failure in failures) {
            val snapshots = mutableListOf<CognitiveConversationContextSnapshot>()
            var call = 0
            val host = host(
                runner = ProductConversationTurnRunner { _, conversation, _ ->
                    snapshots += conversation
                    call += 1
                    when (call) {
                        1 -> completed("baseline reply")
                        2 -> ProductTurnResult.Rejected(failure)
                        else -> completed("next reply")
                    }
                }
            )

            assertIs<ProductConversationResult.Completed>(
                host.send(ProductChatRequest("baseline", ProductChatGenerationMode.ONE_SHOT))
            )
            assertIs<ProductConversationResult.Rejected>(
                host.send(ProductChatRequest("must not commit", ProductChatGenerationMode.ONE_SHOT))
            )
            assertIs<ProductConversationResult.Completed>(
                host.send(ProductChatRequest("next", ProductChatGenerationMode.ONE_SHOT))
            )

            assertEquals(
                listOf("baseline", "baseline reply"),
                snapshots.last().messages.map { it.content },
                "failure=$failure must not mutate committed conversation"
            )
        }
    }

    @Test
    fun clear_removes_only_ephemeral_transcript_for_next_turn() {
        val snapshots = mutableListOf<CognitiveConversationContextSnapshot>()
        val host = host(
            runner = ProductConversationTurnRunner { _, conversation, _ ->
                snapshots += conversation
                completed("reply")
            }
        )

        host.send(ProductChatRequest("before clear", ProductChatGenerationMode.ONE_SHOT))
        assertEquals(ProductConversationClearResult.Cleared, host.clear())
        host.send(ProductChatRequest("after clear", ProductChatGenerationMode.ONE_SHOT))

        assertTrue(snapshots.last().messages.isEmpty())
    }

    @Test
    fun busy_send_and_clear_fail_closed_while_one_turn_is_in_flight() {
        lateinit var host: ProductConversationHost
        var nestedSend: ProductConversationResult? = null
        var nestedClear: ProductConversationClearResult? = null
        host = host(
            runner = ProductConversationTurnRunner { _, _, _ ->
                nestedSend = host.send(ProductChatRequest("nested", ProductChatGenerationMode.ONE_SHOT))
                nestedClear = host.clear()
                completed("outer")
            }
        )

        assertIs<ProductConversationResult.Completed>(
            host.send(ProductChatRequest("outer", ProductChatGenerationMode.ONE_SHOT))
        )
        assertEquals(
            ProductChatFailure.BUSY_OR_TURN_REJECTED,
            assertIs<ProductConversationResult.Rejected>(nestedSend).reason
        )
        assertEquals(ProductConversationClearResult.Busy, nestedClear)
    }

    @Test
    fun rendering_redacts_session_messages_reply_and_internal_sources() {
        val host = host(sessionId = CognitiveConversationSessionId("PRIVATE-SESSION"))
        val result = assertIs<ProductConversationResult.Completed>(
            host.send(ProductChatRequest("PRIVATE USER", ProductChatGenerationMode.ONE_SHOT))
        )

        assertFalse("PRIVATE-SESSION" in host.toString())
        assertFalse("PRIVATE USER" in host.toString())
        assertFalse("PRIVATE REPLY" in host.toString())
        assertFalse("PRIVATE REPLY" in result.toString())
    }

    private fun host(
        sessionId: CognitiveConversationSessionId = CognitiveConversationSessionId("conversation-test"),
        maxInputChars: Int = 64,
        maxRetainedMessages: Int = 8,
        maxRetainedCharacters: Int = 256,
        maxMessageCharacters: Int = 64,
        runner: ProductConversationTurnRunner = ProductConversationTurnRunner { _, _, _ -> completed("PRIVATE REPLY") }
    ): ProductConversationHost =
        ProductConversationHost(
            sessionId = sessionId,
            maxInputChars = maxInputChars,
            maxTurnIdChars = 64,
            maxRetainedMessages = maxRetainedMessages,
            maxRetainedCharacters = maxRetainedCharacters,
            maxMessageCharacters = maxMessageCharacters,
            turnIds = ProductConversationTurnIdSource { "conversation-turn" },
            turns = runner
        )

    private fun reference(): CognitiveTurnReference =
        CognitiveTurnReference(CognitiveTurnId("conversation-turn"), CognitiveTurnGeneration(1))

    private fun completed(
        reply: String,
        chunks: Int = 0,
        chars: Int = 0,
        candidateId: String = "learning",
        learningGeneration: Long = 1
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
                LearningCandidateId(candidateId),
                LearningGeneration(learningGeneration)
            )
        )
        return ProductTurnResult.Completed(turn, finalization, chunks, chars)
    }
}
