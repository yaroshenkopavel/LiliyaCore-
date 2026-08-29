package pro.liliya.core.reasoning

import pro.liliya.core.foundation.FoundationComposition

interface ReasoningOwnership {
    val artifact: ReasoningArtifact
    val generation: ReasoningGeneration
    fun remove(): Boolean
}

sealed interface ReasoningInstallResult {
    data class Installed(val ownership: ReasoningOwnership) : ReasoningInstallResult
    data class Rejected(val reason: String) : ReasoningInstallResult
}

class ReasoningComposition(
    private val foundation: FoundationComposition
) {
    private val store = ReasoningArtifactStore(foundation.observability)

    fun install(artifact: ReasoningArtifact): ReasoningInstallResult {
        val installContext = foundation.rootContext(
            operation = "installReasoningArtifact",
            component = "Reasoning",
            metadata = artifactMetadata(artifact)
        )
        return when (val result = store.register(artifact, installContext)) {
            is ReasoningArtifactRegistrationResult.Registered -> {
                val registration = result.registration
                ReasoningInstallResult.Installed(
                    ownership = object : ReasoningOwnership {
                        override val artifact: ReasoningArtifact = registration.artifact
                        override val generation: ReasoningGeneration = registration.generation

                        override fun remove(): Boolean = registration.remove(
                            foundation.childContext(
                                parent = installContext,
                                component = "Reasoning",
                                operation = "removeReasoningArtifact",
                                metadata = artifactMetadata(artifact) +
                                    ("reasoningGeneration" to generation.value.toString())
                            )
                        )
                    }
                )
            }

            is ReasoningArtifactRegistrationResult.Rejected ->
                ReasoningInstallResult.Rejected(result.reason)
        }
    }

    fun find(id: ReasoningArtifactId): ReasoningArtifact? = store.find(id)
    fun inspect(id: ReasoningArtifactId): ReasoningArtifactSnapshot? = store.inspect(id)
    fun contains(id: ReasoningArtifactId): Boolean = store.contains(id)
    fun snapshot(): List<ReasoningArtifact> = store.snapshot()
    fun snapshotEntries(): List<ReasoningArtifactSnapshot> = store.snapshotEntries()

    private fun artifactMetadata(artifact: ReasoningArtifact): Map<String, String> = buildMap {
        put("reasoningArtifactId", artifact.id.value)
        put("reasoningSourceId", artifact.origin.sourceId.value)
        artifact.origin.sourceReference?.let { put("reasoningSourceReference", it.value) }
        put("reasoningPremiseCount", artifact.premises.size.toString())
        put("createdAt", artifact.createdAt.toString())
    }
}
