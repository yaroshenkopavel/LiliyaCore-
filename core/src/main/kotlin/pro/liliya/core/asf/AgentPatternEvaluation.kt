package pro.liliya.core.asf

data class AgentPatternEvaluation(
    val patternKind: AgentReviewPatternKind,
    val rootTaskId: AgentRootTaskId,
    val primaryArtifactId: AgentArtifactId,
    val reviewArtifactId: AgentArtifactId,
    val totalFindings: Int,
    val supportedFindings: Int,
    val challengedFindings: Int,
    val inconclusiveFindings: Int,
    val unresolvedConflictFindings: Int
) {
    init {
        require(totalFindings > 0) { "pattern evaluation requires synthesis findings" }
        require(supportedFindings >= 0)
        require(challengedFindings >= 0)
        require(inconclusiveFindings >= 0)
        require(unresolvedConflictFindings >= 0)
        require(
            supportedFindings +
                challengedFindings +
                inconclusiveFindings +
                unresolvedConflictFindings == totalFindings
        ) { "pattern evaluation finding counts must be complete" }
    }

    val reviewDetectedRiskOrUncertainty: Boolean
        get() = challengedFindings > 0 ||
            inconclusiveFindings > 0 ||
            unresolvedConflictFindings > 0
}

object AgentPatternEvaluationHooks {
    fun measure(
        pattern: AgentReviewPatternPlan,
        primaryArtifact: AgentArtifact,
        reviewContribution: AgentReviewContribution,
        synthesis: AgentSynthesisResult
    ): AgentPatternEvaluation {
        require(primaryArtifact.rootTaskId == pattern.coordinatorPlan.rootTaskId) {
            "primary artifact root task must match review pattern"
        }
        require(reviewContribution.artifact.rootTaskId == pattern.coordinatorPlan.rootTaskId) {
            "review artifact root task must match review pattern"
        }
        require(synthesis.rootTaskId == pattern.coordinatorPlan.rootTaskId) {
            "synthesis root task must match review pattern"
        }
        require(reviewContribution.kind == pattern.reviewKind) {
            "review contribution kind must match review pattern"
        }

        val primaryArtifactReference = "asf-artifact:${primaryArtifact.id.value}"
        require(primaryArtifactReference in reviewContribution.artifact.provenanceReferences) {
            "review artifact must retain exact primary artifact provenance"
        }
        require(reviewContribution.artifact.id in synthesis.contributingArtifactIds) {
            "synthesis must retain review artifact provenance"
        }

        return AgentPatternEvaluation(
            patternKind = pattern.kind,
            rootTaskId = pattern.coordinatorPlan.rootTaskId,
            primaryArtifactId = primaryArtifact.id,
            reviewArtifactId = reviewContribution.artifact.id,
            totalFindings = synthesis.findings.size,
            supportedFindings = synthesis.findings.count {
                it.state == AgentSynthesisFindingState.SUPPORTED
            },
            challengedFindings = synthesis.findings.count {
                it.state == AgentSynthesisFindingState.CHALLENGED
            },
            inconclusiveFindings = synthesis.findings.count {
                it.state == AgentSynthesisFindingState.INCONCLUSIVE
            },
            unresolvedConflictFindings = synthesis.findings.count {
                it.state == AgentSynthesisFindingState.UNRESOLVED_CONFLICT
            }
        )
    }
}
