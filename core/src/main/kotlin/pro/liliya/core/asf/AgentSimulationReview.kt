package pro.liliya.core.asf

object AgentSimulationProvenance {
    fun proposalReference(proposal: AgentToolProposal): String =
        "asf-tool-proposal:${proposal.id.value}"

    fun simulationReference(simulation: AgentSimulationResult): String =
        "asf-simulation-" + AsfIdentity.sha256(
            "simulation-reference-v1",
            simulation.proposalId.value,
            simulation.rootTaskId.value,
            simulation.state.name,
            *simulation.evidenceReferences.toTypedArray(),
            *simulation.observedRisks.toTypedArray()
        )
}

enum class AgentSimulationRecoveryDisposition {
    NEW_SIMULATION_REQUIRED
}

data class AgentSimulationReviewBundle(
    val proposal: AgentToolProposal,
    val simulation: AgentSimulationResult,
    val reviewContribution: AgentReviewContribution,
    val synthesis: AgentSynthesisResult,
    val readiness: AgentProposalReadiness,
    val recoveryDisposition: AgentSimulationRecoveryDisposition =
        AgentSimulationRecoveryDisposition.NEW_SIMULATION_REQUIRED
) {
    init {
        require(simulation.proposalId == proposal.id) {
            "review bundle simulation must reference exact proposal"
        }
        require(simulation.rootTaskId == proposal.rootTaskId) {
            "review bundle simulation root task must match proposal"
        }
        require(reviewContribution.artifact.rootTaskId == proposal.rootTaskId) {
            "review artifact root task must match proposal"
        }
        require(synthesis.rootTaskId == proposal.rootTaskId) {
            "review synthesis root task must match proposal"
        }
        require(reviewContribution.artifact.id in synthesis.contributingArtifactIds) {
            "synthesis must retain review artifact provenance"
        }

        val required = setOf(
            AgentSimulationProvenance.proposalReference(proposal),
            AgentSimulationProvenance.simulationReference(simulation)
        )
        require(required.all { it in reviewContribution.artifact.provenanceReferences }) {
            "review artifact must retain exact proposal and simulation provenance"
        }
        require(
            readiness == AgentProposalReadinessGate.evaluate(
                proposal,
                simulation,
                synthesis
            )
        ) {
            "review bundle readiness must equal deterministic readiness gate"
        }
    }

    companion object {
        fun create(
            proposal: AgentToolProposal,
            simulation: AgentSimulationResult,
            reviewContribution: AgentReviewContribution,
            synthesis: AgentSynthesisResult
        ) = AgentSimulationReviewBundle(
            proposal = proposal,
            simulation = simulation,
            reviewContribution = reviewContribution,
            synthesis = synthesis,
            readiness = AgentProposalReadinessGate.evaluate(
                proposal,
                simulation,
                synthesis
            )
        )
    }
}
