package pro.liliya.android.runtime

import pro.liliya.core.cognitive.CognitiveContextAssemblyResult
import pro.liliya.core.cognitive.CognitiveFinalizationResult
import pro.liliya.core.cognitive.CognitiveGenerationFailure
import pro.liliya.core.cognitive.CognitiveGenerationResult
import pro.liliya.core.cognitive.CognitiveInput
import pro.liliya.core.cognitive.CognitiveRuntimeComposition
import pro.liliya.core.cognitive.CognitiveStreamControl
import pro.liliya.core.cognitive.CognitiveStreamingSink
import pro.liliya.core.cognitive.CognitiveTurnAbortResult
import pro.liliya.core.cognitive.CognitiveTurnId
import pro.liliya.core.cognitive.CognitiveTurnReference
import pro.liliya.core.cognitive.CognitiveTurnRegistrationResult

enum class ProductTurnGenerationMode {
    ONE_SHOT,
    STREAMING
}

class ProductTurnRequest(
    val turnId: CognitiveTurnId,
    val input: CognitiveInput,
    val mode: ProductTurnGenerationMode
) {
    override fun toString(): String =
        "ProductTurnRequest(turnId=<redacted>,input=<redacted>,mode=$mode)"
}

enum class ProductTurnFailure {
    HEART_NOT_READY,
    MISSING_STREAMING_SINK,
    TURN_REGISTRATION_REJECTED,
    CONTEXT_REJECTED,
    GENERATION_REJECTED,
    CANCELLED,
    FINALIZATION_REJECTED,
    STALE,
    INTERNAL_FAILURE
}

sealed interface ProductTurnResult {
    class Completed(
        val turn: CognitiveTurnReference,
        val finalization: CognitiveFinalizationResult.Completed,
        val streamedChunkCount: Int,
        val streamedCharacterCount: Int
    ) : ProductTurnResult {
        override fun toString(): String =
            "Completed(turn=$turn,finalization=<redacted>," +
                "streamedChunkCount=$streamedChunkCount," +
                "streamedCharacterCount=$streamedCharacterCount)"
    }

    data class Rejected(
        val reason: ProductTurnFailure
    ) : ProductTurnResult
}

internal interface ProductTurnRuntimePort {
    fun beginTurn(
        id: CognitiveTurnId,
        input: CognitiveInput
    ): CognitiveTurnRegistrationResult

    fun assembleContext(reference: CognitiveTurnReference): CognitiveContextAssemblyResult

    fun generateOneShot(reference: CognitiveTurnReference): CognitiveGenerationResult

    fun generateStreaming(
        reference: CognitiveTurnReference,
        sink: CognitiveStreamingSink
    ): CognitiveGenerationResult

    fun finalize(reference: CognitiveTurnReference): CognitiveFinalizationResult

    fun abort(reference: CognitiveTurnReference): CognitiveTurnAbortResult
}

internal class CognitiveProductTurnRuntimePort(
    private val runtime: CognitiveRuntimeComposition
) : ProductTurnRuntimePort {
    override fun beginTurn(
        id: CognitiveTurnId,
        input: CognitiveInput
    ): CognitiveTurnRegistrationResult = runtime.beginTurn(id, input)

    override fun assembleContext(
        reference: CognitiveTurnReference
    ): CognitiveContextAssemblyResult = runtime.assembleContext(reference)

    override fun generateOneShot(
        reference: CognitiveTurnReference
    ): CognitiveGenerationResult = runtime.generateCognition(reference)

    override fun generateStreaming(
        reference: CognitiveTurnReference,
        sink: CognitiveStreamingSink
    ): CognitiveGenerationResult = runtime.generateCognitionStreaming(reference, sink)

    override fun finalize(
        reference: CognitiveTurnReference
    ): CognitiveFinalizationResult = runtime.finalizeCognition(reference)

    override fun abort(
        reference: CognitiveTurnReference
    ): CognitiveTurnAbortResult = runtime.abortTurn(reference)

    override fun toString(): String = "CognitiveProductTurnRuntimePort(runtime=<redacted>)"
}

fun interface ProductTurnRuntimeProvider {
    fun current(): CognitiveRuntimeComposition?
}

fun interface ProductTurnHeartStateProvider {
    fun state(): HeartRuntimeState
}

internal fun interface ProductTurnRuntimePortProvider {
    fun current(): ProductTurnRuntimePort?
}

/**
 * Thin host-facing sequencer for one complete local product turn.
 *
 * This class owns ordering only. Cognitive turn state, model sessions, Runtime Hardening,
 * Memory/Knowledge, Learning, License, Authority and Execution remain owned elsewhere.
 */
class ProductTurnOrchestrator internal constructor(
    private val heartState: ProductTurnHeartStateProvider,
    private val runtimePorts: ProductTurnRuntimePortProvider
) {
    constructor(
        heartState: ProductTurnHeartStateProvider,
        runtimeProvider: ProductTurnRuntimeProvider
    ) : this(
        heartState = heartState,
        runtimePorts = ProductTurnRuntimePortProvider {
            runtimeProvider.current()?.let(::CognitiveProductTurnRuntimePort)
        }
    )

    fun run(
        request: ProductTurnRequest,
        streamingSink: CognitiveStreamingSink? = null
    ): ProductTurnResult {
        if (heartState.state() != HeartRuntimeState.READY) {
            return rejected(ProductTurnFailure.HEART_NOT_READY)
        }

        val port = runtimePorts.current()
            ?: return rejected(ProductTurnFailure.HEART_NOT_READY)

        val registration = try {
            port.beginTurn(request.turnId, request.input)
        } catch (_: Exception) {
            return rejected(ProductTurnFailure.INTERNAL_FAILURE)
        }

        val reference = when (registration) {
            is CognitiveTurnRegistrationResult.Registered -> registration.turn.reference
            is CognitiveTurnRegistrationResult.Rejected ->
                return rejected(ProductTurnFailure.TURN_REGISTRATION_REJECTED)
        }

        var streamedChunkCount = 0
        var streamedCharacterCount = 0

        try {
            when (port.assembleContext(reference)) {
                is CognitiveContextAssemblyResult.Published -> Unit
                CognitiveContextAssemblyResult.Stale -> {
                    return rejectedAfterAbort(
                        port,
                        reference,
                        ProductTurnFailure.STALE
                    )
                }
                is CognitiveContextAssemblyResult.Rejected -> {
                    return rejectedAfterAbort(
                        port,
                        reference,
                        ProductTurnFailure.CONTEXT_REJECTED
                    )
                }
            }

            val generation = when (request.mode) {
                ProductTurnGenerationMode.ONE_SHOT ->
                    port.generateOneShot(reference)

                ProductTurnGenerationMode.STREAMING -> {
                    val downstream = streamingSink
                    if (downstream == null) {
                        return rejectedAfterAbort(
                            port,
                            reference,
                            ProductTurnFailure.MISSING_STREAMING_SINK
                        )
                    }
                    port.generateStreaming(
                        reference,
                        CognitiveStreamingSink { chunk ->
                            val control = downstream.onChunk(chunk)
                            streamedChunkCount += 1
                            streamedCharacterCount += chunk.text.length
                            control
                        }
                    )
                }
            }

            when (generation) {
                is CognitiveGenerationResult.Succeeded -> Unit
                CognitiveGenerationResult.Stale -> {
                    return rejectedAfterAbort(
                        port,
                        reference,
                        ProductTurnFailure.STALE
                    )
                }
                is CognitiveGenerationResult.Rejected -> {
                    return rejectedAfterAbort(
                        port,
                        reference,
                        if (generation.reason == CognitiveGenerationFailure.INFERENCE_CANCELLED) {
                            ProductTurnFailure.CANCELLED
                        } else {
                            ProductTurnFailure.GENERATION_REJECTED
                        }
                    )
                }
            }

            return when (val finalization = port.finalize(reference)) {
                is CognitiveFinalizationResult.Completed ->
                    ProductTurnResult.Completed(
                        turn = reference,
                        finalization = finalization,
                        streamedChunkCount = streamedChunkCount,
                        streamedCharacterCount = streamedCharacterCount
                    )

                CognitiveFinalizationResult.Stale ->
                    rejectedAfterAbort(
                        port,
                        reference,
                        ProductTurnFailure.STALE
                    )

                is CognitiveFinalizationResult.Rejected ->
                    rejectedAfterAbort(
                        port,
                        reference,
                        ProductTurnFailure.FINALIZATION_REJECTED
                    )
            }
        } catch (_: Exception) {
            abortTerminally(port, reference)
            return rejected(ProductTurnFailure.INTERNAL_FAILURE)
        }
    }

    override fun toString(): String =
        "ProductTurnOrchestrator(heartState=<redacted>,runtime=<redacted>)"

    private fun rejectedAfterAbort(
        runtime: ProductTurnRuntimePort,
        reference: CognitiveTurnReference,
        reason: ProductTurnFailure
    ): ProductTurnResult.Rejected =
        if (abortTerminally(runtime, reference)) {
            rejected(reason)
        } else {
            rejected(ProductTurnFailure.INTERNAL_FAILURE)
        }

    private fun abortTerminally(
        runtime: ProductTurnRuntimePort,
        reference: CognitiveTurnReference
    ): Boolean =
        try {
            when (runtime.abort(reference)) {
                CognitiveTurnAbortResult.Aborted,
                CognitiveTurnAbortResult.Stale -> true
            }
        } catch (_: Exception) {
            false
        }

    private fun rejected(reason: ProductTurnFailure): ProductTurnResult.Rejected =
        ProductTurnResult.Rejected(reason)
}
