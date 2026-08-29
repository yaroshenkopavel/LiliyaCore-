package pro.liliya.core.agent

import java.time.Instant
import pro.liliya.core.autonomy.AutonomyBudget
import pro.liliya.core.autonomy.AutonomyOwnership
import pro.liliya.core.autonomy.AutonomyPriority
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.foundation.FoundationComposition

data class AgentDelegatedInitiativeRequest(
    val delegationId: AgentDelegationId,
    val delegationGeneration: AgentDelegationGeneration,
    val autonomyProposalId: AutonomyProposalId,
    val objective: String,
    val triggerDescription: String,
    val priority: AutonomyPriority,
    val budget: AutonomyBudget,
    val createdAt: Instant
) {
    init {
        require(objective.isNotBlank()) { "delegated initiative objective must not be blank" }
        require(triggerDescription.isNotBlank()) { "delegated initiative trigger description must not be blank" }
    }
}

data class AgentDelegatedInitiativeReceipt(
    val delegation: ExactAgentDelegationReference,
    val child: ExactAgentReference,
    val autonomy: ExactAutonomyReference
)

interface AgentDelegatedInitiativeOwnership {
    val receipt: AgentDelegatedInitiativeReceipt

    /**
     * Removes the exact created Autonomy first and then its structural binding.
     *
     * This ordering is fail-closed: if Autonomy removal fails the binding is kept intact. If the
     * binding removal fails after Autonomy removal, retrying remove() attempts only the remaining
     * binding cleanup.
     */
    fun remove(): Boolean
}

sealed interface AgentDelegatedInitiativeResult {
    data class Created(
        val ownership: AgentDelegatedInitiativeOwnership
    ) : AgentDelegatedInitiativeResult

    data class Rejected(val reason: String) : AgentDelegatedInitiativeResult {
        init { require(reason.isNotBlank()) { "delegated initiative rejection reason must not be blank" } }
    }

    data class Failed(val reason: String) : AgentDelegatedInitiativeResult {
        init { require(reason.isNotBlank()) { "delegated initiative failure reason must not be blank" } }
    }
}

internal fun interface AgentDelegationPreflightChecker {
    fun check(request: AgentDelegationPreflightRequest): AgentDelegationPreflightResult
}

internal fun interface AgentChildInitiativeCreator {
    fun create(request: AgentInitiativeRequest): AgentInitiativeResult
}

internal fun interface AgentDelegatedBindingInstaller {
    fun install(binding: AgentDelegatedWorkBinding): AgentDelegatedWorkBindingInstallResult
}

/**
 * Compensated creation boundary for delegation-originated Agent work.
 *
 * The bridge performs a fresh exact delegation preflight, creates an ordinary child Agent
 * initiative through the already-frozen Agent boundary, then performs a second fresh preflight
 * before committing the exact delegation↔Autonomy binding. The second check closes the parent/
 * child/delegation TOCTOU window around the Autonomy write.
 *
 * If post-create revalidation or binding installation fails, the exact newly-created Autonomy
 * ownership is removed before a normal rejection is returned. A failed compensation is surfaced as
 * Failed rather than being hidden as an ordinary rejection.
 *
 * Successful callers receive one composite ownership rather than independent mutable Autonomy and
 * binding handles, so the transaction invariant cannot be split accidentally by the public API.
 * The binding is structural evidence only and grants no permission or execution right.
 */
class ControlledAgentDelegatedInitiative private constructor(
    private val foundation: FoundationComposition,
    private val preflight: AgentDelegationPreflightChecker,
    private val childInitiative: AgentChildInitiativeCreator,
    private val bindings: AgentDelegatedBindingInstaller
) {
    constructor(
        foundation: FoundationComposition,
        preflight: ControlledAgentDelegationPreflight,
        childInitiative: ControlledAgentInitiative,
        bindings: AgentDelegatedWorkBindingComposition
    ) : this(
        foundation = foundation,
        preflight = AgentDelegationPreflightChecker(preflight::check),
        childInitiative = AgentChildInitiativeCreator(childInitiative::create),
        bindings = AgentDelegatedBindingInstaller(bindings::install)
    )

    internal constructor(
        foundation: FoundationComposition,
        preflight: AgentDelegationPreflightChecker,
        childInitiative: AgentChildInitiativeCreator,
        bindings: AgentDelegatedBindingInstaller,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit
    ) : this(foundation, preflight, childInitiative, bindings)

    fun create(request: AgentDelegatedInitiativeRequest): AgentDelegatedInitiativeResult {
        val exactRequest = AgentDelegationPreflightRequest(
            delegationId = request.delegationId,
            delegationGeneration = request.delegationGeneration
        )

        val initial = when (val checked = preflight.check(exactRequest)) {
            is AgentDelegationPreflightResult.Ready -> checked.evidence
            is AgentDelegationPreflightResult.Rejected ->
                return AgentDelegatedInitiativeResult.Rejected(
                    "delegation preflight rejected: ${checked.reason}"
                )
        }

        val created = when (
            val result = childInitiative.create(
                AgentInitiativeRequest(
                    agentId = initial.child.id,
                    agentGeneration = initial.child.generation,
                    autonomyProposalId = request.autonomyProposalId,
                    objective = request.objective,
                    triggerDescription = request.triggerDescription,
                    priority = request.priority,
                    budget = request.budget,
                    createdAt = request.createdAt
                )
            )
        ) {
            is AgentInitiativeResult.Created -> result.ownership
            is AgentInitiativeResult.Rejected ->
                return AgentDelegatedInitiativeResult.Rejected(
                    "child initiative rejected: ${result.reason}"
                )
        }

        val confirmed = when (val checked = preflight.check(exactRequest)) {
            is AgentDelegationPreflightResult.Ready -> checked.evidence
            is AgentDelegationPreflightResult.Rejected ->
                return compensate(
                    created = created,
                    reason = "delegation changed during initiative creation: ${checked.reason}"
                )
        }

        if (
            confirmed.delegationId != initial.delegationId ||
            confirmed.delegationGeneration != initial.delegationGeneration ||
            confirmed.parent != initial.parent ||
            confirmed.child != initial.child
        ) {
            return compensate(
                created = created,
                reason = "delegation evidence changed during initiative creation"
            )
        }

        val binding = AgentDelegatedWorkBinding(
            delegation = ExactAgentDelegationReference(
                id = confirmed.delegationId,
                generation = confirmed.delegationGeneration
            ),
            child = confirmed.child,
            autonomy = ExactAutonomyReference(
                proposalId = created.proposal.id,
                generation = created.generation
            )
        )

        return when (val installed = bindings.install(binding)) {
            is AgentDelegatedWorkBindingInstallResult.Installed -> {
                val receipt = AgentDelegatedInitiativeReceipt(
                    delegation = binding.delegation,
                    child = binding.child,
                    autonomy = binding.autonomy
                )
                record(
                    severity = DiagnosticSeverity.INFO,
                    code = "AGENT_DELEGATED_INITIATIVE_CREATED",
                    message = "delegated initiative created and structurally bound",
                    binding = binding
                )
                AgentDelegatedInitiativeResult.Created(
                    ownership = compositeOwnership(
                        receipt = receipt,
                        autonomy = created,
                        binding = installed.ownership
                    )
                )
            }

            is AgentDelegatedWorkBindingInstallResult.Rejected -> compensate(
                created = created,
                reason = "delegated work binding rejected: ${installed.reason}"
            )
        }
    }

    private fun compensate(
        created: AutonomyOwnership,
        reason: String
    ): AgentDelegatedInitiativeResult {
        val removed = created.remove()
        if (!removed) {
            foundation.observability.record(
                severity = DiagnosticSeverity.CRITICAL,
                code = "AGENT_DELEGATED_INITIATIVE_COMPENSATION_FAILED",
                message = "delegated initiative compensation failed",
                context = foundation.rootContext(
                    operation = "compensateAgentDelegatedInitiative",
                    component = "AgentDelegation",
                    metadata = mapOf(
                        "autonomyProposalId" to created.proposal.id.value,
                        "autonomyGeneration" to created.generation.value.toString()
                    )
                ),
                metadata = mapOf("failureReason" to reason)
            )
            return AgentDelegatedInitiativeResult.Failed(
                "delegated initiative compensation failed after: $reason"
            )
        }

        foundation.observability.record(
            severity = DiagnosticSeverity.WARNING,
            code = "AGENT_DELEGATED_INITIATIVE_COMPENSATED",
            message = "delegated initiative rolled back",
            context = foundation.rootContext(
                operation = "compensateAgentDelegatedInitiative",
                component = "AgentDelegation",
                metadata = mapOf(
                    "autonomyProposalId" to created.proposal.id.value,
                    "autonomyGeneration" to created.generation.value.toString()
                )
            ),
            metadata = mapOf("compensationReason" to reason)
        )
        return AgentDelegatedInitiativeResult.Rejected(reason)
    }

    private fun compositeOwnership(
        receipt: AgentDelegatedInitiativeReceipt,
        autonomy: AutonomyOwnership,
        binding: AgentDelegatedWorkBindingOwnership
    ): AgentDelegatedInitiativeOwnership = object : AgentDelegatedInitiativeOwnership {
        private val lock = Any()
        private var autonomyRemoved = false
        private var fullyRemoved = false

        override val receipt: AgentDelegatedInitiativeReceipt = receipt

        override fun remove(): Boolean = synchronized(lock) {
            if (fullyRemoved) return@synchronized false

            if (!autonomyRemoved) {
                if (!autonomy.remove()) return@synchronized false
                autonomyRemoved = true
            }

            if (!binding.remove()) {
                foundation.observability.record(
                    severity = DiagnosticSeverity.CRITICAL,
                    code = "AGENT_DELEGATED_INITIATIVE_BINDING_CLEANUP_FAILED",
                    message = "delegated initiative binding cleanup failed",
                    context = foundation.rootContext(
                        operation = "removeAgentDelegatedInitiative",
                        component = "AgentDelegation",
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

    private fun record(
        severity: DiagnosticSeverity,
        code: String,
        message: String,
        binding: AgentDelegatedWorkBinding
    ) {
        foundation.observability.record(
            severity = severity,
            code = code,
            message = message,
            context = foundation.rootContext(
                operation = "createAgentDelegatedInitiative",
                component = "AgentDelegation",
                metadata = metadata(binding)
            ),
            metadata = metadata(binding)
        )
    }

    private fun receiptMetadata(receipt: AgentDelegatedInitiativeReceipt): Map<String, String> = mapOf(
        "agentDelegationId" to receipt.delegation.id.value,
        "agentDelegationGeneration" to receipt.delegation.generation.value.toString(),
        "childAgentId" to receipt.child.id.value,
        "childAgentGeneration" to receipt.child.generation.value.toString(),
        "autonomyProposalId" to receipt.autonomy.proposalId.value,
        "autonomyGeneration" to receipt.autonomy.generation.value.toString()
    )

    private fun metadata(binding: AgentDelegatedWorkBinding): Map<String, String> = mapOf(
        "agentDelegationId" to binding.delegation.id.value,
        "agentDelegationGeneration" to binding.delegation.generation.value.toString(),
        "childAgentId" to binding.child.id.value,
        "childAgentGeneration" to binding.child.generation.value.toString(),
        "autonomyProposalId" to binding.autonomy.proposalId.value,
        "autonomyGeneration" to binding.autonomy.generation.value.toString()
    )
}
