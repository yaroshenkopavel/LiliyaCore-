package pro.liliya.core.asf

import java.time.Instant

data class AgentCouncilRunWindow(
    val admittedAt: Instant,
    val expiresAt: Instant
) {
    init {
        require(expiresAt.isAfter(admittedAt))
    }
}

data class AgentCouncilParticipantContext(
    val rootTaskId: AgentRootTaskId,
    val participant: AgentCouncilParticipant,
    val objective: String,
    val inputReferences: List<String>,
    val budget: AgentCouncilBudget,
    val startedAt: Instant,
    val expiresAt: Instant
)

sealed interface AgentCouncilParticipantOutcome {
    val usage: AgentRuntimeUsage

    data class Completed(
        val findings: List<AgentCouncilFinding>,
        override val usage: AgentRuntimeUsage
    ) : AgentCouncilParticipantOutcome {
        init {
            require(findings.isNotEmpty())
        }
    }

    data class Failed(
        val reason: String,
        override val usage: AgentRuntimeUsage
    ) : AgentCouncilParticipantOutcome {
        init {
            require(reason.isNotBlank() && reason.length <= 1024)
        }
    }
}

fun interface AgentCouncilParticipantAdapter {
    fun run(context: AgentCouncilParticipantContext): AgentCouncilParticipantOutcome
}

data class AgentCouncilUsage(
    val participantsStarted: Int = 0,
    val wallClockMillis: Long = 0,
    val inferenceUnits: Long = 0,
    val contextBytes: Int = 0,
    val artifactCount: Int = 0
) {
    init {
        require(participantsStarted >= 0)
        require(wallClockMillis >= 0)
        require(inferenceUnits >= 0)
        require(contextBytes >= 0)
        require(artifactCount >= 0)
    }

    fun startParticipant() = copy(
        participantsStarted = Math.addExact(participantsStarted, 1)
    )

    fun plus(usage: AgentRuntimeUsage) = AgentCouncilUsage(
        participantsStarted = participantsStarted,
        wallClockMillis = Math.addExact(wallClockMillis, usage.wallClockMillis),
        inferenceUnits = Math.addExact(inferenceUnits, usage.inferenceUnits),
        contextBytes = Math.addExact(contextBytes, usage.contextBytes),
        artifactCount = Math.addExact(artifactCount, usage.artifactCount)
    )
}

fun AgentCouncilBudget.allows(usage: AgentCouncilUsage): Boolean =
    usage.participantsStarted <= maxParticipants &&
        usage.wallClockMillis <= maxWallClockMillis &&
        usage.inferenceUnits <= maxInferenceUnits &&
        usage.contextBytes <= maxContextBytes &&
        usage.artifactCount <= maxArtifacts

enum class AgentCouncilTerminalState {
    COMPLETED,
    PARTIAL,
    FAILED,
    CANCELLED,
    EXPIRED,
    BUDGET_EXHAUSTED
}

data class AgentCouncilRunResult(
    val state: AgentCouncilTerminalState,
    val findings: List<AgentCouncilFinding>,
    val synthesis: AgentCouncilResult?,
    val usage: AgentCouncilUsage,
    val completedParticipants: Int
) {
    init {
        require(completedParticipants >= 0)
        require(completedParticipants <= usage.participantsStarted)
        require(synthesis == null || findings.isNotEmpty())
    }
}

class AgentCouncilRunner(
    private val adapter: AgentCouncilParticipantAdapter,
    private val timeSource: () -> Instant = Instant::now
) {
    fun runSequential(
        request: AgentCouncilRequest,
        window: AgentCouncilRunWindow,
        isCancelled: () -> Boolean = { false }
    ): AgentCouncilRunResult {
        var usage = AgentCouncilUsage()
        val findings = mutableListOf<AgentCouncilFinding>()
        var completed = 0

        for (participant in request.participants) {
            if (isCancelled()) {
                return terminal(
                    AgentCouncilTerminalState.CANCELLED,
                    request,
                    findings,
                    usage,
                    completed
                )
            }
            if (!timeSource().isBefore(window.expiresAt)) {
                return terminal(
                    AgentCouncilTerminalState.EXPIRED,
                    request,
                    findings,
                    usage,
                    completed
                )
            }

            usage = usage.startParticipant()
            if (!request.budget.allows(usage)) {
                return terminal(
                    AgentCouncilTerminalState.BUDGET_EXHAUSTED,
                    request,
                    findings,
                    usage,
                    completed
                )
            }

            val outcome = try {
                adapter.run(
                    AgentCouncilParticipantContext(
                        rootTaskId = request.rootTaskId,
                        participant = participant,
                        objective = request.objective,
                        inputReferences = request.inputReferences,
                        budget = request.budget,
                        startedAt = timeSource(),
                        expiresAt = window.expiresAt
                    )
                )
            } catch (_: RuntimeException) {
                return terminal(
                    if (completed > 0) AgentCouncilTerminalState.PARTIAL
                    else AgentCouncilTerminalState.FAILED,
                    request,
                    findings,
                    usage,
                    completed
                )
            }

            usage = usage.plus(outcome.usage)
            if (!request.budget.allows(usage)) {
                return terminal(
                    AgentCouncilTerminalState.BUDGET_EXHAUSTED,
                    request,
                    findings,
                    usage,
                    completed
                )
            }

            when (outcome) {
                is AgentCouncilParticipantOutcome.Completed -> {
                    require(outcome.findings.all { it.participantId == participant.id }) {
                        "participant outcome may contain only own findings"
                    }
                    findings += outcome.findings
                    completed++
                }
                is AgentCouncilParticipantOutcome.Failed -> {
                    return terminal(
                        if (completed > 0) AgentCouncilTerminalState.PARTIAL
                        else AgentCouncilTerminalState.FAILED,
                        request,
                        findings,
                        usage,
                        completed
                    )
                }
            }

            if (isCancelled()) {
                return terminal(
                    AgentCouncilTerminalState.CANCELLED,
                    request,
                    findings,
                    usage,
                    completed
                )
            }
        }

        return terminal(
            AgentCouncilTerminalState.COMPLETED,
            request,
            findings,
            usage,
            completed
        )
    }

    private fun terminal(
        state: AgentCouncilTerminalState,
        request: AgentCouncilRequest,
        findings: List<AgentCouncilFinding>,
        usage: AgentCouncilUsage,
        completed: Int
    ): AgentCouncilRunResult =
        AgentCouncilRunResult(
            state = state,
            findings = findings.toList(),
            synthesis = if (findings.isEmpty()) null
            else AgentCouncilSynthesis.synthesize(request, findings),
            usage = usage,
            completedParticipants = completed
        )
}
