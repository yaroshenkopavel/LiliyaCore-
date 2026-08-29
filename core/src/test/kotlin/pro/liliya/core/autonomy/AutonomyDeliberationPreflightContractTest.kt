package pro.liliya.core.autonomy

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AutonomyDeliberationPreflightContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val autonomy: AutonomyComposition,
        val gate: ControlledAutonomyDeliberationGate,
        val deliberation: AutonomyDeliberationComposition,
        val preflight: AutonomyDeliberationPreflight
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "autonomy-live-${sequence.incrementAndGet()}" }
        )
        val autonomy = AutonomyComposition(foundation)
        val gate = ControlledAutonomyDeliberationGate(foundation, autonomy)
        val deliberation = AutonomyDeliberationComposition(foundation)
        return Fixture(
            logs = logs,
            autonomy = autonomy,
            gate = gate,
            deliberation = deliberation,
            preflight = AutonomyDeliberationPreflight(foundation, deliberation, gate)
        )
    }

    private fun proposal(id: String = "autonomy-1") = AutonomyProposal(
        id = AutonomyProposalId(id),
        origin = AutonomyOrigin.Declared(AutonomySourceId("goal-context")),
        objective = "private autonomy objective",
        triggerDescription = "private autonomy trigger",
        priority = AutonomyPriority.HIGH,
        budget = AutonomyBudget(2),
        createdAt = Instant.parse("2026-08-29T15:10:00Z")
    )

    private fun installRequest(
        f: Fixture,
        reference: AutonomyAttemptReference,
        objective: String = "private deliberation objective"
    ) = assertIs<AutonomyDeliberationInstallResult.Installed>(
        f.deliberation.install(
            AutonomyDeliberationRequest(
                id = AutonomyDeliberationRequestId("request-1"),
                autonomy = reference,
                objective = objective,
                createdAt = Instant.parse("2026-08-29T15:11:00Z")
            )
        )
    ).ownership

    @Test
    fun exact_live_request_and_claimed_attempt_return_ready_evidence_only() {
        val f = fixture()
        val autonomyOwnership = assertIs<AutonomyInstallResult.Installed>(f.autonomy.install(proposal())).ownership
        val claim = assertIs<AutonomyDeliberationAttemptResult.Claimed>(
            f.gate.claimAttempt(autonomyOwnership.proposal.id, autonomyOwnership.generation)
        )
        val requestOwnership = installRequest(
            f,
            AutonomyAttemptReference(
                proposalId = claim.evidence.proposal.id,
                proposalGeneration = claim.evidence.generation,
                attemptNumber = claim.evidence.attemptNumber
            )
        )

        val result = f.preflight.check(requestOwnership.request.id, requestOwnership.generation)

        assertIs<AutonomyDeliberationPreflightResult.Ready>(result)
        assertTrue(f.logs.snapshot().any { it.marker == "AUTONOMY_DELIBERATION_PREFLIGHT_READY" })
    }

    @Test
    fun request_for_unclaimed_attempt_rejects_fail_closed() {
        val f = fixture()
        val autonomyOwnership = assertIs<AutonomyInstallResult.Installed>(f.autonomy.install(proposal())).ownership
        val requestOwnership = installRequest(
            f,
            AutonomyAttemptReference(
                proposalId = autonomyOwnership.proposal.id,
                proposalGeneration = autonomyOwnership.generation,
                attemptNumber = 1
            )
        )

        val result = f.preflight.check(requestOwnership.request.id, requestOwnership.generation)

        assertIs<AutonomyDeliberationPreflightResult.Rejected>(result)
    }

    @Test
    fun cancellation_after_request_creation_invalidates_preflight() {
        val f = fixture()
        val autonomyOwnership = assertIs<AutonomyInstallResult.Installed>(f.autonomy.install(proposal())).ownership
        val claim = assertIs<AutonomyDeliberationAttemptResult.Claimed>(
            f.gate.claimAttempt(autonomyOwnership.proposal.id, autonomyOwnership.generation)
        )
        val requestOwnership = installRequest(
            f,
            AutonomyAttemptReference(
                proposalId = claim.evidence.proposal.id,
                proposalGeneration = claim.evidence.generation,
                attemptNumber = claim.evidence.attemptNumber
            )
        )
        assertIs<AutonomyDeliberationCancellationResult.Cancelled>(
            f.gate.cancel(autonomyOwnership.proposal.id, autonomyOwnership.generation)
        )

        val result = f.preflight.check(requestOwnership.request.id, requestOwnership.generation)

        assertIs<AutonomyDeliberationPreflightResult.Rejected>(result)
    }

    @Test
    fun stale_autonomy_replacement_invalidates_old_request_without_touching_replacement() {
        val f = fixture()
        val original = assertIs<AutonomyInstallResult.Installed>(f.autonomy.install(proposal())).ownership
        val claim = assertIs<AutonomyDeliberationAttemptResult.Claimed>(
            f.gate.claimAttempt(original.proposal.id, original.generation)
        )
        val requestOwnership = installRequest(
            f,
            AutonomyAttemptReference(
                claim.evidence.proposal.id,
                claim.evidence.generation,
                claim.evidence.attemptNumber
            )
        )
        assertTrue(original.remove())
        val replacement = assertIs<AutonomyInstallResult.Installed>(f.autonomy.install(proposal())).ownership

        val result = f.preflight.check(requestOwnership.request.id, requestOwnership.generation)

        assertIs<AutonomyDeliberationPreflightResult.Rejected>(result)
        assertTrue(f.autonomy.contains(replacement.proposal.id))
    }

    @Test
    fun stale_deliberation_request_generation_rejects() {
        val f = fixture()
        val autonomyOwnership = assertIs<AutonomyInstallResult.Installed>(f.autonomy.install(proposal())).ownership
        val claim = assertIs<AutonomyDeliberationAttemptResult.Claimed>(
            f.gate.claimAttempt(autonomyOwnership.proposal.id, autonomyOwnership.generation)
        )
        val stale = installRequest(
            f,
            AutonomyAttemptReference(claim.evidence.proposal.id, claim.evidence.generation, claim.evidence.attemptNumber)
        )
        assertTrue(stale.remove())
        installRequest(
            f,
            AutonomyAttemptReference(claim.evidence.proposal.id, claim.evidence.generation, claim.evidence.attemptNumber)
        )

        val result = f.preflight.check(stale.request.id, stale.generation)

        assertIs<AutonomyDeliberationPreflightResult.Rejected>(result)
    }

    @Test
    fun private_payload_is_absent_from_preflight_observability() {
        val f = fixture()
        val secretProposal = "never-log-autonomy-objective"
        val secretRequest = "never-log-deliberation-objective"
        val autonomyOwnership = assertIs<AutonomyInstallResult.Installed>(
            f.autonomy.install(
                AutonomyProposal(
                    id = AutonomyProposalId("autonomy-1"),
                    origin = AutonomyOrigin.Declared(AutonomySourceId("goal-context")),
                    objective = secretProposal,
                    triggerDescription = "never-log-autonomy-trigger",
                    priority = AutonomyPriority.NORMAL,
                    budget = AutonomyBudget(1),
                    createdAt = Instant.parse("2026-08-29T15:10:00Z")
                )
            )
        ).ownership
        val claim = assertIs<AutonomyDeliberationAttemptResult.Claimed>(
            f.gate.claimAttempt(autonomyOwnership.proposal.id, autonomyOwnership.generation)
        )
        val requestOwnership = installRequest(
            f,
            AutonomyAttemptReference(claim.evidence.proposal.id, claim.evidence.generation, claim.evidence.attemptNumber),
            objective = secretRequest
        )

        f.preflight.check(requestOwnership.request.id, requestOwnership.generation)

        val secrets = setOf(secretProposal, secretRequest, "never-log-autonomy-trigger")
        assertFalse(f.logs.snapshot().any { event ->
            event.message in secrets || event.metadata.values.any { it in secrets }
        })
    }
}
