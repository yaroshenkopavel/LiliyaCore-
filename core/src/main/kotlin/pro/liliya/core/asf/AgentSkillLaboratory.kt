package pro.liliya.core.asf

enum class AgentLaboratoryMetricRelation {
    BETTER,
    WORSE,
    EQUAL
}

data class AgentLaboratoryEvaluationVector(
    val completed: Boolean,
    val challengedFindings: Int,
    val unresolvedConflictFindings: Int,
    val wallClockMillis: Long,
    val inferenceUnits: Long,
    val contextBytes: Int,
    val artifactCount: Int,
    val retryCount: Int,
    val cancellationCount: Int,
    val workerCount: Int
) {
    init {
        require(challengedFindings >= 0)
        require(unresolvedConflictFindings >= 0)
        require(wallClockMillis >= 0)
        require(inferenceUnits >= 0)
        require(contextBytes >= 0)
        require(artifactCount >= 0)
        require(retryCount >= 0)
        require(cancellationCount >= 0)
        require(workerCount > 0)
    }
}

data class AgentLaboratoryEvaluationComparison(
    val completion: AgentLaboratoryMetricRelation,
    val challengedFindings: AgentLaboratoryMetricRelation,
    val unresolvedConflicts: AgentLaboratoryMetricRelation,
    val wallClock: AgentLaboratoryMetricRelation,
    val inference: AgentLaboratoryMetricRelation,
    val context: AgentLaboratoryMetricRelation,
    val artifacts: AgentLaboratoryMetricRelation,
    val retries: AgentLaboratoryMetricRelation,
    val cancellations: AgentLaboratoryMetricRelation,
    val workerCount: AgentLaboratoryMetricRelation
) {
    val mixedTradeoffs: Boolean
        get() {
            val values = listOf(
                completion,
                challengedFindings,
                unresolvedConflicts,
                wallClock,
                inference,
                context,
                artifacts,
                retries,
                cancellations,
                workerCount
            )
            return AgentLaboratoryMetricRelation.BETTER in values &&
                AgentLaboratoryMetricRelation.WORSE in values
        }
}

object AgentLaboratoryEvaluationComparator {
    fun compare(
        baseline: AgentLaboratoryEvaluationVector,
        candidate: AgentLaboratoryEvaluationVector
    ) = AgentLaboratoryEvaluationComparison(
        completion = compareBoolean(baseline.completed, candidate.completed),
        challengedFindings = lowerIsBetter(
            baseline.challengedFindings,
            candidate.challengedFindings
        ),
        unresolvedConflicts = lowerIsBetter(
            baseline.unresolvedConflictFindings,
            candidate.unresolvedConflictFindings
        ),
        wallClock = lowerIsBetter(baseline.wallClockMillis, candidate.wallClockMillis),
        inference = lowerIsBetter(baseline.inferenceUnits, candidate.inferenceUnits),
        context = lowerIsBetter(baseline.contextBytes, candidate.contextBytes),
        artifacts = lowerIsBetter(baseline.artifactCount, candidate.artifactCount),
        retries = lowerIsBetter(baseline.retryCount, candidate.retryCount),
        cancellations = lowerIsBetter(
            baseline.cancellationCount,
            candidate.cancellationCount
        ),
        workerCount = lowerIsBetter(baseline.workerCount, candidate.workerCount)
    )

    private fun compareBoolean(
        baseline: Boolean,
        candidate: Boolean
    ) = when {
        baseline == candidate -> AgentLaboratoryMetricRelation.EQUAL
        candidate -> AgentLaboratoryMetricRelation.BETTER
        else -> AgentLaboratoryMetricRelation.WORSE
    }

    private fun lowerIsBetter(baseline: Long, candidate: Long) = when {
        candidate < baseline -> AgentLaboratoryMetricRelation.BETTER
        candidate > baseline -> AgentLaboratoryMetricRelation.WORSE
        else -> AgentLaboratoryMetricRelation.EQUAL
    }

    private fun lowerIsBetter(baseline: Int, candidate: Int) =
        lowerIsBetter(baseline.toLong(), candidate.toLong())
}

enum class AgentSkillCandidateValidation {
    ADMISSIBLE,
    PARENT_MISMATCH
}

data class AgentSkillCandidateEvaluation(
    val candidateId: AgentSkillCandidateId,
    val baselineSkillReference: String?,
    val baseline: AgentLaboratoryEvaluationVector,
    val candidate: AgentLaboratoryEvaluationVector,
    val comparison: AgentLaboratoryEvaluationComparison,
    val evidenceReferences: List<String>
) {
    init {
        require(
            comparison == AgentLaboratoryEvaluationComparator.compare(
                baseline,
                candidate
            )
        )
        require(evidenceReferences.isNotEmpty() && evidenceReferences.size <= 64)
        require(evidenceReferences == evidenceReferences.sorted())
        require(evidenceReferences.distinct().size == evidenceReferences.size)
    }

    companion object {
        fun create(
            candidateId: AgentSkillCandidateId,
            baselineSkillReference: String?,
            baseline: AgentLaboratoryEvaluationVector,
            candidate: AgentLaboratoryEvaluationVector,
            evidenceReferences: Collection<String>
        ) = AgentSkillCandidateEvaluation(
            candidateId = candidateId,
            baselineSkillReference = baselineSkillReference,
            baseline = baseline,
            candidate = candidate,
            comparison = AgentLaboratoryEvaluationComparator.compare(
                baseline,
                candidate
            ),
            evidenceReferences = evidenceReferences.sorted()
        )
    }
}

data class AgentCouncilEvaluation(
    val rootTaskId: AgentRootTaskId,
    val baseline: AgentLaboratoryEvaluationVector,
    val council: AgentLaboratoryEvaluationVector,
    val comparison: AgentLaboratoryEvaluationComparison,
    val evidenceReferences: List<String>
) {
    init {
        require(
            comparison == AgentLaboratoryEvaluationComparator.compare(
                baseline,
                council
            )
        )
        require(evidenceReferences.isNotEmpty() && evidenceReferences.size <= 64)
        require(evidenceReferences == evidenceReferences.sorted())
        require(evidenceReferences.distinct().size == evidenceReferences.size)
    }

    companion object {
        fun create(
            rootTaskId: AgentRootTaskId,
            baseline: AgentLaboratoryEvaluationVector,
            council: AgentLaboratoryEvaluationVector,
            evidenceReferences: Collection<String>
        ) = AgentCouncilEvaluation(
            rootTaskId = rootTaskId,
            baseline = baseline,
            council = council,
            comparison = AgentLaboratoryEvaluationComparator.compare(
                baseline,
                council
            ),
            evidenceReferences = evidenceReferences.sorted()
        )
    }
}

class AgentSkillCandidateLab(
    private val candidates: AgentSkillCandidateRepository =
        AgentSkillCandidateRepository()
) {
    private val evaluations =
        linkedMapOf<AgentSkillCandidateId, AgentSkillCandidateEvaluation>()

    @Synchronized
    fun propose(candidate: AgentSkillCandidate) {
        require(candidate.state == AgentSkillCandidateState.PROPOSED)
        candidates.add(candidate)
    }

    @Synchronized
    fun validate(
        id: AgentSkillCandidateId,
        expectedParentSkillReference: String?
    ): AgentSkillCandidateValidation {
        val current = requireNotNull(candidates.get(id))
        require(current.state == AgentSkillCandidateState.PROPOSED)

        val validating = current.transition(AgentSkillCandidateState.VALIDATING)
        candidates.replace(validating)

        if (validating.parentSkillReference != expectedParentSkillReference) {
            candidates.replace(
                validating.transition(AgentSkillCandidateState.REJECTED)
            )
            return AgentSkillCandidateValidation.PARENT_MISMATCH
        }

        return AgentSkillCandidateValidation.ADMISSIBLE
    }

    @Synchronized
    fun recordEvaluation(
        id: AgentSkillCandidateId,
        baseline: AgentLaboratoryEvaluationVector,
        candidateVector: AgentLaboratoryEvaluationVector,
        evidenceReferences: Collection<String>
    ): AgentSkillCandidateEvaluation {
        val current = requireNotNull(candidates.get(id))
        require(current.state == AgentSkillCandidateState.VALIDATING)
        require(id !in evaluations)

        val evaluation = AgentSkillCandidateEvaluation.create(
            candidateId = id,
            baselineSkillReference = current.parentSkillReference,
            baseline = baseline,
            candidate = candidateVector,
            evidenceReferences = evidenceReferences
        )
        evaluations[id] = evaluation
        candidates.replace(
            current.transition(AgentSkillCandidateState.EVALUATED)
        )
        return evaluation
    }

    @Synchronized
    fun markEligibleForGovernance(
        id: AgentSkillCandidateId
    ): AgentSkillCandidate {
        val current = requireNotNull(candidates.get(id))
        require(current.state == AgentSkillCandidateState.EVALUATED)
        require(id in evaluations)

        val eligible = current.transition(
            AgentSkillCandidateState.ELIGIBLE_FOR_GOVERNANCE
        )
        candidates.replace(eligible)
        return eligible
    }

    @Synchronized
    fun quarantine(id: AgentSkillCandidateId): AgentSkillCandidate {
        val current = requireNotNull(candidates.get(id))
        require(
            current.state in setOf(
                AgentSkillCandidateState.PROPOSED,
                AgentSkillCandidateState.VALIDATING,
                AgentSkillCandidateState.EVALUATED
            )
        )
        val quarantined = current.transition(AgentSkillCandidateState.QUARANTINED)
        candidates.replace(quarantined)
        return quarantined
    }

    @Synchronized
    fun candidate(id: AgentSkillCandidateId): AgentSkillCandidate? =
        candidates.get(id)

    @Synchronized
    fun evaluation(id: AgentSkillCandidateId): AgentSkillCandidateEvaluation? =
        evaluations[id]
}
