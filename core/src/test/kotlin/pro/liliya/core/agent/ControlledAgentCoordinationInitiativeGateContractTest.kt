package pro.liliya.core.agent

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.core.autonomy.AutonomyBudget
import pro.liliya.core.autonomy.AutonomyComposition
import pro.liliya.core.autonomy.AutonomyDeliberationAttemptResult
import pro.liliya.core.autonomy.AutonomyDeliberationAttemptValidationResult
import pro.liliya.core.autonomy.AutonomyGeneration
import pro.liliya.core.autonomy.AutonomyInstallResult
import pro.liliya.core.autonomy.AutonomyOrigin
import pro.liliya.core.autonomy.AutonomyPriority
import pro.liliya.core.autonomy.AutonomyProposal
import pro.liliya.core.autonomy.AutonomyProposalId
import pro.liliya.core.autonomy.AutonomySourceId
import pro.liliya.core.autonomy.ControlledAutonomyDeliberationGate
import pro.liliya.core.autonomy.AutonomyAttemptReference
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class ControlledAgentCoordinationInitiativeGateContractTest {
    private data class Fixture(
        val foundation: FoundationComposition,
        val bindings: AgentCoordinationWorkBindingComposition,
        val autonomy: AutonomyComposition,
        val autonomyGate: ControlledAutonomyDeliberationGate
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "coord-attempt-${sequence.incrementAndGet()}" }
        )
        val autonomy = AutonomyComposition(foundation)
        return Fixture(
            foundation = foundation,
            bindings = AgentCoordinationWorkBindingComposition(foundation),
            autonomy = autonomy,
            autonomyGate = ControlledAutonomyDeliberationGate(foundation, autonomy)
        )
    }

    private fun participant(id: String, generation: Long) =
        ExactAgentReference(AgentId(id), AgentGeneration(generation))

    private fun installAutonomy(
        f: Fixture,
        participant: ExactAgentReference,
        id: String,
        budget: Int = 2
    ): ExactAutonomyReference {
        val installed = assertIs<AutonomyInstallResult.Installed>(
            f.autonomy.install(
                AutonomyProposal(
                    id = AutonomyProposalId(id),
                    origin = AutonomyOrigin.Declared(AutonomySourceId("test")),
                    objective = "private objective $id",
                    triggerDescription = "private trigger $id",
                    priority = AutonomyPriority.NORMAL,
                    budget = AutonomyBudget(budget),
                    createdAt = Instant.parse("2026-08-30T01:00:00Z")
                )
            )
        ).ownership
        return ExactAutonomyReference(installed.proposal.id, installed.generation)
    }

    private fun binding(
        coordination: ExactAgentCoordinationReference,
        assignments: List<Pair<ExactAgentReference, ExactAutonomyReference>>
    ) = AgentCoordinationWorkBinding(
        coordination = coordination,
        assignments = assignments.map { (participant, autonomy) ->
            AgentCoordinationWorkAssignment(participant, autonomy)
        }
    )

    private fun readyChecker(
        coordination: ExactAgentCoordinationReference,
        participants: List<ExactAgentReference>,
        rejectOnCall: Int? = null
    ): AgentCoordinationPreflightChecker {
        val calls = AtomicInteger(0)
        return AgentCoordinationPreflightChecker {
            if (rejectOnCall != null && calls.incrementAndGet() == rejectOnCall) {
                AgentCoordinationPreflightResult.Rejected("synthetic governance change")
            } else {
                AgentCoordinationPreflightResult.Ready(
                    AgentCoordinationReadyEvidence(
                        coordinationId = coordination.id,
                        coordinationGeneration = coordination.generation,
                        participants = participants.sortedWith(
                            compareBy({ it.id.value }, { it.generation.value })
                        )
                    )
                )
            }
        }
    }

    private fun directClaimer(
        gate: ControlledAutonomyDeliberationGate,
        onClaim: ((ExactAgentReference, ExactAutonomyReference) -> Unit)? = null
    ) = AgentCoordinationParticipantAttemptClaimer { participant, autonomy ->
        val result = gate.claimAttempt(autonomy.proposalId, autonomy.generation)
        if (result is AutonomyDeliberationAttemptResult.Claimed) {
            onClaim?.invoke(participant, autonomy)
            AgentInitiativeAttemptResult.Claimed(result)
        } else {
            AgentInitiativeAttemptResult.Rejected(
                (result as AutonomyDeliberationAttemptResult.Rejected).reason
            )
        }
    }

    private fun validate(
        f: Fixture,
        autonomy: ExactAutonomyReference,
        attemptNumber: Int
    ) = f.autonomyGate.validateAttempt(
        AutonomyAttemptReference(
            proposalId = autonomy.proposalId,
            proposalGeneration = autonomy.generation,
            attemptNumber = attemptNumber
        )
    )

    @Test
    fun exact_stable_coordination_claims_one_attempt_for_every_bound_participant() {
        val f = fixture()
        val coordination = ExactAgentCoordinationReference(
            AgentCoordinationId("coord-1"), AgentCoordinationGeneration(1)
        )
        val a = participant("agent-a", 1)
        val b = participant("agent-b", 2)
        val autonomyA = installAutonomy(f, a, "autonomy-a")
        val autonomyB = installAutonomy(f, b, "autonomy-b")
        assertIs<AgentCoordinationWorkBindingInstallResult.Installed>(
            f.bindings.install(binding(coordination, listOf(a to autonomyA, b to autonomyB)))
        )

        val gate = ControlledAgentCoordinationInitiativeGate(
            foundation = f.foundation,
            bindings = f.bindings,
            preflight = readyChecker(coordination, listOf(a, b)),
            claimer = directClaimer(f.autonomyGate),
            autonomyGate = f.autonomyGate,
            testOnly = Unit
        )

        val claimed = assertIs<AgentCoordinationAttemptResult.Claimed>(
            gate.claimAttempts(coordination.id, coordination.generation)
        )

        assertEquals(2, claimed.receipt.attempts.size)
        claimed.receipt.attempts.forEach { attempt ->
            assertIs<AutonomyDeliberationAttemptValidationResult.Valid>(
                validate(f, attempt.autonomy, attempt.attemptNumber)
            )
        }
    }

    @Test
    fun governance_change_after_first_claim_invalidates_claim_before_any_receipt_returns() {
        val f = fixture()
        val coordination = ExactAgentCoordinationReference(
            AgentCoordinationId("coord-race"), AgentCoordinationGeneration(1)
        )
        val a = participant("agent-a", 1)
        val b = participant("agent-b", 1)
        val autonomyA = installAutonomy(f, a, "race-a")
        val autonomyB = installAutonomy(f, b, "race-b")
        assertIs<AgentCoordinationWorkBindingInstallResult.Installed>(
            f.bindings.install(binding(coordination, listOf(a to autonomyA, b to autonomyB)))
        )

        val gate = ControlledAgentCoordinationInitiativeGate(
            foundation = f.foundation,
            bindings = f.bindings,
            preflight = readyChecker(coordination, listOf(a, b), rejectOnCall = 3),
            claimer = directClaimer(f.autonomyGate),
            autonomyGate = f.autonomyGate,
            testOnly = Unit
        )

        assertIs<AgentCoordinationAttemptResult.Rejected>(
            gate.claimAttempts(coordination.id, coordination.generation)
        )
        assertIs<AutonomyDeliberationAttemptValidationResult.Rejected>(
            validate(f, autonomyA, 1)
        )
    }

    @Test
    fun later_participant_claim_rejection_invalidates_already_claimed_participants() {
        val f = fixture()
        val coordination = ExactAgentCoordinationReference(
            AgentCoordinationId("coord-partial"), AgentCoordinationGeneration(1)
        )
        val a = participant("agent-a", 1)
        val b = participant("agent-b", 1)
        val autonomyA = installAutonomy(f, a, "partial-a")
        val autonomyB = installAutonomy(f, b, "partial-b")
        assertIs<AgentCoordinationWorkBindingInstallResult.Installed>(
            f.bindings.install(binding(coordination, listOf(a to autonomyA, b to autonomyB)))
        )
        f.autonomyGate.cancel(autonomyB.proposalId, autonomyB.generation)

        val gate = ControlledAgentCoordinationInitiativeGate(
            foundation = f.foundation,
            bindings = f.bindings,
            preflight = readyChecker(coordination, listOf(a, b)),
            claimer = directClaimer(f.autonomyGate),
            autonomyGate = f.autonomyGate,
            testOnly = Unit
        )

        assertIs<AgentCoordinationAttemptResult.Rejected>(
            gate.claimAttempts(coordination.id, coordination.generation)
        )
        assertIs<AutonomyDeliberationAttemptValidationResult.Rejected>(
            validate(f, autonomyA, 1)
        )
    }

    @Test
    fun binding_removed_during_first_claim_invalidates_that_claim_and_stops_transaction() {
        val f = fixture()
        val coordination = ExactAgentCoordinationReference(
            AgentCoordinationId("coord-binding-race"), AgentCoordinationGeneration(1)
        )
        val a = participant("agent-a", 1)
        val b = participant("agent-b", 1)
        val autonomyA = installAutonomy(f, a, "binding-a")
        val autonomyB = installAutonomy(f, b, "binding-b")
        val bindingOwnership = assertIs<AgentCoordinationWorkBindingInstallResult.Installed>(
            f.bindings.install(binding(coordination, listOf(a to autonomyA, b to autonomyB)))
        ).ownership
        val claimCount = AtomicInteger(0)

        val gate = ControlledAgentCoordinationInitiativeGate(
            foundation = f.foundation,
            bindings = f.bindings,
            preflight = readyChecker(coordination, listOf(a, b)),
            claimer = directClaimer(f.autonomyGate) { _, _ ->
                if (claimCount.incrementAndGet() == 1) bindingOwnership.remove()
            },
            autonomyGate = f.autonomyGate,
            testOnly = Unit
        )

        assertIs<AgentCoordinationAttemptResult.Rejected>(
            gate.claimAttempts(coordination.id, coordination.generation)
        )
        assertEquals(1, claimCount.get())
        assertIs<AutonomyDeliberationAttemptValidationResult.Rejected>(
            validate(f, autonomyA, 1)
        )
    }
}
