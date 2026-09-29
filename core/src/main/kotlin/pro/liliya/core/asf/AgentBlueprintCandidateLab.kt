package pro.liliya.core.asf

data class AgentBlueprintCandidateEvaluation(
    val candidateId: AgentBlueprintCandidateId,
    val baselineBlueprint: AgentBlueprintReference,
    val baseline: AgentBlueprintEvaluationVector,
    val candidate: AgentBlueprintEvaluationVector,
    val comparison: AgentBlueprintEvaluationComparison,
    val evidenceReferences: List<String>
) {
    init {
        require(
            comparison == AgentBlueprintEvaluationComparator.compare(
                baseline,
                candidate
            )
        ) { "candidate evaluation comparison must match vectors" }
        require(evidenceReferences.isNotEmpty()) {
            "candidate evaluation requires evidence provenance"
        }
        require(evidenceReferences.size <= 64) {
            "too many candidate evaluation evidence references"
        }
        require(evidenceReferences.distinct().size == evidenceReferences.size)
        require(evidenceReferences == evidenceReferences.sorted())
        evidenceReferences.forEach {
            require(it.isNotBlank() && it.length <= 256)
        }
    }

    companion object {
        fun create(
            candidateId: AgentBlueprintCandidateId,
            baselineBlueprint: AgentBlueprintReference,
            baseline: AgentBlueprintEvaluationVector,
            candidate: AgentBlueprintEvaluationVector,
            evidenceReferences: Collection<String>
        ) = AgentBlueprintCandidateEvaluation(
            candidateId = candidateId,
            baselineBlueprint = baselineBlueprint,
            baseline = baseline,
            candidate = candidate,
            comparison = AgentBlueprintEvaluationComparator.compare(
                baseline,
                candidate
            ),
            evidenceReferences = evidenceReferences.sorted()
        )
    }
}

class AgentBlueprintCandidateLab(
    private val candidates: AgentBlueprintCandidateRepository =
        AgentBlueprintCandidateRepository()
) {
    private val evaluations =
        linkedMapOf<AgentBlueprintCandidateId, AgentBlueprintCandidateEvaluation>()

    @Synchronized
    fun propose(candidate: AgentBlueprintCandidate) {
        require(candidate.state == AgentBlueprintCandidateState.PROPOSED) {
            "candidate laboratory accepts only proposed candidates"
        }
        candidates.add(candidate)
    }

    @Synchronized
    fun validate(
        id: AgentBlueprintCandidateId,
        parentBlueprint: AgentBlueprint,
        parentBudget: AgentWorkBudget
    ): AgentBlueprintCandidateValidation {
        val current = requireNotNull(candidates.get(id)) {
            "unknown blueprint candidate"
        }
        require(current.state == AgentBlueprintCandidateState.PROPOSED) {
            "candidate must be proposed before validation"
        }

        val validating =
            current.transition(AgentBlueprintCandidateState.VALIDATING)
        candidates.replace(validating)

        val result = AgentBlueprintCandidateValidator.validate(
            validating,
            parentBlueprint,
            parentBudget
        )
        if (result != AgentBlueprintCandidateValidation.ADMISSIBLE) {
            candidates.replace(
                validating.transition(AgentBlueprintCandidateState.REJECTED)
            )
        }
        return result
    }

    @Synchronized
    fun recordEvaluation(
        id: AgentBlueprintCandidateId,
        baselineBlueprint: AgentBlueprintReference,
        baseline: AgentBlueprintEvaluationVector,
        candidateVector: AgentBlueprintEvaluationVector,
        evidenceReferences: Collection<String>
    ): AgentBlueprintCandidateEvaluation {
        val current = requireNotNull(candidates.get(id)) {
            "unknown blueprint candidate"
        }
        require(current.state == AgentBlueprintCandidateState.VALIDATING) {
            "candidate must be validating before evaluation"
        }
        require(baselineBlueprint == current.parentBlueprint) {
            "evaluation baseline must match candidate parent blueprint"
        }
        require(id !in evaluations) {
            "candidate evaluation already exists"
        }

        val evaluation = AgentBlueprintCandidateEvaluation.create(
            id,
            baselineBlueprint,
            baseline,
            candidateVector,
            evidenceReferences
        )
        evaluations[id] = evaluation
        candidates.replace(
            current.transition(AgentBlueprintCandidateState.EVALUATED)
        )
        return evaluation
    }

    @Synchronized
    fun markEligibleForGovernance(
        id: AgentBlueprintCandidateId
    ): AgentBlueprintCandidate {
        val current = requireNotNull(candidates.get(id)) {
            "unknown blueprint candidate"
        }
        require(current.state == AgentBlueprintCandidateState.EVALUATED) {
            "candidate must be evaluated before governance eligibility"
        }
        require(id in evaluations) {
            "candidate requires structured evaluation"
        }

        val eligible = current.transition(
            AgentBlueprintCandidateState.ELIGIBLE_FOR_GOVERNANCE
        )
        candidates.replace(eligible)
        return eligible
    }

    @Synchronized
    fun quarantine(
        id: AgentBlueprintCandidateId
    ): AgentBlueprintCandidate {
        val current = requireNotNull(candidates.get(id)) {
            "unknown blueprint candidate"
        }
        require(
            current.state in setOf(
                AgentBlueprintCandidateState.PROPOSED,
                AgentBlueprintCandidateState.VALIDATING,
                AgentBlueprintCandidateState.EVALUATED
            )
        ) { "terminal candidate cannot be quarantined" }

        val quarantined = current.transition(
            AgentBlueprintCandidateState.QUARANTINED
        )
        candidates.replace(quarantined)
        return quarantined
    }

    @Synchronized
    fun candidate(
        id: AgentBlueprintCandidateId
    ): AgentBlueprintCandidate? = candidates.get(id)

    @Synchronized
    fun evaluation(
        id: AgentBlueprintCandidateId
    ): AgentBlueprintCandidateEvaluation? = evaluations[id]
}
