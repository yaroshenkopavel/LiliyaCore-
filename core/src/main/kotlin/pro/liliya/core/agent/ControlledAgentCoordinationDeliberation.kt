package pro.liliya.core.agent

import java.time.Instant
import pro.liliya.core.autonomy.AutonomyAttemptReference
import pro.liliya.core.autonomy.AutonomyDeliberationAttemptValidationResult
import pro.liliya.core.autonomy.AutonomyDeliberationComposition
import pro.liliya.core.autonomy.AutonomyDeliberationGeneration
import pro.liliya.core.autonomy.AutonomyDeliberationInstallResult
import pro.liliya.core.autonomy.AutonomyDeliberationOwnership
import pro.liliya.core.autonomy.AutonomyDeliberationRequest
import pro.liliya.core.autonomy.AutonomyDeliberationRequestId
import pro.liliya.core.autonomy.ControlledAutonomyDeliberationGate
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

data class AgentCoordinationDeliberationSpec(
    val participant: ExactAgentReference,
    val requestId: AutonomyDeliberationRequestId,
    val objective: String,
    val createdAt: Instant
) {
    init {
        require(objective.isNotBlank()) { "coordination deliberation objective must not be blank" }
    }
}

class AgentCoordinationDeliberationRequest(
    val coordination: ExactAgentCoordinationReference,
    val attemptBindingGeneration: AgentCoordinationAttemptBindingGeneration,
    specs: List<AgentCoordinationDeliberationSpec>
) {
    val specs: List<AgentCoordinationDeliberationSpec> = specs.toList()

    init {
        require(this.specs.size >= 2) { "coordination deliberation requires at least two participant specs" }
        require(this.specs.map { it.participant }.distinct().size == this.specs.size) {
            "coordination deliberation participants must be exact-reference unique"
        }
        require(this.specs.map { it.participant.id }.distinct().size == this.specs.size) {
            "coordination deliberation cannot contain multiple generations of one agent id"
        }
        require(this.specs.map { it.requestId }.distinct().size == this.specs.size) {
            "coordination deliberation request ids must be unique"
        }
    }
}

class AgentCoordinationParticipantDeliberation(
    val participant: ExactAgentReference,
    val attempt: AutonomyAttemptReference,
    val requestId: AutonomyDeliberationRequestId,
    val requestGeneration: AutonomyDeliberationGeneration
)

class AgentCoordinationDeliberationReceipt(
    val coordination: ExactAgentCoordinationReference,
    val attemptBindingGeneration: AgentCoordinationAttemptBindingGeneration,
    deliberations: List<AgentCoordinationParticipantDeliberation>
) {
    val deliberations: List<AgentCoordinationParticipantDeliberation> = deliberations.toList()

    init {
        require(this.deliberations.size >= 2) {
            "coordination deliberation receipt requires at least two participant deliberations"
        }
    }
}

sealed interface AgentCoordinationDeliberationResult {
    data class Created(val receipt: AgentCoordinationDeliberationReceipt) : AgentCoordinationDeliberationResult

    data class Rejected(val reason: String) : AgentCoordinationDeliberationResult {
        init { require(reason.isNotBlank()) { "coordination deliberation rejection reason must not be blank" } }
    }

    data class Failed(val reason: String) : AgentCoordinationDeliberationResult {
        init { require(reason.isNotBlank()) { "coordination deliberation failure reason must not be blank" } }
    }
}

internal fun interface AgentCoordinationDeliberationInstaller {
    fun install(request: AutonomyDeliberationRequest): AutonomyDeliberationInstallResult
}

internal fun interface AgentCoordinationAttemptValidator {
    fun validate(attempt: AutonomyAttemptReference): AutonomyDeliberationAttemptValidationResult
}

/**
 * Compensated creation of one exact Autonomy deliberation request per committed coordinated attempt.
 *
 * A successful receipt is returned only while the exact coordination generation, every participant
 * ACTIVE lifecycle, the exact attempt-binding generation and every exact bound attempt remain valid
 * through the complete write transaction. Any failure after one or more writes removes every exact
 * deliberation generation created by this transaction before a normal rejection can be returned.
 *
 * This bridge creates deliberation data only. It performs no Planning/Reasoning/Decision,
 * scheduling, Authority or Execution and the receipt grants no permission.
 */
class ControlledAgentCoordinationDeliberation private constructor(
    private val foundation: FoundationComposition,
    private val attemptBindings: AgentCoordinationAttemptBindingComposition,
    private val deliberation: AutonomyDeliberationComposition,
    private val preflight: AgentCoordinationPreflightChecker,
    private val validator: AgentCoordinationAttemptValidator,
    private val installer: AgentCoordinationDeliberationInstaller
) {
    constructor(
        foundation: FoundationComposition,
        attemptBindings: AgentCoordinationAttemptBindingComposition,
        deliberation: AutonomyDeliberationComposition,
        preflight: ControlledAgentCoordinationPreflight,
        autonomyGate: ControlledAutonomyDeliberationGate
    ) : this(
        foundation = foundation,
        attemptBindings = attemptBindings,
        deliberation = deliberation,
        preflight = AgentCoordinationPreflightChecker(preflight::check),
        validator = AgentCoordinationAttemptValidator(autonomyGate::validateAttempt),
        installer = AgentCoordinationDeliberationInstaller(deliberation::install)
    )

    internal constructor(
        foundation: FoundationComposition,
        attemptBindings: AgentCoordinationAttemptBindingComposition,
        deliberation: AutonomyDeliberationComposition,
        preflight: AgentCoordinationPreflightChecker,
        validator: AgentCoordinationAttemptValidator,
        installer: AgentCoordinationDeliberationInstaller,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit
    ) : this(foundation, attemptBindings, deliberation, preflight, validator, installer)

    fun create(request: AgentCoordinationDeliberationRequest): AgentCoordinationDeliberationResult {
        val context = foundation.rootContext(
            operation = "createAgentCoordinationDeliberation",
            component = "AgentCoordination",
            metadata = requestMetadata(request)
        )

        val initialBinding = exactBinding(request)
            ?: return reject("coordination attempt binding is not live at exact generation", context)
        if (!participantSetsMatch(request, initialBinding.binding)) {
            return reject("coordination deliberation participant set does not match attempt binding", context)
        }
        if (!readyAndMatching(request.coordination, initialBinding.binding)) {
            return reject("coordination governance does not match attempt binding", context)
        }
        if (!allAttemptsValid(initialBinding.binding)) {
            return reject("coordination attempt binding contains invalid attempt evidence", context)
        }

        val specsByParticipant = request.specs.associateBy { it.participant }
        val created = mutableListOf<AutonomyDeliberationOwnership>()
        val receiptEntries = mutableListOf<AgentCoordinationParticipantDeliberation>()

        initialBinding.binding.assignments.forEach { assignment ->
            if (!transactionStillValid(request, initialBinding)) {
                return compensate(
                    request.coordination,
                    created,
                    "coordination governance changed before deliberation write",
                    context
                )
            }

            val spec = specsByParticipant.getValue(assignment.participant)
            val deliberationRequest = AutonomyDeliberationRequest(
                id = spec.requestId,
                autonomy = assignment.attempt,
                objective = spec.objective,
                createdAt = spec.createdAt
            )
            val ownership = when (val installed = installer.install(deliberationRequest)) {
                is AutonomyDeliberationInstallResult.Installed -> installed.ownership
                is AutonomyDeliberationInstallResult.Rejected -> return compensate(
                    request.coordination,
                    created,
                    "participant deliberation rejected: ${installed.reason}",
                    context
                )
            }
            created += ownership
            receiptEntries += AgentCoordinationParticipantDeliberation(
                participant = assignment.participant,
                attempt = assignment.attempt,
                requestId = ownership.request.id,
                requestGeneration = ownership.generation
            )

            if (!transactionStillValid(request, initialBinding)) {
                return compensate(
                    request.coordination,
                    created,
                    "coordination governance changed during deliberation write",
                    context
                )
            }
        }

        if (!transactionStillValid(request, initialBinding)) {
            return compensate(
                request.coordination,
                created,
                "coordination governance changed before deliberation commit",
                context
            )
        }

        val receipt = AgentCoordinationDeliberationReceipt(
            coordination = request.coordination,
            attemptBindingGeneration = request.attemptBindingGeneration,
            deliberations = receiptEntries
        )
        foundation.observability.record(
            severity = DiagnosticSeverity.INFO,
            code = "AGENT_COORDINATION_DELIBERATION_CREATED",
            message = "coordination deliberation requests created",
            context = context,
            metadata = receiptMetadata(receipt)
        )
        return AgentCoordinationDeliberationResult.Created(receipt)
    }

    private fun exactBinding(
        request: AgentCoordinationDeliberationRequest
    ): AgentCoordinationAttemptBindingSnapshot? {
        val snapshot = attemptBindings.inspect(request.coordination) ?: return null
        return snapshot.takeIf { it.generation == request.attemptBindingGeneration }
    }

    private fun transactionStillValid(
        request: AgentCoordinationDeliberationRequest,
        initialBinding: AgentCoordinationAttemptBindingSnapshot
    ): Boolean {
        val current = exactBinding(request) ?: return false
        if (current.binding != initialBinding.binding) return false
        if (!readyAndMatching(request.coordination, current.binding)) return false
        return allAttemptsValid(current.binding)
    }

    private fun readyAndMatching(
        coordination: ExactAgentCoordinationReference,
        binding: AgentCoordinationAttemptBinding
    ): Boolean {
        val checked = preflight.check(
            AgentCoordinationPreflightRequest(
                coordinationId = coordination.id,
                coordinationGeneration = coordination.generation
            )
        )
        val evidence = (checked as? AgentCoordinationPreflightResult.Ready)?.evidence ?: return false
        return evidence.coordinationId == coordination.id &&
            evidence.coordinationGeneration == coordination.generation &&
            evidence.participants == binding.assignments.map { it.participant }
    }

    private fun allAttemptsValid(binding: AgentCoordinationAttemptBinding): Boolean =
        binding.assignments.all { assignment ->
            validator.validate(assignment.attempt) is AutonomyDeliberationAttemptValidationResult.Valid
        }

    private fun participantSetsMatch(
        request: AgentCoordinationDeliberationRequest,
        binding: AgentCoordinationAttemptBinding
    ): Boolean =
        request.specs.map { it.participant }.sortedWith(exactAgentComparator) ==
            binding.assignments.map { it.participant }.sortedWith(exactAgentComparator)

    private fun compensate(
        coordination: ExactAgentCoordinationReference,
        created: List<AutonomyDeliberationOwnership>,
        reason: String,
        context: pro.liliya.core.logging.LogContext
    ): AgentCoordinationDeliberationResult {
        var compensationFailed = false
        created.asReversed().forEach { ownership ->
            if (!ownership.remove()) {
                val live = deliberation.inspect(ownership.request.id)
                if (live?.generation == ownership.generation) {
                    compensationFailed = true
                }
            }
        }

        if (compensationFailed) {
            foundation.observability.record(
                severity = DiagnosticSeverity.CRITICAL,
                code = "AGENT_COORDINATION_DELIBERATION_COMPENSATION_FAILED",
                message = "coordination deliberation compensation failed",
                context = context,
                metadata = coordinationMetadata(coordination) + mapOf(
                    "createdCount" to created.size.toString(),
                    "failureReason" to reason
                )
            )
            return AgentCoordinationDeliberationResult.Failed(
                "coordination deliberation compensation failed after: $reason"
            )
        }

        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_COORDINATION_DELIBERATION_COMPENSATED",
            message = "coordination deliberation writes compensated",
            context = context,
            metadata = coordinationMetadata(coordination) + mapOf(
                "createdCount" to created.size.toString(),
                "compensationReason" to reason
            )
        )
        return AgentCoordinationDeliberationResult.Rejected(reason)
    }

    private fun reject(
        reason: String,
        context: pro.liliya.core.logging.LogContext
    ): AgentCoordinationDeliberationResult.Rejected {
        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_COORDINATION_DELIBERATION_REJECTED",
            message = reason,
            context = context,
            metadata = mapOf("rejectionReason" to reason)
        )
        return AgentCoordinationDeliberationResult.Rejected(reason)
    }

    private fun requestMetadata(request: AgentCoordinationDeliberationRequest): Map<String, String> =
        coordinationMetadata(request.coordination) + mapOf(
            "attemptBindingGeneration" to request.attemptBindingGeneration.value.toString(),
            "participantCount" to request.specs.size.toString()
        )

    private fun receiptMetadata(receipt: AgentCoordinationDeliberationReceipt): Map<String, String> = buildMap {
        putAll(coordinationMetadata(receipt.coordination))
        put("attemptBindingGeneration", receipt.attemptBindingGeneration.value.toString())
        put("deliberationCount", receipt.deliberations.size.toString())
        receipt.deliberations.forEachIndexed { index, entry ->
            put("deliberation${index}AgentId", entry.participant.id.value)
            put("deliberation${index}AgentGeneration", entry.participant.generation.value.toString())
            put("deliberation${index}RequestId", entry.requestId.value)
            put("deliberation${index}RequestGeneration", entry.requestGeneration.value.toString())
            put("deliberation${index}AutonomyProposalId", entry.attempt.proposalId.value)
            put("deliberation${index}AutonomyGeneration", entry.attempt.proposalGeneration.value.toString())
            put("deliberation${index}AttemptNumber", entry.attempt.attemptNumber.toString())
        }
    }

    private fun coordinationMetadata(
        coordination: ExactAgentCoordinationReference
    ): Map<String, String> = mapOf(
        "agentCoordinationId" to coordination.id.value,
        "agentCoordinationGeneration" to coordination.generation.value.toString()
    )

    private companion object {
        val exactAgentComparator = compareBy<ExactAgentReference>(
            { it.id.value },
            { it.generation.value }
        )
    }
}
