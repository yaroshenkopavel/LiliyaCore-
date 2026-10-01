package pro.liliya.core.asf

import java.nio.charset.StandardCharsets

enum class AgentCognitiveRuntimeKind {
    DETERMINISTIC_RULE,
    RETRIEVAL_GRAPH,
    EMBEDDING_RERANKER,
    ONNX,
    LLM
}

fun AgentWorkerRuntimeKind.toCognitiveRuntimeKind(): AgentCognitiveRuntimeKind =
    when (this) {
        AgentWorkerRuntimeKind.DETERMINISTIC -> AgentCognitiveRuntimeKind.DETERMINISTIC_RULE
        AgentWorkerRuntimeKind.GRAPH_QUERY -> AgentCognitiveRuntimeKind.RETRIEVAL_GRAPH
        AgentWorkerRuntimeKind.EMBEDDING_RERANKER -> AgentCognitiveRuntimeKind.EMBEDDING_RERANKER
        AgentWorkerRuntimeKind.ONNX -> AgentCognitiveRuntimeKind.ONNX
        AgentWorkerRuntimeKind.LLM -> AgentCognitiveRuntimeKind.LLM
    }

data class AgentCognitiveRuntimeExecutionRequest(
    val descriptor: AgentWorkerRuntimeDescriptor,
    val context: AgentRuntimeContext
) {
    init {
        require(context.workerRuntime == descriptor) {
            "runtime execution request must match the admitted worker runtime"
        }
    }
}

sealed interface AgentCognitiveRuntimeExecutionResult {
    val usage: AgentRuntimeUsage

    data class Completed(
        val artifactKind: String,
        val payloadDigest: String,
        val sourceReferences: List<String>,
        override val usage: AgentRuntimeUsage
    ) : AgentCognitiveRuntimeExecutionResult {
        init {
            require(artifactKind.isNotBlank()) {
                "runtime artifact kind must not be blank"
            }
            require(artifactKind.toByteArray(StandardCharsets.UTF_8).size <= 128) {
                "runtime artifact kind exceeds bounded size"
            }
            require(payloadDigest.isNotBlank()) {
                "runtime payload digest must not be blank"
            }
            require(payloadDigest.toByteArray(StandardCharsets.UTF_8).size <= 256) {
                "runtime payload digest exceeds bounded size"
            }
            require(sourceReferences.isNotEmpty()) {
                "runtime result requires source provenance"
            }
            require(sourceReferences.size <= 48) {
                "runtime source provenance exceeds bounded count"
            }
            require(sourceReferences == sourceReferences.distinct().sorted()) {
                "runtime source provenance must be unique and canonical"
            }
            sourceReferences.forEach {
                require(it.isNotBlank()) {
                    "runtime source provenance reference must not be blank"
                }
                require(it.toByteArray(StandardCharsets.UTF_8).size <= 256) {
                    "runtime source provenance reference exceeds bounded size"
                }
            }
        }

        companion object {
            fun create(
                artifactKind: String,
                payloadDigest: String,
                sourceReferences: Collection<String>,
                usage: AgentRuntimeUsage
            ) = Completed(
                artifactKind = artifactKind,
                payloadDigest = payloadDigest,
                sourceReferences = sourceReferences.distinct().sorted(),
                usage = usage
            )
        }
    }

    data class Failed(
        val reason: String,
        override val usage: AgentRuntimeUsage
    ) : AgentCognitiveRuntimeExecutionResult {
        init {
            require(reason.isNotBlank()) {
                "runtime adapter failure reason must not be blank"
            }
            require(reason.toByteArray(StandardCharsets.UTF_8).size <= 1024) {
                "runtime adapter failure reason exceeds bounded size"
            }
        }
    }
}

fun interface AgentCognitiveRuntimeExecutionAdapter {
    fun run(request: AgentCognitiveRuntimeExecutionRequest): AgentCognitiveRuntimeExecutionResult
}

data class AgentCognitiveRuntimeRegistration(
    val descriptor: AgentWorkerRuntimeDescriptor,
    val available: () -> Boolean,
    val adapter: AgentCognitiveRuntimeExecutionAdapter
) {
    val cognitiveKind: AgentCognitiveRuntimeKind
        get() = descriptor.kind.toCognitiveRuntimeKind()
}

class AgentHeterogeneousRuntimeFabric(
    registrations: Collection<AgentCognitiveRuntimeRegistration>
) : AgentRuntimeAdapter {
    private val byIdentity: Map<String, AgentCognitiveRuntimeRegistration>

    init {
        require(registrations.isNotEmpty()) {
            "heterogeneous runtime fabric requires at least one registration"
        }
        require(registrations.size <= MAX_REGISTRATIONS) {
            "heterogeneous runtime fabric exceeds bounded registration count"
        }
        val keys = registrations.map { it.descriptor.canonicalRuntimeIdentity() }
        require(keys.distinct().size == keys.size) {
            "heterogeneous runtime registrations must have unique exact identities"
        }
        byIdentity = registrations.associateBy {
            it.descriptor.canonicalRuntimeIdentity()
        }
    }

    override fun run(context: AgentRuntimeContext): AgentRuntimeOutcome {
        val descriptor = context.workerRuntime
            ?: return fail(
                reason = "heterogeneous worker runtime descriptor missing",
                usage = AgentRuntimeUsage(0, 0, 0, 0, 0)
            )

        val registration = byIdentity[descriptor.canonicalRuntimeIdentity()]
            ?: return fail(
                reason = "heterogeneous worker runtime is not registered",
                usage = AgentRuntimeUsage(0, 0, 0, 0, 0)
            )

        if (registration.descriptor != descriptor) {
            return fail(
                reason = "heterogeneous worker runtime identity mismatch",
                usage = AgentRuntimeUsage(0, 0, 0, 0, 0)
            )
        }

        if (!registration.available()) {
            return fail(
                reason = "heterogeneous worker runtime unavailable",
                usage = AgentRuntimeUsage(0, 0, 0, 0, 0)
            )
        }

        val result = try {
            registration.adapter.run(
                AgentCognitiveRuntimeExecutionRequest(
                    descriptor = descriptor,
                    context = context
                )
            )
        } catch (_: Exception) {
            return fail(
                reason = "heterogeneous worker runtime adapter failed",
                usage = AgentRuntimeUsage(0, 0, 0, 0, 0)
            )
        }

        return when (result) {
            is AgentCognitiveRuntimeExecutionResult.Failed ->
                fail(result.reason, result.usage)

            is AgentCognitiveRuntimeExecutionResult.Completed ->
                AgentRuntimeOutcome.Completed(
                    kind = result.artifactKind,
                    payloadDigest = result.payloadDigest,
                    provenanceReferences = runtimeProvenance(
                        descriptor = descriptor,
                        sourceReferences = result.sourceReferences
                    ),
                    usage = result.usage
                )
        }
    }

    private fun fail(
        reason: String,
        usage: AgentRuntimeUsage
    ) = AgentRuntimeOutcome.Failed(
        reason = reason,
        usage = usage
    )

    private fun runtimeProvenance(
        descriptor: AgentWorkerRuntimeDescriptor,
        sourceReferences: List<String>
    ): List<String> {
        val refs = buildList {
            addAll(sourceReferences)
            add(
                "asf-runtime-kind:" +
                    descriptor.kind.toCognitiveRuntimeKind().name.lowercase()
            )
            add(
                "asf-runtime-id-sha256:" +
                    AsfIdentity.sha256(descriptor.runtimeId)
            )
            descriptor.modelId?.let {
                add("asf-model-id-sha256:" + AsfIdentity.sha256(it))
            }
        }.distinct().sorted()
        require(refs.size <= 64) {
            "heterogeneous runtime provenance exceeds artifact bound"
        }
        return refs
    }

    companion object {
        const val MAX_REGISTRATIONS = 64
    }
}

internal fun AgentWorkerRuntimeDescriptor.canonicalRuntimeIdentity(): String =
    AsfIdentity.sha256(
        "heterogeneous-runtime-v1",
        kind.name,
        runtimeId,
        modelId ?: ""
    )

object AgentCognitiveRuntimeAdapters {
    fun deterministicRule(
        block: (AgentCognitiveRuntimeExecutionRequest) -> AgentCognitiveRuntimeExecutionResult
    ) = AgentCognitiveRuntimeExecutionAdapter(block)

    fun retrievalGraph(
        block: (AgentCognitiveRuntimeExecutionRequest) -> AgentCognitiveRuntimeExecutionResult
    ) = AgentCognitiveRuntimeExecutionAdapter(block)

    fun embeddingReranker(
        block: (AgentCognitiveRuntimeExecutionRequest) -> AgentCognitiveRuntimeExecutionResult
    ) = AgentCognitiveRuntimeExecutionAdapter(block)

    fun onnx(
        block: (AgentCognitiveRuntimeExecutionRequest) -> AgentCognitiveRuntimeExecutionResult
    ) = AgentCognitiveRuntimeExecutionAdapter(block)

    fun llm(
        block: (AgentCognitiveRuntimeExecutionRequest) -> AgentCognitiveRuntimeExecutionResult
    ) = AgentCognitiveRuntimeExecutionAdapter(block)
}
