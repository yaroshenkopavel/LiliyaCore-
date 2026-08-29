package pro.liliya.core.autonomy

import pro.liliya.core.foundation.FoundationComposition

interface AutonomyOwnership {
    val proposal: AutonomyProposal
    val generation: AutonomyGeneration
    fun remove(): Boolean
}

sealed interface AutonomyInstallResult {
    data class Installed(val ownership: AutonomyOwnership) : AutonomyInstallResult
    data class Rejected(val reason: String) : AutonomyInstallResult
}

class AutonomyComposition(
    private val foundation: FoundationComposition
) {
    private val store = AutonomyStore(foundation.observability)

    fun install(proposal: AutonomyProposal): AutonomyInstallResult {
        val installContext = foundation.rootContext(
            operation = "installAutonomyProposal",
            component = "Autonomy",
            metadata = proposalMetadata(proposal)
        )

        return when (val result = store.register(proposal, installContext)) {
            is AutonomyRegistrationResult.Registered -> {
                val registration = result.registration
                AutonomyInstallResult.Installed(
                    ownership = object : AutonomyOwnership {
                        override val proposal: AutonomyProposal = registration.proposal
                        override val generation: AutonomyGeneration = registration.generation

                        override fun remove(): Boolean = registration.remove(
                            foundation.childContext(
                                parent = installContext,
                                component = "Autonomy",
                                operation = "removeAutonomyProposal",
                                metadata = proposalMetadata(proposal) +
                                    ("autonomyGeneration" to generation.value.toString())
                            )
                        )
                    }
                )
            }

            is AutonomyRegistrationResult.Rejected -> AutonomyInstallResult.Rejected(result.reason)
        }
    }

    fun find(id: AutonomyProposalId): AutonomyProposal? = store.find(id)
    fun inspect(id: AutonomyProposalId): AutonomySnapshot? = store.inspect(id)
    fun contains(id: AutonomyProposalId): Boolean = store.contains(id)
    fun snapshot(): List<AutonomyProposal> = store.snapshot()
    fun snapshotEntries(): List<AutonomySnapshot> = store.snapshotEntries()

    private fun proposalMetadata(proposal: AutonomyProposal): Map<String, String> = buildMap {
        put("autonomyProposalId", proposal.id.value)
        put("autonomyPriority", proposal.priority.name)
        put("autonomyMaxAttempts", proposal.budget.maxAttempts.toString())
        put("createdAt", proposal.createdAt.toString())
        when (val origin = proposal.origin) {
            is AutonomyOrigin.Reflection -> {
                put("autonomyOriginType", "reflection")
                put("reflectionRecordId", origin.recordId.value)
                put("reflectionGeneration", origin.generation.value.toString())
            }
            is AutonomyOrigin.Declared -> {
                put("autonomyOriginType", "declared")
                put("autonomySourceId", origin.sourceId.value)
                origin.sourceReference?.let { put("autonomySourceReference", it.value) }
            }
        }
    }
}
