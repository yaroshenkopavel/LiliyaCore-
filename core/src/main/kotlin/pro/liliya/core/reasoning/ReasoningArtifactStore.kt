package pro.liliya.core.reasoning

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.logging.LogContext
import pro.liliya.core.observability.CoreObservability

internal interface ReasoningArtifactRegistration {
    val artifact: ReasoningArtifact
    val generation: ReasoningGeneration
    fun remove(context: LogContext): Boolean
}

internal sealed interface ReasoningArtifactRegistrationResult {
    data class Registered(val registration: ReasoningArtifactRegistration) : ReasoningArtifactRegistrationResult
    data class Rejected(val reason: String) : ReasoningArtifactRegistrationResult
}

internal class ReasoningArtifactStore(
    private val observability: CoreObservability
) {
    private data class Entry(
        val generation: ReasoningGeneration,
        val artifact: ReasoningArtifact
    )

    private val nextGeneration = AtomicLong(0)
    private val artifacts = ConcurrentHashMap<ReasoningArtifactId, Entry>()

    fun register(
        artifact: ReasoningArtifact,
        context: LogContext
    ): ReasoningArtifactRegistrationResult {
        val entry = Entry(
            generation = ReasoningGeneration(nextGeneration.incrementAndGet()),
            artifact = artifact
        )
        val previous = artifacts.putIfAbsent(artifact.id, entry)
        if (previous != null) {
            val reason = "reasoning artifact id is already registered"
            observability.record(
                DiagnosticSeverity.WARNING,
                "REASONING_ARTIFACT_REGISTRATION_REJECTED",
                reason,
                context,
                metadata(artifact, previous.generation) + ("rejectionReason" to reason)
            )
            return ReasoningArtifactRegistrationResult.Rejected(reason)
        }

        observability.record(
            DiagnosticSeverity.INFO,
            "REASONING_ARTIFACT_REGISTERED",
            "reasoning artifact registered",
            context,
            metadata(artifact, entry.generation)
        )

        return ReasoningArtifactRegistrationResult.Registered(
            object : ReasoningArtifactRegistration {
                override val artifact: ReasoningArtifact = artifact
                override val generation: ReasoningGeneration = entry.generation

                override fun remove(context: LogContext): Boolean {
                    val removed = artifacts.remove(artifact.id, entry)
                    observability.record(
                        if (removed) DiagnosticSeverity.INFO else DiagnosticSeverity.WARNING,
                        if (removed) "REASONING_ARTIFACT_REMOVED" else "REASONING_ARTIFACT_REMOVAL_REJECTED",
                        if (removed) "reasoning artifact removed" else "reasoning artifact registration is no longer current",
                        context,
                        metadata(artifact, entry.generation)
                    )
                    return removed
                }
            }
        )
    }

    fun find(id: ReasoningArtifactId): ReasoningArtifact? = artifacts[id]?.artifact

    fun inspect(id: ReasoningArtifactId): ReasoningArtifactSnapshot? = artifacts[id]?.let { entry ->
        ReasoningArtifactSnapshot(entry.artifact, entry.generation)
    }

    fun contains(id: ReasoningArtifactId): Boolean = artifacts.containsKey(id)

    fun snapshot(): List<ReasoningArtifact> = snapshotEntries().map { it.artifact }

    fun snapshotEntries(): List<ReasoningArtifactSnapshot> = artifacts.values
        .map { ReasoningArtifactSnapshot(it.artifact, it.generation) }
        .sortedWith(compareBy<ReasoningArtifactSnapshot> { it.artifact.createdAt }.thenBy { it.artifact.id.value })

    private fun metadata(
        artifact: ReasoningArtifact,
        generation: ReasoningGeneration
    ): Map<String, String> = buildMap {
        put("reasoningArtifactId", artifact.id.value)
        put("reasoningGeneration", generation.value.toString())
        put("reasoningSourceId", artifact.origin.sourceId.value)
        artifact.origin.sourceReference?.let { put("reasoningSourceReference", it.value) }
        put("reasoningPremiseCount", artifact.premises.size.toString())
        put("createdAt", artifact.createdAt.toString())
    }
}
