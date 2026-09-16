package pro.liliya.app

import android.app.Instrumentation
import android.os.Bundle
import pro.liliya.core.cognitive.CognitiveMaterializationPort
import pro.liliya.core.cognitive.CognitiveMaterializationRequest
import pro.liliya.core.cognitive.CognitiveMaterializationResult
import pro.liliya.core.cognitive.CognitiveRuntimeLimits
import pro.liliya.core.cognitive.CognitiveStructuredResponseBudgets
import pro.liliya.core.cognitive.CognitiveStructuredResponseParseResult
import pro.liliya.core.cognitive.CognitiveStructuredResponseParser

/**
 * Test-only, privacy-preserving diagnostic wrapper for the physical HostBootstrap acceptance.
 * It exposes only typed rejection enums and output length; raw model output is never emitted.
 */
class TypedDiagnosticCognitiveMaterializationPort(
    private val delegate: CognitiveMaterializationPort,
    limits: CognitiveRuntimeLimits,
    private val instrumentation: Instrumentation
) : CognitiveMaterializationPort {
    private val parser = CognitiveStructuredResponseParser(
        CognitiveStructuredResponseBudgets.from(limits)
    )

    override fun materialize(
        request: CognitiveMaterializationRequest
    ): CognitiveMaterializationResult {
        val result = delegate.materialize(request)
        if (result is CognitiveMaterializationResult.Rejected) {
            val parserState = when (val parsed = parser.parse(request.inferenceOutput)) {
                is CognitiveStructuredResponseParseResult.Parsed -> "PARSED"
                is CognitiveStructuredResponseParseResult.Rejected -> parsed.reason.name
            }
            val evidence = Bundle().apply {
                putString("hostQwen.materializationResult", result.reason.name)
                putString("hostQwen.structuredParser", parserState)
                putString(
                    "hostQwen.inferenceOutputChars",
                    request.inferenceOutput.length.toString()
                )
            }
            instrumentation.sendStatus(2, evidence)
            println(
                "HOST_QWEN_TYPED_MATERIALIZATION_DIAGNOSTIC=" +
                    "result=${result.reason.name}," +
                    "parser=$parserState," +
                    "outputChars=${request.inferenceOutput.length}"
            )
        }
        return result
    }
}
