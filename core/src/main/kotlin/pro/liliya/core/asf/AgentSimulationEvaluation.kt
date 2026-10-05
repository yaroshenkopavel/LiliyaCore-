package pro.liliya.core.asf

data class AgentSimulationDryRunEvaluation(
    val proposalId: AgentToolProposalId,
    val sourceArtifactId: AgentArtifactId,
    val sourceProducerId: AgentInstanceId,
    val rootTaskId: AgentRootTaskId,
    val effectClass: AgentEffectClass,
    val simulationReference: String,
    val reviewArtifactId: AgentArtifactId?,
    val readiness: AgentProposalReadiness,
    val contextReferenceCount: Int,
    val evidenceReferenceCount: Int,
    val riskItemCount: Int,
    val recoveryDisposition: AgentSimulationRecoveryDisposition =
        AgentSimulationRecoveryDisposition.NEW_SIMULATION_REQUIRED
) {
    init {
        require(simulationReference.isNotBlank())
        require(simulationReference.length <= 256)
        require(contextReferenceCount > 0)
        require(evidenceReferenceCount > 0)
        require(riskItemCount >= 0)
    }

    val reviewed: Boolean
        get() = reviewArtifactId != null
}

object AgentSimulationEvaluationHooks {
    fun measure(
        proposal: AgentToolProposal,
        run: AgentSimulationRun
    ): AgentSimulationDryRunEvaluation {
        require(run.state == AgentSimulationRunState.COMPLETED) {
            "dry-run evaluation requires completed simulation"
        }
        require(run.proposalId == proposal.id) {
            "dry-run evaluation proposal must match run"
        }

        val request = requireNotNull(run.request)
        val simulation = requireNotNull(run.simulation)
        val readiness = requireNotNull(run.readiness)

        return AgentSimulationDryRunEvaluation(
            proposalId = proposal.id,
            sourceArtifactId = proposal.sourceArtifactId,
            sourceProducerId = proposal.sourceProducerId,
            rootTaskId = proposal.rootTaskId,
            effectClass = proposal.effectClass,
            simulationReference =
                AgentSimulationProvenance.simulationReference(simulation),
            reviewArtifactId = null,
            readiness = readiness,
            contextReferenceCount = request.contextReferences.size,
            evidenceReferenceCount = simulation.evidenceReferences.size,
            riskItemCount = simulation.observedRisks.size
        )
    }

    fun measure(
        bundle: AgentSimulationReviewBundle
    ): AgentSimulationDryRunEvaluation {
        val proposal = bundle.proposal
        val simulation = bundle.simulation

        return AgentSimulationDryRunEvaluation(
            proposalId = proposal.id,
            sourceArtifactId = proposal.sourceArtifactId,
            sourceProducerId = proposal.sourceProducerId,
            rootTaskId = proposal.rootTaskId,
            effectClass = proposal.effectClass,
            simulationReference =
                AgentSimulationProvenance.simulationReference(simulation),
            reviewArtifactId = bundle.reviewContribution.artifact.id,
            readiness = bundle.readiness,
            contextReferenceCount = 1,
            evidenceReferenceCount = simulation.evidenceReferences.size,
            riskItemCount = simulation.observedRisks.size
        )
    }
}
