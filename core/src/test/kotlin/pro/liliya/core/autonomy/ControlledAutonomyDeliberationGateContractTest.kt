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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ControlledAutonomyDeliberationGateContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val composition: AutonomyComposition,
        val gate: ControlledAutonomyDeliberationGate
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "autonomy-deliberation-${sequence.incrementAndGet()}" }
        )
        val composition = AutonomyComposition(foundation)
        return Fixture(
            logs = logs,
            composition = composition,
            gate = ControlledAutonomyDeliberationGate(foundation, composition)
        )
    }

    private fun proposal(
        id: String = "autonomy-1",
        objective: String = "private objective",
        trigger: String = "private trigger",
        maxAttempts: Int = 2
    ) = AutonomyProposal(
        id = AutonomyProposalId(id),
        origin = AutonomyOrigin.Declared(
            sourceId = AutonomySourceId("declared-goal-context"),
            sourceReference = AutonomySourceReference("source-1")
        ),
        objective = objective,
        triggerDescription = trigger,
        priority = AutonomyPriority.NORMAL,
        budget = AutonomyBudget(maxAttempts),
        createdAt = Instant.parse("2026-08-29T15:15:00Z")
    )

    @Test
    fun exact_live_generation_can_claim_only_within_budget() {
        val f = fixture()
        val ownership = assertIs<AutonomyInstallResult.Installed>(
            f.composition.install(proposal(maxAttempts = 2))
        ).ownership

        val first = assertIs<AutonomyDeliberationAttemptResult.Claimed>(
            f.gate.claimAttempt(ownership.proposal.id, ownership.generation)
        )
        val second = assertIs<AutonomyDeliberationAttemptResult.Claimed>(
            f.gate.claimAttempt(ownership.proposal.id, ownership.generation)
        )
        val exhausted = f.gate.claimAttempt(ownership.proposal.id, ownership.generation)

        assertEquals(1, first.evidence.attemptNumber)
        assertEquals(2, second.evidence.attemptNumber)
        assertIs<AutonomyDeliberationAttemptResult.Rejected>(exhausted)
    }

    @Test
    fun stale_generation_rejects_without_consuming_replacement_budget() {
        val f = fixture()
        val stale = assertIs<AutonomyInstallResult.Installed>(f.composition.install(proposal())).ownership
        assertTrue(stale.remove())
        val replacement = assertIs<AutonomyInstallResult.Installed>(
            f.composition.install(proposal(objective = "replacement private objective"))
        ).ownership

        assertIs<AutonomyDeliberationAttemptResult.Rejected>(
            f.gate.claimAttempt(stale.proposal.id, stale.generation)
        )
        val replacementAttempt = assertIs<AutonomyDeliberationAttemptResult.Claimed>(
            f.gate.claimAttempt(replacement.proposal.id, replacement.generation)
        )

        assertEquals(1, replacementAttempt.evidence.attemptNumber)
    }

    @Test
    fun exact_cancellation_blocks_future_attempts() {
        val f = fixture()
        val ownership = assertIs<AutonomyInstallResult.Installed>(f.composition.install(proposal())).ownership

        assertIs<AutonomyDeliberationCancellationResult.Cancelled>(
            f.gate.cancel(ownership.proposal.id, ownership.generation)
        )
        assertIs<AutonomyDeliberationAttemptResult.Rejected>(
            f.gate.claimAttempt(ownership.proposal.id, ownership.generation)
        )
    }

    @Test
    fun stale_cancellation_cannot_cancel_replacement() {
        val f = fixture()
        val stale = assertIs<AutonomyInstallResult.Installed>(f.composition.install(proposal())).ownership
        assertTrue(stale.remove())
        val replacement = assertIs<AutonomyInstallResult.Installed>(
            f.composition.install(proposal(objective = "replacement private objective"))
        ).ownership

        assertIs<AutonomyDeliberationCancellationResult.Rejected>(
            f.gate.cancel(stale.proposal.id, stale.generation)
        )
        assertIs<AutonomyDeliberationAttemptResult.Claimed>(
            f.gate.claimAttempt(replacement.proposal.id, replacement.generation)
        )
    }

    @Test
    fun private_payload_is_absent_from_gate_observability() {
        val f = fixture()
        val secretObjective = "never-log-autonomy-gate-objective"
        val secretTrigger = "never-log-autonomy-gate-trigger"
        val ownership = assertIs<AutonomyInstallResult.Installed>(
            f.composition.install(
                proposal(objective = secretObjective, trigger = secretTrigger)
            )
        ).ownership

        f.gate.claimAttempt(ownership.proposal.id, ownership.generation)
        f.gate.cancel(ownership.proposal.id, ownership.generation)

        val secrets = setOf(secretObjective, secretTrigger)
        assertFalse(f.logs.snapshot().any { event ->
            event.message in secrets || event.metadata.values.any { it in secrets }
        })
    }

    @Test
    fun gate_observability_contains_no_decision_authority_execution_scheduler_or_agent_semantics() {
        val f = fixture()
        val ownership = assertIs<AutonomyInstallResult.Installed>(f.composition.install(proposal())).ownership
        f.gate.claimAttempt(ownership.proposal.id, ownership.generation)

        val forbidden = setOf(
            "decision", "approved", "authority", "authorized", "capability", "permission",
            "execution", "execute", "executed", "executor", "scheduled", "scheduler", "agent"
        )
        assertFalse(f.logs.snapshot().any { event ->
            event.metadata.keys.any { key -> forbidden.any { key.lowercase().contains(it) } }
        })
    }
}
