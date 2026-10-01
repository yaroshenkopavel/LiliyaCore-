package pro.liliya.core.asf

@JvmInline
value class AgentBlueprintCandidateId(val value: String) {
    init {
        require(value.isNotBlank()) { "blueprint candidate id must not be blank" }
        require(value.length <= 160) { "blueprint candidate id exceeds bounded size" }
    }
}

enum class AgentBlueprintCandidateState {
    PROPOSED,
    VALIDATING,
    EVALUATED,
    REJECTED,
    QUARANTINED,
    ELIGIBLE_FOR_GOVERNANCE;

    fun canTransitionTo(next: AgentBlueprintCandidateState): Boolean = when (this) {
        PROPOSED -> next == VALIDATING || next == REJECTED || next == QUARANTINED
        VALIDATING -> next == EVALUATED || next == REJECTED || next == QUARANTINED
        EVALUATED -> next == ELIGIBLE_FOR_GOVERNANCE || next == REJECTED || next == QUARANTINED
        REJECTED, QUARANTINED, ELIGIBLE_FOR_GOVERNANCE -> false
    }
}

data class AgentBlueprintCandidate(
    val id: AgentBlueprintCandidateId,
    val parentBlueprint: AgentBlueprintReference,
    val proposedBlueprint: AgentBlueprint,
    val proposedBudget: AgentWorkBudget,
    val provenanceReferences: List<String>,
    val state: AgentBlueprintCandidateState
) {
    init {
        require(provenanceReferences.isNotEmpty()) {
            "blueprint candidate requires provenance"
        }
        require(provenanceReferences.size <= 64)
        require(provenanceReferences.distinct().size == provenanceReferences.size)
        require(provenanceReferences == provenanceReferences.sorted())
        provenanceReferences.forEach {
            require(it.isNotBlank() && it.length <= 256)
        }
        require(
            id == deterministicId(
                parentBlueprint,
                proposedBlueprint,
                proposedBudget,
                provenanceReferences
            )
        ) { "blueprint candidate id does not match deterministic content identity" }
    }

    fun transition(next: AgentBlueprintCandidateState): AgentBlueprintCandidate {
        require(state.canTransitionTo(next)) {
            "invalid blueprint candidate transition: $state -> $next"
        }
        return copy(state = next)
    }

    companion object {
        fun create(
            parentBlueprint: AgentBlueprintReference,
            proposedBlueprint: AgentBlueprint,
            proposedBudget: AgentWorkBudget,
            provenanceReferences: Collection<String>
        ): AgentBlueprintCandidate {
            val provenance = provenanceReferences.sorted()
            return AgentBlueprintCandidate(
                id = deterministicId(
                    parentBlueprint,
                    proposedBlueprint,
                    proposedBudget,
                    provenance
                ),
                parentBlueprint = parentBlueprint,
                proposedBlueprint = proposedBlueprint,
                proposedBudget = proposedBudget,
                provenanceReferences = provenance,
                state = AgentBlueprintCandidateState.PROPOSED
            )
        }

        private fun deterministicId(
            parentBlueprint: AgentBlueprintReference,
            proposedBlueprint: AgentBlueprint,
            proposedBudget: AgentWorkBudget,
            provenanceReferences: List<String>
        ) = AgentBlueprintCandidateId(
            "asf-blueprint-candidate-" + AsfIdentity.sha256(
                "blueprint-candidate-v1",
                parentBlueprint.id.value,
                parentBlueprint.version.value.toString(),
                proposedBlueprint.id.value,
                proposedBlueprint.version.value.toString(),
                proposedBudget.maxWallClockMillis.toString(),
                proposedBudget.maxInferenceUnits.toString(),
                proposedBudget.maxContextBytes.toString(),
                proposedBudget.maxRetrievalItems.toString(),
                proposedBudget.maxArtifacts.toString(),
                proposedBudget.maxDescendants.toString(),
                *provenanceReferences.toTypedArray()
            )
        )
    }
}

enum class AgentBlueprintCandidateValidation {
    ADMISSIBLE,
    SCOPE_WIDENING,
    BUDGET_WIDENING,
    PARENT_MISMATCH
}

object AgentBlueprintCandidateValidator {
    fun validate(
        candidate: AgentBlueprintCandidate,
        parentBlueprint: AgentBlueprint,
        parentBudget: AgentWorkBudget
    ): AgentBlueprintCandidateValidation {
        if (
            candidate.parentBlueprint.id != parentBlueprint.id ||
            candidate.parentBlueprint.version != parentBlueprint.version
        ) {
            return AgentBlueprintCandidateValidation.PARENT_MISMATCH
        }
        if (!candidate.proposedBlueprint.cognitiveScope.isWithin(parentBlueprint.cognitiveScope)) {
            return AgentBlueprintCandidateValidation.SCOPE_WIDENING
        }
        if (!candidate.proposedBudget.isWithin(parentBudget)) {
            return AgentBlueprintCandidateValidation.BUDGET_WIDENING
        }
        return AgentBlueprintCandidateValidation.ADMISSIBLE
    }
}

class AgentBlueprintCandidateRepository {
    private val byId = linkedMapOf<AgentBlueprintCandidateId, AgentBlueprintCandidate>()

    @Synchronized
    fun add(candidate: AgentBlueprintCandidate) {
        require(candidate.id !in byId) { "duplicate blueprint candidate" }
        byId[candidate.id] = candidate
    }

    @Synchronized
    fun replace(candidate: AgentBlueprintCandidate) {
        require(candidate.id in byId) { "unknown blueprint candidate" }
        byId[candidate.id] = candidate
    }

    @Synchronized
    fun get(id: AgentBlueprintCandidateId): AgentBlueprintCandidate? = byId[id]

    @Synchronized
    fun snapshot(): List<AgentBlueprintCandidate> =
        byId.values.sortedBy { it.id.value }
}
