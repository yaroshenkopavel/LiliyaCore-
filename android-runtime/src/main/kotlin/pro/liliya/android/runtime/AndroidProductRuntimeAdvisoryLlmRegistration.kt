package pro.liliya.android.runtime

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import pro.liliya.core.asf.AgentCognitiveRuntimeAdapters
import pro.liliya.core.asf.AgentCognitiveRuntimeExecutionRequest
import pro.liliya.core.asf.AgentCognitiveRuntimeExecutionResult
import pro.liliya.core.asf.AgentCognitiveRuntimeRegistration
import pro.liliya.core.asf.AgentRuntimeUsage
import pro.liliya.core.asf.AgentWorkerRuntimeDescriptor
import pro.liliya.core.asf.AgentWorkerRuntimeKind
import pro.liliya.core.cognitive.CognitiveInferencePort
import pro.liliya.core.cognitive.CognitiveInferenceRequest
import pro.liliya.core.cognitive.CognitiveInferenceResult

class AndroidProductRuntimeAdvisoryLlmCompiledRequest(
    val inference: CognitiveInferenceRequest,
    sourceReferences: Collection<String>
) {
    val sourceReferences: List<String> = sourceReferences.distinct().sorted()

    init {
        require(this.sourceReferences.isNotEmpty()) {
            "advisory LLM compiled request requires source provenance"
        }
        require(this.sourceReferences.size <= 48) {
            "advisory LLM compiled request exceeds source provenance bound"
        }
        this.sourceReferences.forEach {
            require(it.isNotBlank()) {
                "advisory LLM source provenance must not be blank"
            }
            require(it.toByteArray(StandardCharsets.UTF_8).size <= 256) {
                "advisory LLM source provenance exceeds bounded size"
            }
        }
    }
}

fun interface AndroidProductRuntimeAdvisoryLlmContextCompiler {
    fun compile(
        request: AgentCognitiveRuntimeExecutionRequest
    ): AndroidProductRuntimeAdvisoryLlmCompiledRequest
}

fun interface AndroidProductRuntimeAdvisoryLlmUsageMeter {
    fun measure(
        request: AgentCognitiveRuntimeExecutionRequest,
        compiled: AndroidProductRuntimeAdvisoryLlmCompiledRequest,
        result: CognitiveInferenceResult?,
        elapsedMillis: Long
    ): AgentRuntimeUsage
}

/**
 * Bounded advisory ASF registration over the existing CognitiveInferencePort.
 *
 * The adapter does not compile raw ASF references into prompts itself. A product-owned context
 * compiler must provide a normal CognitiveInferenceRequest, preserving the existing Cognitive
 * turn/context and authoritative retrieval contracts.
 */
object AndroidProductRuntimeAdvisoryLlmRegistration {
    fun create(
        descriptor: AgentWorkerRuntimeDescriptor,
        available: () -> Boolean,
        contextCompiler: AndroidProductRuntimeAdvisoryLlmContextCompiler,
        inference: CognitiveInferencePort,
        usageMeter: AndroidProductRuntimeAdvisoryLlmUsageMeter,
        nanoTime: () -> Long = System::nanoTime
    ): AgentCognitiveRuntimeRegistration {
        require(descriptor.kind == AgentWorkerRuntimeKind.LLM) {
            "advisory LLM registration requires LLM runtime kind"
        }
        require(descriptor.modelId != null) {
            "advisory LLM registration requires exact model identity"
        }

        return AgentCognitiveRuntimeRegistration(
            descriptor = descriptor,
            available = available,
            maxConcurrentExecutions = 1,
            adapter = AgentCognitiveRuntimeAdapters.llm { request ->
                val compiled = try {
                    contextCompiler.compile(request)
                } catch (_: Exception) {
                    return@llm failed("advisory LLM context compilation failed")
                }

                val started = nanoTime()
                val providerResult = try {
                    inference.infer(compiled.inference)
                } catch (_: Exception) {
                    null
                }
                val finished = nanoTime()
                val elapsedMillis = maxOf(0L, (finished - started) / 1_000_000L)

                val usage = try {
                    usageMeter.measure(
                        request = request,
                        compiled = compiled,
                        result = providerResult,
                        elapsedMillis = elapsedMillis
                    )
                } catch (_: Exception) {
                    return@llm failed("advisory LLM usage metering failed")
                }

                if (providerResult == null) {
                    return@llm AgentCognitiveRuntimeExecutionResult.Failed(
                        reason = "advisory LLM inference provider failed",
                        usage = failureUsage(usage)
                    )
                }

                when (providerResult) {
                    is CognitiveInferenceResult.Succeeded -> {
                        if (usage.artifactCount != 1) {
                            return@llm AgentCognitiveRuntimeExecutionResult.Failed(
                                reason = "advisory LLM usage metering contract violated",
                                usage = failureUsage(usage)
                            )
                        }
                        AgentCognitiveRuntimeExecutionResult.Completed.create(
                            artifactKind = "llm-advisory",
                            payloadDigest = sha256(providerResult.output),
                            sourceReferences = compiled.sourceReferences,
                            usage = usage
                        )
                    }

                    is CognitiveInferenceResult.Rejected ->
                        AgentCognitiveRuntimeExecutionResult.Failed(
                            reason = "advisory LLM inference rejected: " + providerResult.reason.name,
                            usage = failureUsage(usage)
                        )
                }
            }
        )
    }

    private fun failureUsage(usage: AgentRuntimeUsage) =
        usage.copy(artifactCount = 0)

    private fun failed(reason: String) =
        AgentCognitiveRuntimeExecutionResult.Failed(
            reason = reason,
            usage = AgentRuntimeUsage(
                wallClockMillis = 0,
                inferenceUnits = 0,
                contextBytes = 0,
                retrievalItems = 0,
                artifactCount = 0
            )
        )

    private fun sha256(value: String): String =
        "sha256:" +
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
}
