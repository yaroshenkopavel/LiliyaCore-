package pro.liliya.core.asf

data class AgentSimulationBudget(
    val maxContextReferences: Int,
    val maxEvidenceReferences: Int,
    val maxRiskItems: Int
) {
    init {
        require(maxContextReferences > 0)
        require(maxEvidenceReferences > 0)
        require(maxRiskItems >= 0)
    }
}

enum class AgentSimulationRunState {
    COMPLETED,
    CANCELLED,
    BUDGET_EXHAUSTED,
    FAILED
}

data class AgentSimulationRun(
    val state: AgentSimulationRunState,
    val proposalId: AgentToolProposalId,
    val request: AgentSimulationRequest?,
    val simulation: AgentSimulationResult?,
    val readiness: AgentProposalReadiness?,
    val recoveryDisposition: AgentSimulationRecoveryDisposition =
        AgentSimulationRecoveryDisposition.NEW_SIMULATION_REQUIRED
) {
    init {
        if (state == AgentSimulationRunState.COMPLETED) {
            require(request != null)
            require(simulation != null)
            require(readiness != null)
        } else {
            require(simulation == null)
            require(readiness == null)
        }
    }
}

class AgentSimulationPipeline(
    private val adapter: AgentSimulationAdapter,
    private val budget: AgentSimulationBudget
) {
    fun run(
        proposal: AgentToolProposal,
        contextReferences: Collection<String>,
        cancelled: () -> Boolean = { false }
    ): AgentSimulationRun {
        if (cancelled()) {
            return stopped(AgentSimulationRunState.CANCELLED, proposal.id, null)
        }

        val canonicalContext = contextReferences.sorted()
        if (
            canonicalContext.isEmpty() ||
            canonicalContext.size > budget.maxContextReferences
        ) {
            return stopped(
                AgentSimulationRunState.BUDGET_EXHAUSTED,
                proposal.id,
                null
            )
        }

        val request = try {
            AgentSimulationRequest.create(proposal, canonicalContext)
        } catch (_: IllegalArgumentException) {
            return stopped(AgentSimulationRunState.FAILED, proposal.id, null)
        }

        if (cancelled()) {
            return stopped(AgentSimulationRunState.CANCELLED, proposal.id, request)
        }

        val simulation = try {
            adapter.simulate(request)
        } catch (_: RuntimeException) {
            return stopped(AgentSimulationRunState.FAILED, proposal.id, request)
        }

        if (cancelled()) {
            return stopped(AgentSimulationRunState.CANCELLED, proposal.id, request)
        }

        if (
            simulation.proposalId != proposal.id ||
            simulation.rootTaskId != proposal.rootTaskId
        ) {
            return stopped(AgentSimulationRunState.FAILED, proposal.id, request)
        }

        if (
            simulation.evidenceReferences.size > budget.maxEvidenceReferences ||
            simulation.observedRisks.size > budget.maxRiskItems
        ) {
            return stopped(
                AgentSimulationRunState.BUDGET_EXHAUSTED,
                proposal.id,
                request
            )
        }

        val readiness = try {
            AgentProposalReadinessGate.evaluate(proposal, simulation)
        } catch (_: IllegalArgumentException) {
            return stopped(AgentSimulationRunState.FAILED, proposal.id, request)
        }

        return AgentSimulationRun(
            state = AgentSimulationRunState.COMPLETED,
            proposalId = proposal.id,
            request = request,
            simulation = simulation,
            readiness = readiness
        )
    }

    private fun stopped(
        state: AgentSimulationRunState,
        proposalId: AgentToolProposalId,
        request: AgentSimulationRequest?
    ) = AgentSimulationRun(
        state = state,
        proposalId = proposalId,
        request = request,
        simulation = null,
        readiness = null
    )
}
