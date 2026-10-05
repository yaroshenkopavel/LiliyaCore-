package pro.liliya.core.asf

@JvmInline
value class AgentToolProposalId(val value: String) {
    init {
        require(value.isNotBlank()) { "tool proposal id must not be blank" }
        require(value.length <= 160) { "tool proposal id exceeds bounded size" }
    }
}

enum class AgentEffectClass {
    READ_ONLY,
    LOCAL_STATE_CHANGE,
    EXTERNAL_STATE_CHANGE,
    UNKNOWN
}

data class AgentToolProposal(
    val id: AgentToolProposalId,
    val sourceArtifactId: AgentArtifactId,
    val sourceProducerId: AgentInstanceId,
    val rootTaskId: AgentRootTaskId,
    val toolReference: String,
    val operationClass: String,
    val targetReference: String,
    val effectClass: AgentEffectClass,
    val preconditions: List<String>,
    val expectedEffects: List<String>
) {
    init {
        require(toolReference.isNotBlank() && toolReference.length <= 256)
        require(operationClass.isNotBlank() && operationClass.length <= 128)
        require(targetReference.isNotBlank() && targetReference.length <= 256)
        require(preconditions.isNotEmpty() && preconditions.size <= 32)
        require(expectedEffects.isNotEmpty() && expectedEffects.size <= 32)
        require(preconditions.distinct().size == preconditions.size)
        require(expectedEffects.distinct().size == expectedEffects.size)
        require(preconditions == preconditions.sorted())
        require(expectedEffects == expectedEffects.sorted())
        (preconditions + expectedEffects).forEach {
            require(it.isNotBlank() && it.length <= 512)
        }
        require(
            id == deterministicId(
                sourceArtifactId,
                sourceProducerId,
                rootTaskId,
                toolReference,
                operationClass,
                targetReference,
                effectClass,
                preconditions,
                expectedEffects
            )
        ) { "tool proposal id does not match deterministic content identity" }
    }

    companion object {
        fun create(
            sourceArtifact: AgentArtifact,
            toolReference: String,
            operationClass: String,
            targetReference: String,
            effectClass: AgentEffectClass,
            preconditions: Collection<String>,
            expectedEffects: Collection<String>
        ): AgentToolProposal {
            val canonicalPreconditions = preconditions.sorted()
            val canonicalEffects = expectedEffects.sorted()
            return AgentToolProposal(
                id = deterministicId(
                    sourceArtifact.id,
                    sourceArtifact.producerId,
                    sourceArtifact.rootTaskId,
                    toolReference,
                    operationClass,
                    targetReference,
                    effectClass,
                    canonicalPreconditions,
                    canonicalEffects
                ),
                sourceArtifactId = sourceArtifact.id,
                sourceProducerId = sourceArtifact.producerId,
                rootTaskId = sourceArtifact.rootTaskId,
                toolReference = toolReference,
                operationClass = operationClass,
                targetReference = targetReference,
                effectClass = effectClass,
                preconditions = canonicalPreconditions,
                expectedEffects = canonicalEffects
            )
        }

        private fun deterministicId(
            sourceArtifactId: AgentArtifactId,
            sourceProducerId: AgentInstanceId,
            rootTaskId: AgentRootTaskId,
            toolReference: String,
            operationClass: String,
            targetReference: String,
            effectClass: AgentEffectClass,
            preconditions: List<String>,
            expectedEffects: List<String>
        ) = AgentToolProposalId(
            "asf-tool-proposal-" + AsfIdentity.sha256(
                "tool-proposal-v1",
                sourceArtifactId.value,
                sourceProducerId.value,
                rootTaskId.value,
                toolReference,
                operationClass,
                targetReference,
                effectClass.name,
                *preconditions.toTypedArray(),
                *expectedEffects.toTypedArray()
            )
        )
    }
}

data class AgentSimulationRequest(
    val proposal: AgentToolProposal,
    val contextReferences: List<String>
) {
    init {
        require(contextReferences.isNotEmpty()) {
            "simulation request requires context provenance"
        }
        require(contextReferences.size <= 64) {
            "too many simulation context references"
        }
        require(contextReferences.distinct().size == contextReferences.size)
        require(contextReferences == contextReferences.sorted())
        contextReferences.forEach {
            require(it.isNotBlank() && it.length <= 256)
        }
    }

    companion object {
        fun create(
            proposal: AgentToolProposal,
            contextReferences: Collection<String>
        ) = AgentSimulationRequest(proposal, contextReferences.sorted())
    }
}

enum class AgentSimulationState {
    PLAUSIBLE,
    BLOCKED,
    UNRESOLVED
}

data class AgentSimulationResult(
    val proposalId: AgentToolProposalId,
    val rootTaskId: AgentRootTaskId,
    val state: AgentSimulationState,
    val evidenceReferences: List<String>,
    val observedRisks: List<String>
) {
    init {
        require(evidenceReferences.isNotEmpty()) {
            "simulation result requires evidence"
        }
        require(evidenceReferences.size <= 64)
        require(evidenceReferences.distinct().size == evidenceReferences.size)
        require(evidenceReferences == evidenceReferences.sorted())
        require(observedRisks.size <= 32)
        require(observedRisks.distinct().size == observedRisks.size)
        require(observedRisks == observedRisks.sorted())
        (evidenceReferences + observedRisks).forEach {
            require(it.isNotBlank() && it.length <= 512)
        }
        require(state != AgentSimulationState.PLAUSIBLE || observedRisks.isEmpty()) {
            "plausible simulation cannot retain unresolved risks"
        }
    }

    companion object {
        fun create(
            request: AgentSimulationRequest,
            state: AgentSimulationState,
            evidenceReferences: Collection<String>,
            observedRisks: Collection<String> = emptyList()
        ) = AgentSimulationResult(
            proposalId = request.proposal.id,
            rootTaskId = request.proposal.rootTaskId,
            state = state,
            evidenceReferences = evidenceReferences.sorted(),
            observedRisks = observedRisks.sorted()
        )
    }
}

fun interface AgentSimulationAdapter {
    fun simulate(request: AgentSimulationRequest): AgentSimulationResult
}

enum class AgentProposalReadiness {
    READY_FOR_GOVERNANCE,
    BLOCKED,
    UNRESOLVED
}

object AgentProposalReadinessGate {
    fun evaluate(
        proposal: AgentToolProposal,
        simulation: AgentSimulationResult,
        synthesis: AgentSynthesisResult? = null
    ): AgentProposalReadiness {
        require(simulation.proposalId == proposal.id) {
            "simulation must reference exact proposal"
        }
        require(simulation.rootTaskId == proposal.rootTaskId) {
            "simulation root task must match proposal"
        }
        if (synthesis != null) {
            require(synthesis.rootTaskId == proposal.rootTaskId) {
                "review synthesis root task must match proposal"
            }
        }

        if (simulation.state == AgentSimulationState.BLOCKED) {
            return AgentProposalReadiness.BLOCKED
        }
        if (simulation.state == AgentSimulationState.UNRESOLVED) {
            return AgentProposalReadiness.UNRESOLVED
        }

        val states = synthesis?.findings?.map { it.state }.orEmpty()
        if (AgentSynthesisFindingState.CHALLENGED in states) {
            return AgentProposalReadiness.BLOCKED
        }
        if (
            AgentSynthesisFindingState.UNRESOLVED_CONFLICT in states ||
            AgentSynthesisFindingState.INCONCLUSIVE in states
        ) {
            return AgentProposalReadiness.UNRESOLVED
        }

        return AgentProposalReadiness.READY_FOR_GOVERNANCE
    }
}
