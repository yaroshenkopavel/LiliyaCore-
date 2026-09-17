package pro.liliya.core.autonomy

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.CapabilityId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.execution.ExecutionActionId
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.InMemoryPersistentRecordBackend
import pro.liliya.core.persistence.PersistentRecordBackend
import pro.liliya.core.persistence.PersistentStoreId

class PersistentAutonomyGoalExecutionJournalContractTest {
    private val storeId = PersistentStoreId("autonomy-goal-execution-test")

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger(0)
        val logs = InMemoryLogWriter()
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "autonomy-execution-${sequence.incrementAndGet()}" }
        )
    }

    private fun open(
        foundation: FoundationComposition,
        backend: PersistentRecordBackend
    ): PersistentAutonomyGoalExecutionJournal =
        assertIs<PersistentAutonomyGoalExecutionJournalOpenResult.Opened>(
            PersistentAutonomyGoalExecutionJournal.open(foundation, storeId, backend)
        ).journal

    private fun plan() = AutonomyGoalExecutionPlan(
        id = AutonomyGoalExecutionTransactionId("tx-1"),
        identity = AutonomyGoalAuthorityIdentity(
            goalId = AutonomyGoalId("goal-1"),
            proposalId = AutonomyGoalProposalId("proposal-1"),
            scope = AutonomyGoalScope("owner-local-scope"),
            actionClass = AutonomyActionClass("local-read")
        ),
        principal = AuthorityPrincipal("local-owner"),
        capability = CapabilityId("capability.local.read"),
        actionId = ExecutionActionId("action.local.read"),
        createdAt = Instant.parse("2026-09-17T09:00:00Z")
    )

    @Test
    fun authorized_state_is_invalidated_on_restart_and_requires_fresh_authority_transition() {
        val f = foundation()
        val backend = InMemoryPersistentRecordBackend()
        val first = open(f, backend)
        val prepared = assertIs<PersistentAutonomyGoalExecutionPrepareResult.Prepared>(
            first.prepare(plan())
        ).snapshot
        assertIs<PersistentAutonomyGoalExecutionTransitionResult.Committed>(
            first.transition(
                prepared.reference,
                AutonomyGoalExecutionState.PLANNED,
                AutonomyGoalExecutionState.AUTHORIZED
            )
        )

        val reopened = open(f, backend)
        val recovered = requireNotNull(reopened.inspect(prepared.plan.id))
        assertEquals(AutonomyGoalExecutionState.RECOVERY_REQUIRED, recovered.state)
        assertIs<PersistentAutonomyGoalExecutionTransitionResult.Rejected>(
            reopened.transition(
                recovered.reference,
                AutonomyGoalExecutionState.RECOVERY_REQUIRED,
                AutonomyGoalExecutionState.EXECUTING
            )
        )
        assertIs<PersistentAutonomyGoalExecutionTransitionResult.Committed>(
            reopened.transition(
                recovered.reference,
                AutonomyGoalExecutionState.RECOVERY_REQUIRED,
                AutonomyGoalExecutionState.AUTHORIZED
            )
        )
    }

    @Test
    fun interrupted_executing_state_becomes_terminal_outcome_unknown_and_cannot_replay() {
        val f = foundation()
        val backend = InMemoryPersistentRecordBackend()
        val first = open(f, backend)
        val prepared = assertIs<PersistentAutonomyGoalExecutionPrepareResult.Prepared>(
            first.prepare(plan())
        ).snapshot
        assertIs<PersistentAutonomyGoalExecutionTransitionResult.Committed>(
            first.transition(
                prepared.reference,
                AutonomyGoalExecutionState.PLANNED,
                AutonomyGoalExecutionState.AUTHORIZED
            )
        )
        assertIs<PersistentAutonomyGoalExecutionTransitionResult.Committed>(
            first.transition(
                prepared.reference,
                AutonomyGoalExecutionState.AUTHORIZED,
                AutonomyGoalExecutionState.EXECUTING
            )
        )

        val reopened = open(f, backend)
        val unknown = requireNotNull(reopened.inspect(prepared.plan.id))
        assertEquals(AutonomyGoalExecutionState.OUTCOME_UNKNOWN, unknown.state)
        assertIs<PersistentAutonomyGoalExecutionTransitionResult.Rejected>(
            reopened.transition(
                unknown.reference,
                AutonomyGoalExecutionState.OUTCOME_UNKNOWN,
                AutonomyGoalExecutionState.AUTHORIZED
            )
        )
        assertIs<PersistentAutonomyGoalExecutionTransitionResult.Rejected>(
            reopened.transition(
                unknown.reference,
                AutonomyGoalExecutionState.OUTCOME_UNKNOWN,
                AutonomyGoalExecutionState.EXECUTING
            )
        )
    }

    @Test
    fun successful_terminal_transaction_is_single_use_and_duplicate_live_id_rejects() {
        val f = foundation()
        val journal = open(f, InMemoryPersistentRecordBackend())
        val prepared = assertIs<PersistentAutonomyGoalExecutionPrepareResult.Prepared>(
            journal.prepare(plan())
        ).snapshot
        assertIs<PersistentAutonomyGoalExecutionPrepareResult.Rejected>(journal.prepare(plan()))
        assertIs<PersistentAutonomyGoalExecutionTransitionResult.Committed>(
            journal.transition(prepared.reference, AutonomyGoalExecutionState.PLANNED, AutonomyGoalExecutionState.AUTHORIZED)
        )
        assertIs<PersistentAutonomyGoalExecutionTransitionResult.Committed>(
            journal.transition(prepared.reference, AutonomyGoalExecutionState.AUTHORIZED, AutonomyGoalExecutionState.EXECUTING)
        )
        assertIs<PersistentAutonomyGoalExecutionTransitionResult.Committed>(
            journal.transition(prepared.reference, AutonomyGoalExecutionState.EXECUTING, AutonomyGoalExecutionState.SUCCEEDED)
        )
        assertIs<PersistentAutonomyGoalExecutionTransitionResult.Rejected>(
            journal.transition(prepared.reference, AutonomyGoalExecutionState.SUCCEEDED, AutonomyGoalExecutionState.EXECUTING)
        )
        assertEquals(AutonomyGoalExecutionState.SUCCEEDED, journal.inspect(prepared.plan.id)?.state)
    }

    @Test
    fun known_execution_failure_is_terminal_and_not_a_retry_signal() {
        val f = foundation()
        val journal = open(f, InMemoryPersistentRecordBackend())
        val prepared = assertIs<PersistentAutonomyGoalExecutionPrepareResult.Prepared>(journal.prepare(plan())).snapshot
        assertIs<PersistentAutonomyGoalExecutionTransitionResult.Committed>(
            journal.transition(prepared.reference, AutonomyGoalExecutionState.PLANNED, AutonomyGoalExecutionState.AUTHORIZED)
        )
        assertIs<PersistentAutonomyGoalExecutionTransitionResult.Committed>(
            journal.transition(prepared.reference, AutonomyGoalExecutionState.AUTHORIZED, AutonomyGoalExecutionState.EXECUTING)
        )
        assertIs<PersistentAutonomyGoalExecutionTransitionResult.Committed>(
            journal.transition(prepared.reference, AutonomyGoalExecutionState.EXECUTING, AutonomyGoalExecutionState.FAILED)
        )

        assertIs<PersistentAutonomyGoalExecutionTransitionResult.Rejected>(
            journal.transition(prepared.reference, AutonomyGoalExecutionState.FAILED, AutonomyGoalExecutionState.AUTHORIZED)
        )
    }
}
