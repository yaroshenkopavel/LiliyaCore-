package pro.liliya.core.asf

data class AgentBlueprintEvaluationVector(
    val completed: Boolean,
    val challengedFindings: Int,
    val unresolvedConflictFindings: Int,
    val wallClockMillis: Long,
    val inferenceUnits: Long,
    val contextBytes: Int,
    val artifactCount: Int,
    val retryCount: Int
) {
    init {
        require(challengedFindings >= 0)
        require(unresolvedConflictFindings >= 0)
        require(wallClockMillis >= 0)
        require(inferenceUnits >= 0)
        require(contextBytes >= 0)
        require(artifactCount >= 0)
        require(retryCount >= 0)
    }
}

enum class AgentBlueprintEvaluationRelation {
    BETTER,
    WORSE,
    EQUAL
}

data class AgentBlueprintEvaluationComparison(
    val completion: AgentBlueprintEvaluationRelation,
    val challengedFindings: AgentBlueprintEvaluationRelation,
    val unresolvedConflicts: AgentBlueprintEvaluationRelation,
    val wallClock: AgentBlueprintEvaluationRelation,
    val inference: AgentBlueprintEvaluationRelation,
    val context: AgentBlueprintEvaluationRelation,
    val artifacts: AgentBlueprintEvaluationRelation,
    val retries: AgentBlueprintEvaluationRelation
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
                retries
            )
            return AgentBlueprintEvaluationRelation.BETTER in values &&
                AgentBlueprintEvaluationRelation.WORSE in values
        }
}

object AgentBlueprintEvaluationComparator {
    fun compare(
        baseline: AgentBlueprintEvaluationVector,
        candidate: AgentBlueprintEvaluationVector
    ) = AgentBlueprintEvaluationComparison(
        completion = compareBoolean(baseline.completed, candidate.completed),
        challengedFindings = lowerIsBetter(
            baseline.challengedFindings,
            candidate.challengedFindings
        ),
        unresolvedConflicts = lowerIsBetter(
            baseline.unresolvedConflictFindings,
            candidate.unresolvedConflictFindings
        ),
        wallClock = lowerIsBetter(
            baseline.wallClockMillis,
            candidate.wallClockMillis
        ),
        inference = lowerIsBetter(
            baseline.inferenceUnits,
            candidate.inferenceUnits
        ),
        context = lowerIsBetter(
            baseline.contextBytes,
            candidate.contextBytes
        ),
        artifacts = lowerIsBetter(
            baseline.artifactCount,
            candidate.artifactCount
        ),
        retries = lowerIsBetter(
            baseline.retryCount,
            candidate.retryCount
        )
    )

    private fun compareBoolean(
        baseline: Boolean,
        candidate: Boolean
    ): AgentBlueprintEvaluationRelation = when {
        candidate == baseline -> AgentBlueprintEvaluationRelation.EQUAL
        candidate -> AgentBlueprintEvaluationRelation.BETTER
        else -> AgentBlueprintEvaluationRelation.WORSE
    }

    private fun lowerIsBetter(
        baseline: Long,
        candidate: Long
    ): AgentBlueprintEvaluationRelation = when {
        candidate < baseline -> AgentBlueprintEvaluationRelation.BETTER
        candidate > baseline -> AgentBlueprintEvaluationRelation.WORSE
        else -> AgentBlueprintEvaluationRelation.EQUAL
    }

    private fun lowerIsBetter(
        baseline: Int,
        candidate: Int
    ): AgentBlueprintEvaluationRelation =
        lowerIsBetter(baseline.toLong(), candidate.toLong())
}
