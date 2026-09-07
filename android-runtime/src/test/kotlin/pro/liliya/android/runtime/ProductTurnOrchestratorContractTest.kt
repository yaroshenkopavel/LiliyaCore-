package pro.liliya.android.runtime

import java.time.Instant
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.cognitive.CognitiveContextAssemblyFailure
import pro.liliya.core.cognitive.CognitiveContextAssemblyResult
import pro.liliya.core.cognitive.CognitiveFinalizationFailure
import pro.liliya.core.cognitive.CognitiveFinalizationResult
import pro.liliya.core.cognitive.CognitiveGenerationFailure
import pro.liliya.core.cognitive.CognitiveGenerationResult
import pro.liliya.core.cognitive.CognitiveInferenceChunk
import pro.liliya.core.cognitive.CognitiveInput
import pro.liliya.core.cognitive.CognitiveLearningReference
import pro.liliya.core.cognitive.CognitiveReflectionReference
import pro.liliya.core.cognitive.CognitiveResult
import pro.liliya.core.cognitive.CognitiveStreamControl
import pro.liliya.core.cognitive.CognitiveStreamingSink
import pro.liliya.core.cognitive.CognitiveTurnAbortResult
import pro.liliya.core.cognitive.CognitiveTurnGeneration
import pro.liliya.core.cognitive.CognitiveTurnHandle
import pro.liliya.core.cognitive.CognitiveTurnId
import pro.liliya.core.cognitive.CognitiveTurnLifecycle
import pro.liliya.core.cognitive.CognitiveTurnReference
import pro.liliya.core.cognitive.CognitiveTurnRegistrationFailure
import pro.liliya.core.cognitive.CognitiveTurnRegistrationResult
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

class ProductTurnOrchestratorContractTest {

    @Test
    fun heart_not_ready_rejects_before_runtime_access() {
        var runtimeCalls = 0
        val orchestrator = ProductTurnOrchestrator(
            heartState = ProductTurnHeartStateProvider { HeartRuntimeState.FAILED },
            runtimePorts = ProductTurnRuntimePortProvider {
                runtimeCalls += 1
                FakeRuntimePort()
            }
        )

        val result = assertIs<ProductTurnResult.Rejected>(
            orchestrator.run(request(ProductTurnGenerationMode.ONE_SHOT))
        )

        assertEquals(ProductTurnFailure.HEART_NOT_READY, result.reason)
        assertEquals(0, runtimeCalls)
    }

    @Test
    fun one_shot_success_orders_all_stages_and_never_aborts() {
        val port = FakeRuntimePort()
        val orchestrator = ready(port)

        val result = assertIs<ProductTurnResult.Completed>(
            orchestrator.run(request(ProductTurnGenerationMode.ONE_SHOT))
        )

        assertEquals(
            listOf("begin", "context", "one-shot", "finalize"),
            port.events
        )
        assertEquals(0, port.abortCalls)
        assertEquals(0, result.streamedChunkCount)
        assertEquals(0, result.streamedCharacterCount)
        assertEquals(port.reference, result.turn)
        assertEquals("learning-1", result.finalization.learning.id.value)
    }

    @Test
    fun streaming_success_delivers_chunks_counts_progress_and_finalizes_once() {
        val port = FakeRuntimePort(
            streamingChunks = listOf("hel", "lo")
        )
        val delivered = mutableListOf<String>()
        val orchestrator = ready(port)

        val result = assertIs<ProductTurnResult.Completed>(
            orchestrator.run(
                request(ProductTurnGenerationMode.STREAMING),
                CognitiveStreamingSink { chunk ->
                    delivered += chunk.text
                    CognitiveStreamControl.CONTINUE
                }
            )
        )

        assertEquals(listOf("begin", "context", "stream", "finalize"), port.events)
        assertEquals(listOf("hel", "lo"), delivered)
        assertEquals(2, result.streamedChunkCount)
        assertEquals(5, result.streamedCharacterCount)
        assertEquals(0, port.abortCalls)
    }

    @Test
    fun stop_chunk_is_counted_but_cancellation_never_finalizes() {
        val port = FakeRuntimePort(
            streamingChunks = listOf("first", "second"),
            cancelWhenSinkStops = true
        )
        val orchestrator = ready(port)

        val result = assertIs<ProductTurnResult.Rejected>(
            orchestrator.run(
                request(ProductTurnGenerationMode.STREAMING),
                CognitiveStreamingSink { CognitiveStreamControl.STOP }
            )
        )

        assertEquals(ProductTurnFailure.CANCELLED, result.reason)
        assertEquals(listOf("begin", "context", "stream", "abort"), port.events)
        assertFalse("finalize" in port.events)
        assertEquals(1, port.abortCalls)
    }

    @Test
    fun missing_streaming_sink_aborts_registered_turn_without_generation() {
        val port = FakeRuntimePort()
        val result = assertIs<ProductTurnResult.Rejected>(
            ready(port).run(request(ProductTurnGenerationMode.STREAMING))
        )

        assertEquals(ProductTurnFailure.MISSING_STREAMING_SINK, result.reason)
        assertEquals(listOf("begin", "context", "abort"), port.events)
    }

    @Test
    fun context_rejection_aborts_exact_turn_and_blocks_generation() {
        val port = FakeRuntimePort(
            context = CognitiveContextAssemblyResult.Rejected(
                CognitiveContextAssemblyFailure.MEMORY_PROVIDER_FAILED
            )
        )

        val result = assertIs<ProductTurnResult.Rejected>(
            ready(port).run(request(ProductTurnGenerationMode.ONE_SHOT))
        )

        assertEquals(ProductTurnFailure.CONTEXT_REJECTED, result.reason)
        assertEquals(listOf("begin", "context", "abort"), port.events)
        assertEquals(port.reference, port.lastAborted)
    }

    @Test
    fun generation_rejection_aborts_and_never_finalizes() {
        val port = FakeRuntimePort(
            oneShot = CognitiveGenerationResult.Rejected(
                CognitiveGenerationFailure.MATERIALIZER_REJECTED
            )
        )

        val result = assertIs<ProductTurnResult.Rejected>(
            ready(port).run(request(ProductTurnGenerationMode.ONE_SHOT))
        )

        assertEquals(ProductTurnFailure.GENERATION_REJECTED, result.reason)
        assertEquals(listOf("begin", "context", "one-shot", "abort"), port.events)
    }

    @Test
    fun finalization_rejection_aborts_and_is_not_success() {
        val port = FakeRuntimePort(
            finalization = CognitiveFinalizationResult.Rejected(
                CognitiveFinalizationFailure.OUTCOME_MATERIALIZER_REJECTED
            )
        )

        val result = assertIs<ProductTurnResult.Rejected>(
            ready(port).run(request(ProductTurnGenerationMode.ONE_SHOT))
        )

        assertEquals(ProductTurnFailure.FINALIZATION_REJECTED, result.reason)
        assertEquals(
            listOf("begin", "context", "one-shot", "finalize", "abort"),
            port.events
        )
    }

    @Test
    fun stale_context_is_never_converted_to_success() {
        val port = FakeRuntimePort(context = CognitiveContextAssemblyResult.Stale)

        val result = assertIs<ProductTurnResult.Rejected>(
            ready(port).run(request(ProductTurnGenerationMode.ONE_SHOT))
        )

        assertEquals(ProductTurnFailure.STALE, result.reason)
        assertEquals(listOf("begin", "context", "abort"), port.events)
    }

    @Test
    fun registration_rejection_does_not_abort_foreign_or_existing_turn() {
        val port = FakeRuntimePort(
            registration = CognitiveTurnRegistrationResult.Rejected(
                CognitiveTurnRegistrationFailure.LIVE_TURN_EXISTS
            )
        )

        val result = assertIs<ProductTurnResult.Rejected>(
            ready(port).run(request(ProductTurnGenerationMode.ONE_SHOT))
        )

        assertEquals(ProductTurnFailure.TURN_REGISTRATION_REJECTED, result.reason)
        assertEquals(listOf("begin"), port.events)
        assertEquals(0, port.abortCalls)
    }

    @Test
    fun unexpected_failure_after_registration_attempts_exact_abort() {
        val port = FakeRuntimePort(throwOnContext = true)

        val result = assertIs<ProductTurnResult.Rejected>(
            ready(port).run(request(ProductTurnGenerationMode.ONE_SHOT))
        )

        assertEquals(ProductTurnFailure.INTERNAL_FAILURE, result.reason)
        assertEquals(listOf("begin", "context", "abort"), port.events)
        assertEquals(port.reference, port.lastAborted)
    }

    @Test
    fun previously_obtained_orchestrator_rechecks_live_heart_state_for_each_turn() {
        var state = HeartRuntimeState.READY
        var runtimeCalls = 0
        val port = FakeRuntimePort()
        val orchestrator = ProductTurnOrchestrator(
            heartState = ProductTurnHeartStateProvider { state },
            runtimePorts = ProductTurnRuntimePortProvider {
                runtimeCalls += 1
                port
            }
        )

        assertIs<ProductTurnResult.Completed>(
            orchestrator.run(request(ProductTurnGenerationMode.ONE_SHOT))
        )
        state = HeartRuntimeState.FAILED

        val rejected = assertIs<ProductTurnResult.Rejected>(
            orchestrator.run(
                ProductTurnRequest(
                    turnId = CognitiveTurnId("turn-after-heart-failure"),
                    input = CognitiveInput("private second input"),
                    mode = ProductTurnGenerationMode.ONE_SHOT
                )
            )
        )

        assertEquals(ProductTurnFailure.HEART_NOT_READY, rejected.reason)
        assertEquals(1, runtimeCalls)
    }

    @Test
    fun finalization_stale_aborts_and_never_becomes_success() {
        val port = FakeRuntimePort(
            finalization = CognitiveFinalizationResult.Stale
        )

        val result = assertIs<ProductTurnResult.Rejected>(
            ready(port).run(request(ProductTurnGenerationMode.ONE_SHOT))
        )

        assertEquals(ProductTurnFailure.STALE, result.reason)
        assertEquals(
            listOf("begin", "context", "one-shot", "finalize", "abort"),
            port.events
        )
    }

    @Test
    fun unexpected_stream_sink_exception_is_contained_and_aborts_exact_turn() {
        val port = FakeRuntimePort(streamingChunks = listOf("private-chunk"))

        val result = assertIs<ProductTurnResult.Rejected>(
            ready(port).run(
                request(ProductTurnGenerationMode.STREAMING),
                CognitiveStreamingSink { error("PRIVATE-SINK-EXCEPTION") }
            )
        )

        assertEquals(ProductTurnFailure.INTERNAL_FAILURE, result.reason)
        assertEquals(listOf("begin", "context", "stream", "abort"), port.events)
        assertEquals(port.reference, port.lastAborted)
    }

    @Test
    fun request_and_completed_rendering_do_not_expose_private_content() {
        val privateInput = "PRIVATE-PRODUCT-TURN-INPUT"
        val request = ProductTurnRequest(
            turnId = CognitiveTurnId("PRIVATE-TURN-ID"),
            input = CognitiveInput(privateInput),
            mode = ProductTurnGenerationMode.ONE_SHOT
        )
        val result = assertIs<ProductTurnResult.Completed>(
            ready(FakeRuntimePort()).run(request)
        )

        assertFalse(privateInput in request.toString())
        assertFalse("PRIVATE-TURN-ID" in request.toString())
        assertFalse("final-result-private" in result.toString())
        assertFalse("learning-private" in result.toString())
        assertTrue("finalization=<redacted>" in result.toString())
    }

    private fun ready(port: ProductTurnRuntimePort): ProductTurnOrchestrator =
        ProductTurnOrchestrator(
            heartState = ProductTurnHeartStateProvider { HeartRuntimeState.READY },
            runtimePorts = ProductTurnRuntimePortProvider { port }
        )

    private fun request(mode: ProductTurnGenerationMode): ProductTurnRequest =
        ProductTurnRequest(
            turnId = CognitiveTurnId("turn-1"),
            input = CognitiveInput("private user input"),
            mode = mode
        )

    private class FakeRuntimePort(
        private val registration: CognitiveTurnRegistrationResult? = null,
        private val context: CognitiveContextAssemblyResult =
            CognitiveContextAssemblyResult.Published(0),
        private val oneShot: CognitiveGenerationResult? = null,
        private val finalization: CognitiveFinalizationResult? = null,
        private val streamingChunks: List<String> = listOf("stream"),
        private val cancelWhenSinkStops: Boolean = false,
        private val throwOnContext: Boolean = false
    ) : ProductTurnRuntimePort {
        val reference = CognitiveTurnReference(
            CognitiveTurnId("turn-1"),
            CognitiveTurnGeneration(1)
        )
        val events = mutableListOf<String>()
        var abortCalls = 0
        var lastAborted: CognitiveTurnReference? = null

        override fun beginTurn(
            id: CognitiveTurnId,
            input: CognitiveInput
        ): CognitiveTurnRegistrationResult {
            events += "begin"
            return registration ?: CognitiveTurnRegistrationResult.Registered(
                object : CognitiveTurnHandle {
                    override val reference = this@FakeRuntimePort.reference
                    override fun isCurrent(): Boolean = true
                    override fun lifecycle(): CognitiveTurnLifecycle = CognitiveTurnLifecycle.CREATED
                }
            )
        }

        override fun assembleContext(
            reference: CognitiveTurnReference
        ): CognitiveContextAssemblyResult {
            events += "context"
            if (throwOnContext) error("PRIVATE-EXCEPTION-MESSAGE")
            return context
        }

        override fun generateOneShot(
            reference: CognitiveTurnReference
        ): CognitiveGenerationResult {
            events += "one-shot"
            return oneShot ?: generationSuccess(reference)
        }

        override fun generateStreaming(
            reference: CognitiveTurnReference,
            sink: CognitiveStreamingSink
        ): CognitiveGenerationResult {
            events += "stream"
            for ((index, text) in streamingChunks.withIndex()) {
                val control = sink.onChunk(
                    CognitiveInferenceChunk(
                        turn = reference,
                        sequence = index.toLong() + 1,
                        text = text
                    )
                )
                if (control == CognitiveStreamControl.STOP) {
                    return if (cancelWhenSinkStops) {
                        CognitiveGenerationResult.Rejected(
                            CognitiveGenerationFailure.INFERENCE_CANCELLED
                        )
                    } else {
                        CognitiveGenerationResult.Rejected(
                            CognitiveGenerationFailure.INFERENCE_PROVIDER_REJECTED
                        )
                    }
                }
            }
            return generationSuccess(reference)
        }

        override fun finalize(
            reference: CognitiveTurnReference
        ): CognitiveFinalizationResult {
            events += "finalize"
            return finalization ?: completed(reference)
        }

        override fun abort(
            reference: CognitiveTurnReference
        ): CognitiveTurnAbortResult {
            events += "abort"
            abortCalls += 1
            lastAborted = reference
            return CognitiveTurnAbortResult.Aborted
        }

        private fun generationSuccess(
            reference: CognitiveTurnReference
        ): CognitiveGenerationResult.Succeeded =
            CognitiveGenerationResult.Succeeded(
                turn = reference,
                planning = PlanningReference(
                    PlanningProposalId("planning-1"),
                    PlanningGeneration(1)
                ),
                reasoning = ReasoningReference(
                    ReasoningArtifactId("reasoning-1"),
                    ReasoningGeneration(1)
                ),
                decision = DecisionReference(
                    DecisionId("decision-1"),
                    DecisionGeneration(1)
                )
            )

        private fun completed(
            reference: CognitiveTurnReference
        ): CognitiveFinalizationResult.Completed {
            val generation = generationSuccess(reference)
            return CognitiveFinalizationResult.Completed(
                result = CognitiveResult(
                    turn = reference,
                    planning = generation.planning,
                    reasoning = generation.reasoning,
                    decision = generation.decision,
                    content = "final-result-private",
                    createdAt = Instant.parse("2026-09-08T00:00:00Z")
                ),
                reflection = CognitiveReflectionReference(
                    ReflectionRecordId("reflection-1"),
                    ReflectionGeneration(1)
                ),
                learning = CognitiveLearningReference(
                    LearningCandidateId("learning-private"),
                    LearningGeneration(1)
                )
            )
        }
    }
}
