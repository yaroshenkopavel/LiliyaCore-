package pro.liliya.core.agent

import java.time.Instant
import pro.liliya.core.autonomy.AutonomyBudget
import pro.liliya.core.autonomy.AutonomyOwnership
import pro.liliya.core.autonomy.AutonomyPriority
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

class AgentCoordinationParticipantInitiativeRequest(
    val participant: ExactAgentReference,
    val autonomyProposalId: AutonomyProposalId,
    val objective: String,
    val triggerDescription: String,
    val priority: AutonomyPriority,
    val budget: AutonomyBudget,
    val createdAt: Instant
) {
    init {
        require(objective.isNotBlank()) { "coordination initiative objective must not be blank" }
        require(triggerDescription.isNotBlank()) { "coordination initiative trigger description must not be blank" }
    }

    override fun toString(): String =
        "AgentCoordinationParticipantInitiativeRequest(" +
            "participant=$participant, autonomyProposalId=$autonomyProposalId, " +
            "objective=<redacted>, triggerDescription=<redacted>, priority=$priority, " +
            "budget=$budget, createdAt=$createdAt)"
}

class AgentCoordinationInitiativeRequest(
    val coordinationId: AgentCoordinationId,
    val coordinationGeneration: AgentCoordinationGeneration,
    participants: List<AgentCoordinationParticipantInitiativeRequest>
) {
    val participants: List<AgentCoordinationParticipantInitiativeRequest> = participants.toList()

    init {
        require(this.participants.size >= 2) { "coordination initiative requires at least two participants" }
        require(this.participants.map { it.participant }.distinct().size == this.participants.size) {
            "coordination initiative participants must be exact-reference unique"
        }
        require(this.participants.map { it.participant.id }.distinct().size == this.participants.size) {
            "coordination initiative cannot contain multiple generations of the same agent id"
        }
        require(this.participants.map { it.autonomyProposalId }.distinct().size == this.participants.size) {
            "coordination initiative autonomy proposal ids must be unique"
        }
    }

    override fun toString(): String =
        "AgentCoordinationInitiativeRequest(" +
            "coordinationId=$coordinationId, coordinationGeneration=$coordinationGeneration, " +
            "participantCount=${participants.size})"
}

class AgentCoordinationInitiativeReceipt(
    val coordination: ExactAgentCoordinationReference,
    assignments: List<AgentCoordinationWorkAssignment>,
    val bindingGeneration: AgentCoordinationWorkBindingGeneration
) {
    val assignments: List<AgentCoordinationWorkAssignment> = assignments.toList()
}

interface AgentCoordinationInitiativeOwnership {
    val receipt: AgentCoordinationInitiativeReceipt

    /** Removes exact created Autonomy first, then the structural work binding. */
    fun remove(): Boolean
}

sealed interface AgentCoordinationInitiativeResult {
    data class Created(
        val ownership: AgentCoordinationInitiativeOwnership
    ) : AgentCoordinationInitiativeResult

    data class Rejected(val reason: String) : AgentCoordinationInitiativeResult {
        init { require(reason.isNotBlank()) { "coordination initiative rejection reason must not be blank" } }
    }

    data class Failed(val reason: String) : AgentCoordinationInitiativeResult {
        init { require(reason.isNotBlank()) { "coordination initiative failure reason must not be blank" } }
    }
}

internal fun interface AgentCoordinationPreflightChecker {
    fun check(request: AgentCoordinationPreflightRequest): AgentCoordinationPreflightResult
}

internal fun interface AgentCoordinationInitiativeCreator {
    fun create(request: AgentInitiativeRequest): AgentInitiativeResult
}

internal fun interface AgentCoordinationWorkBindingInstaller {
    fun install(binding: AgentCoordinationWorkBinding): AgentCoordinationWorkBindingInstallResult
}

/**
 * Compensated multi-participant initiative transaction for one exact live coordination generation.
 *
 * The exact participant request set must equal the live coordination participant set. Each ordinary
 * Agent initiative is created through the frozen Agent boundary. A second fresh coordination
 * preflight runs after all Autonomy writes and before the structural binding commit. Any normal
 * failure after a write compensates every exact created Autonomy generation. Compensation failure is
 * explicit and CRITICAL-observable. Success exposes one composite ownership only.
 */
class ControlledAgentCoordinationInitiative private constructor(
    private val foundation: FoundationComposition,
    private val preflight: AgentCoordinationPreflightChecker,
    private val initiative: AgentCoordinationInitiativeCreator,
    private val bindings: AgentCoordinationWorkBindingInstaller
) {
    constructor(
        foundation: FoundationComposition,
        preflight: ControlledAgentCoordinationPreflight,
        initiative: ControlledAgentInitiative,
        bindings: AgentCoordinationWorkBindingComposition
    ) : this(
        foundation = foundation,
        preflight = AgentCoordinationPreflightChecker(preflight::check),
        initiative = AgentCoordinationInitiativeCreator(initiative::create),
        bindings = AgentCoordinationWorkBindingInstaller(bindings::install)
    )

    internal constructor(
        foundation: FoundationComposition,
        preflight: AgentCoordinationPreflightChecker,
        initiative: AgentCoordinationInitiativeCreator,
        bindings: AgentCoordinationWorkBindingInstaller,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit
    ) : this(foundation, preflight, initiative, bindings)

    fun create(request: AgentCoordinationInitiativeRequest): AgentCoordinationInitiativeResult {
        val exactRequest = AgentCoordinationPreflightRequest(
            coordinationId = request.coordinationId,
            coordinationGeneration = request.coordinationGeneration
        )

        val initial = when (val checked = preflight.check(exactRequest)) {
            is AgentCoordinationPreflightResult.Ready -> checked.evidence
            is AgentCoordinationPreflightResult.Rejected ->
                return AgentCoordinationInitiativeResult.Rejected(
                    "coordination preflight rejected: ${checked.reason}"
                )
        }

        if (!sameParticipants(initial.participants, request.participants.map { it.participant })) {
            return AgentCoordinationInitiativeResult.Rejected(
                "coordination initiative participant set does not match exact live coordination"
            )
        }

        val requestsByParticipant = request.participants.associateBy { it.participant }
        val created = mutableListOf<Pair<ExactAgentReference, AutonomyOwnership>>()

        initial.participants.forEach { participant ->
            val participantRequest = requestsByParticipant.getValue(participant)
            when (
                val result = initiative.create(
                    AgentInitiativeRequest(
                        agentId = participant.id,
                        agentGeneration = participant.generation,
                        autonomyProposalId = participantRequest.autonomyProposalId,
                        objective = participantRequest.objective,
                        triggerDescription = participantRequest.triggerDescription,
                        priority = participantRequest.priority,
                        budget = participantRequest.budget,
                        createdAt = participantRequest.createdAt
                    )
                )
            ) {
                is AgentInitiativeResult.Created -> created += participant to result.ownership
                is AgentInitiativeResult.Rejected -> return compensate(
                    created = created,
                    reason = "participant initiative rejected: ${result.reason}"
                )
            }
        }

        val confirmed = when (val checked = preflight.check(exactRequest)) {
            is AgentCoordinationPreflightResult.Ready -> checked.evidence
            is AgentCoordinationPreflightResult.Rejected -> return compensate(
                created = created,
                reason = "coordination changed during initiative creation: ${checked.reason}"
            )
        }

        if (
            confirmed.coordinationId != initial.coordinationId ||
            confirmed.coordinationGeneration != initial.coordinationGeneration ||
            confirmed.participants != initial.participants
        ) {
            return compensate(
                created = created,
                reason = "coordination evidence changed during initiative creation"
            )
        }

        val binding = AgentCoordinationWorkBinding(
            coordination = ExactAgentCoordinationReference(
                id = confirmed.coordinationId,
                generation = confirmed.coordinationGeneration
            ),
            assignments = created.map { (participant, ownership) ->
                AgentCoordinationWorkAssignment(
                    participant = participant,
                    autonomy = ExactAutonomyReference(
                        proposalId = ownership.proposal.id,
                        generation = ownership.generation
                    )
                )
            }
        )

        return when (val installed = bindings.install(binding)) {
            is AgentCoordinationWorkBindingInstallResult.Installed -> {
                val receipt = AgentCoordinationInitiativeReceipt(
                    coordination = binding.coordination,
                    assignments = binding.assignments,
                    bindingGeneration = installed.ownership.generation
                )
                recordCreated(receipt)
                AgentCoordinationInitiativeResult.Created(
                    compositeOwnership(
                        receipt = receipt,
                        autonomy = created.map { it.second },
                        binding = installed.ownership
                    )
                )
            }

            is AgentCoordinationWorkBindingInstallResult.Rejected -> compensate(
                created = created,
                reason = "coordination work binding rejected: ${installed.reason}"
            )
        }
    }

    private fun sameParticipants(
        expected: List<ExactAgentReference>,
        actual: List<ExactAgentReference>
    ): Boolean = expected.toSet() == actual.toSet() && expected.size == actual.size

    private fun compensate(
        created: List<Pair<ExactAgentReference, AutonomyOwnership>>,
        reason: String
    ): AgentCoordinationInitiativeResult {
        var compensationFailed = false
        created.asReversed().forEach { (_, ownership) ->
            if (!ownership.remove()) compensationFailed = true
        }

        if (compensationFailed) {
            foundation.observability.record(
                severity = DiagnosticSeverity.CRITICAL,
                code = "AGENT_COORDINATION_INITIATIVE_COMPENSATION_FAILED",
                message = "coordination initiative compensation failed",
                context = foundation.rootContext(
                    operation = "compensateAgentCoordinationInitiative",
                    component = "AgentCoordination",
                    metadata = mapOf("createdCount" to created.size.toString())
                ),
                metadata = mapOf("failureReason" to reason)
            )
            return AgentCoordinationInitiativeResult.Failed(
                "coordination initiative compensation failed after: $reason"
            )
        }

        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_COORDINATION_INITIATIVE_COMPENSATED",
            message = "coordination initiative rolled back",
            context = foundation.rootContext(
                operation = "compensateAgentCoordinationInitiative",
                component = "AgentCoordination",
                metadata = mapOf("createdCount" to created.size.toString())
            ),
            metadata = mapOf("compensationReason" to reason)
        )
        return AgentCoordinationInitiativeResult.Rejected(reason)
    }

    private fun compositeOwnership(
        receipt: AgentCoordinationInitiativeReceipt,
        autonomy: List<AutonomyOwnership>,
        binding: AgentCoordinationWorkBindingOwnership
    ): AgentCoordinationInitiativeOwnership = object : AgentCoordinationInitiativeOwnership {
        private val lock = Any()
        private val autonomyRemoved = BooleanArray(autonomy.size)
        private var fullyRemoved = false

        override val receipt: AgentCoordinationInitiativeReceipt = receipt

        override fun remove(): Boolean = synchronized(lock) {
            if (fullyRemoved) return@synchronized false

            var removalFailed = false
            for (index in autonomy.indices.reversed()) {
                if (!autonomyRemoved[index]) {
                    if (autonomy[index].remove()) {
                        autonomyRemoved[index] = true
                    } else {
                        removalFailed = true
                    }
                }
            }
            if (removalFailed) return@synchronized false

            if (!binding.remove()) {
                foundation.observability.record(
                    severity = DiagnosticSeverity.CRITICAL,
                    code = "AGENT_COORDINATION_INITIATIVE_BINDING_CLEANUP_FAILED",
                    message = "coordination initiative binding cleanup failed",
                    context = foundation.rootContext(
                        operation = "removeAgentCoordinationInitiative",
                        component = "AgentCoordination",
                        metadata = receiptMetadata(receipt)
                    ),
                    metadata = receiptMetadata(receipt)
                )
                return@synchronized false
            }

            fullyRemoved = true
            true
        }
    }

    private fun recordCreated(receipt: AgentCoordinationInitiativeReceipt) {
        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AGENT_COORDINATION_INITIATIVE_CREATED",
            message = "coordination initiatives created and structurally bound",
            context = foundation.rootContext(
                operation = "createAgentCoordinationInitiative",
                component = "AgentCoordination",
                metadata = receiptMetadata(receipt)
            ),
            metadata = receiptMetadata(receipt)
        )
    }

    private fun receiptMetadata(receipt: AgentCoordinationInitiativeReceipt): Map<String, String> = buildMap {
        put("agentCoordinationId", receipt.coordination.id.value)
        put("agentCoordinationGeneration", receipt.coordination.generation.value.toString())
        put("coordinationWorkBindingGeneration", receipt.bindingGeneration.value.toString())
        put("assignmentCount", receipt.assignments.size.toString())
        receipt.assignments.forEachIndexed { index, assignment ->
            put("assignment${index}AgentId", assignment.participant.id.value)
            put("assignment${index}AgentGeneration", assignment.participant.generation.value.toString())
            put("assignment${index}AutonomyProposalId", assignment.autonomy.proposalId.value)
            put("assignment${index}AutonomyGeneration", assignment.autonomy.generation.value.toString())
        }
    }
}
