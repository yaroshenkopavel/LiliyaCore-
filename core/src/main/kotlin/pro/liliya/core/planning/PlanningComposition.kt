package pro.liliya.core.planning

import pro.liliya.core.foundation.FoundationComposition

interface PlanningOwnership {
    val proposal: PlanningProposal
    val generation: PlanningGeneration
    fun remove(): Boolean
}

sealed interface PlanningInstallResult {
    data class Installed(val ownership: PlanningOwnership) : PlanningInstallResult
    data class Rejected(val reason: String) : PlanningInstallResult
}

class PlanningComposition(
    private val foundation: FoundationComposition
) {
    private val store = PlanningProposalStore(foundation.observability)

    fun install(proposal: PlanningProposal): PlanningInstallResult {
        val installContext = foundation.rootContext(
            operation = "installPlanningProposal",
            component = "Planning",
            metadata = proposalMetadata(proposal)
        )
        return when (val result = store.register(proposal, installContext)) {
            is PlanningProposalRegistrationResult.Registered -> {
                val registration = result.registration
                PlanningInstallResult.Installed(
                    ownership = object : PlanningOwnership {
                        override val proposal: PlanningProposal = registration.proposal
                        override val generation: PlanningGeneration = registration.generation

                        override fun remove(): Boolean = registration.remove(
                            foundation.childContext(
                                parent = installContext,
                                component = "Planning",
                                operation = "removePlanningProposal",
                                metadata = proposalMetadata(proposal) +
                                    ("planningGeneration" to generation.value.toString())
                            )
                        )
                    }
                )
            }

            is PlanningProposalRegistrationResult.Rejected ->
                PlanningInstallResult.Rejected(result.reason)
        }
    }

    fun find(id: PlanningProposalId): PlanningProposal? = store.find(id)
    fun inspect(id: PlanningProposalId): PlanningProposalSnapshot? = store.inspect(id)
    fun contains(id: PlanningProposalId): Boolean = store.contains(id)
    fun snapshot(): List<PlanningProposal> = store.snapshot()
    fun snapshotEntries(): List<PlanningProposalSnapshot> = store.snapshotEntries()

    private fun proposalMetadata(proposal: PlanningProposal): Map<String, String> = buildMap {
        put("planningProposalId", proposal.id.value)
        put("planningSourceId", proposal.origin.sourceId.value)
        proposal.origin.sourceReference?.let { put("planningSourceReference", it.value) }
        put("planningStepCount", proposal.steps.size.toString())
        put("createdAt", proposal.createdAt.toString())
    }
}
