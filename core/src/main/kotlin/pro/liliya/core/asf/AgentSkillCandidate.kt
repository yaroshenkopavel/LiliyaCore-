package pro.liliya.core.asf

@JvmInline
value class AgentSkillCandidateId(val value: String) {
    init {
        require(value.isNotBlank())
        require(value.length <= 160)
    }
}

enum class AgentSkillCandidateState {
    PROPOSED,
    VALIDATING,
    EVALUATED,
    REJECTED,
    QUARANTINED,
    ELIGIBLE_FOR_GOVERNANCE;

    fun canTransitionTo(next: AgentSkillCandidateState): Boolean = when (this) {
        PROPOSED -> next == VALIDATING || next == REJECTED || next == QUARANTINED
        VALIDATING -> next == EVALUATED || next == REJECTED || next == QUARANTINED
        EVALUATED -> next == ELIGIBLE_FOR_GOVERNANCE || next == REJECTED || next == QUARANTINED
        REJECTED, QUARANTINED, ELIGIBLE_FOR_GOVERNANCE -> false
    }
}

data class AgentSkillCandidate(
    val id: AgentSkillCandidateId,
    val parentSkillReference: String?,
    val name: String,
    val version: Int,
    val inputKinds: List<String>,
    val outputKinds: List<String>,
    val provenanceReferences: List<String>,
    val state: AgentSkillCandidateState
) {
    init {
        require(name.isNotBlank() && name.length <= 128)
        require(version > 0)
        require(inputKinds.isNotEmpty() && inputKinds.size <= 32)
        require(outputKinds.isNotEmpty() && outputKinds.size <= 32)
        require(inputKinds == inputKinds.sorted() && inputKinds.distinct().size == inputKinds.size)
        require(outputKinds == outputKinds.sorted() && outputKinds.distinct().size == outputKinds.size)
        require(provenanceReferences.isNotEmpty() && provenanceReferences.size <= 64)
        require(provenanceReferences == provenanceReferences.sorted())
        require(provenanceReferences.distinct().size == provenanceReferences.size)
        require(id == deterministicId(
            parentSkillReference, name, version, inputKinds, outputKinds, provenanceReferences
        ))
    }

    fun transition(next: AgentSkillCandidateState): AgentSkillCandidate {
        require(state.canTransitionTo(next)) { "invalid skill candidate transition: $state -> $next" }
        return copy(state = next)
    }

    companion object {
        fun create(
            parentSkillReference: String?,
            name: String,
            version: Int,
            inputKinds: Collection<String>,
            outputKinds: Collection<String>,
            provenanceReferences: Collection<String>
        ): AgentSkillCandidate {
            val inputs = inputKinds.sorted()
            val outputs = outputKinds.sorted()
            val provenance = provenanceReferences.sorted()
            return AgentSkillCandidate(
                id = deterministicId(parentSkillReference, name, version, inputs, outputs, provenance),
                parentSkillReference = parentSkillReference,
                name = name,
                version = version,
                inputKinds = inputs,
                outputKinds = outputs,
                provenanceReferences = provenance,
                state = AgentSkillCandidateState.PROPOSED
            )
        }

        private fun deterministicId(
            parentSkillReference: String?,
            name: String,
            version: Int,
            inputKinds: List<String>,
            outputKinds: List<String>,
            provenanceReferences: List<String>
        ) = AgentSkillCandidateId(
            "asf-skill-candidate-" + AsfIdentity.sha256(
                "skill-candidate-v1",
                parentSkillReference ?: "none",
                name,
                version.toString(),
                *inputKinds.toTypedArray(),
                *outputKinds.toTypedArray(),
                *provenanceReferences.toTypedArray()
            )
        )
    }
}

class AgentSkillCandidateRepository {
    private val byId = linkedMapOf<AgentSkillCandidateId, AgentSkillCandidate>()

    @Synchronized
    fun add(candidate: AgentSkillCandidate) {
        require(candidate.id !in byId) { "duplicate skill candidate" }
        byId[candidate.id] = candidate
    }

    @Synchronized
    fun replace(candidate: AgentSkillCandidate) {
        require(candidate.id in byId) { "unknown skill candidate" }
        byId[candidate.id] = candidate
    }

    @Synchronized
    fun get(id: AgentSkillCandidateId): AgentSkillCandidate? = byId[id]

    @Synchronized
    fun snapshot(): List<AgentSkillCandidate> = byId.values.sortedBy { it.id.value }
}
