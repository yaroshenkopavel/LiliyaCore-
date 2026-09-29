package pro.liliya.core.asf

@JvmInline
value class AgentCouncilParticipantId(val value: String) {
    init {
        require(value.isNotBlank())
        require(value.length <= 128)
    }
}

data class AgentCouncilParticipant(
    val id: AgentCouncilParticipantId,
    val modelReference: String,
    val runtimeReference: String,
    val role: String
) {
    init {
        require(modelReference.isNotBlank() && modelReference.length <= 256)
        require(runtimeReference.isNotBlank() && runtimeReference.length <= 256)
        require(role.isNotBlank() && role.length <= 128)
    }
}

data class AgentCouncilBudget(
    val maxParticipants: Int,
    val maxWallClockMillis: Long,
    val maxInferenceUnits: Long,
    val maxContextBytes: Int,
    val maxArtifacts: Int
) {
    init {
        require(maxParticipants in 1..8)
        require(maxWallClockMillis > 0)
        require(maxInferenceUnits > 0)
        require(maxContextBytes > 0)
        require(maxArtifacts > 0)
    }
}

data class AgentCouncilRequest(
    val rootTaskId: AgentRootTaskId,
    val objective: String,
    val participants: List<AgentCouncilParticipant>,
    val inputReferences: List<String>,
    val budget: AgentCouncilBudget
) {
    init {
        require(objective.isNotBlank() && objective.length <= 512)
        require(participants.isNotEmpty())
        require(participants.size <= budget.maxParticipants)
        require(participants.map { it.id }.distinct().size == participants.size)
        require(inputReferences.isNotEmpty() && inputReferences.size <= 64)
        require(inputReferences == inputReferences.sorted())
        require(inputReferences.distinct().size == inputReferences.size)
    }
}

enum class AgentCouncilFindingDisposition {
    SUPPORTS,
    CHALLENGES,
    INCONCLUSIVE
}

data class AgentCouncilFinding(
    val participantId: AgentCouncilParticipantId,
    val claimKey: String,
    val disposition: AgentCouncilFindingDisposition,
    val evidenceReferences: List<String>
) {
    init {
        require(claimKey.isNotBlank() && claimKey.length <= 256)
        require(evidenceReferences.isNotEmpty() && evidenceReferences.size <= 32)
        require(evidenceReferences == evidenceReferences.sorted())
        require(evidenceReferences.distinct().size == evidenceReferences.size)
    }
}

enum class AgentCouncilClaimState {
    SUPPORTED,
    CHALLENGED,
    INCONCLUSIVE,
    UNRESOLVED_CONFLICT
}

data class AgentCouncilClaimResult(
    val claimKey: String,
    val state: AgentCouncilClaimState,
    val contributorIds: List<AgentCouncilParticipantId>
)

data class AgentCouncilResult(
    val rootTaskId: AgentRootTaskId,
    val claims: List<AgentCouncilClaimResult>,
    val participantCount: Int
)

object AgentCouncilSynthesis {
    fun synthesize(
        request: AgentCouncilRequest,
        findings: List<AgentCouncilFinding>
    ): AgentCouncilResult {
        require(findings.isNotEmpty())
        val allowed = request.participants.map { it.id }.toSet()
        require(findings.all { it.participantId in allowed })
        require(findings.map { it.participantId }.toSet().size <= request.budget.maxParticipants)

        val claims = findings.groupBy { it.claimKey }.toSortedMap().map { (claim, rows) ->
            val dispositions = rows.map { it.disposition }.toSet()
            val state = when {
                AgentCouncilFindingDisposition.SUPPORTS in dispositions &&
                    AgentCouncilFindingDisposition.CHALLENGES in dispositions ->
                    AgentCouncilClaimState.UNRESOLVED_CONFLICT
                AgentCouncilFindingDisposition.CHALLENGES in dispositions ->
                    AgentCouncilClaimState.CHALLENGED
                AgentCouncilFindingDisposition.INCONCLUSIVE in dispositions ->
                    AgentCouncilClaimState.INCONCLUSIVE
                else -> AgentCouncilClaimState.SUPPORTED
            }
            AgentCouncilClaimResult(
                claimKey = claim,
                state = state,
                contributorIds = rows.map { it.participantId }.distinct().sortedBy { it.value }
            )
        }

        return AgentCouncilResult(
            rootTaskId = request.rootTaskId,
            claims = claims,
            participantCount = findings.map { it.participantId }.distinct().size
        )
    }
}
