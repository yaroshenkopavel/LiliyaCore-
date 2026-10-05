package pro.liliya.core.autonomy

import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.decision.DecisionGeneration
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.execution.ExecutionActionId
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.orchestration.OrchestrationGeneration
import pro.liliya.core.orchestration.OrchestrationIntentId
import pro.liliya.core.persistence.InMemoryPersistentRecordBackend
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningGeneration

class DurableControlledAutonomyExecutionContractTest {
    private val createdAt = Instant.parse("2026-09-17T18:00:00Z")

    private fun foundation(): FoundationComposition {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "durable-autonomy-${sequence.incrementAndGet()}" }
        )
    }

    private fun request(intent: String = "intent-1") = ControlledAutonomyExecutionRequest(
        deliberationRequestId = AutonomyDeliberationRequestId("deliberation-1"),
        deliberationGeneration = AutonomyDeliberationGeneration(1),
        planningProposalId = PlanningProposalId("planning-1"),
        planningGeneration = PlanningGeneration(1),
        reasoningArtifactId = ReasoningArtifactId("reasoning-1"),
        reasoningGeneration = ReasoningGeneration(1),
        decisionId = DecisionId("decision-1"),
        decisionGeneration = DecisionGeneration(1),
        orchestrationIntentId = OrchestrationIntentId(intent),
        orchestrationGeneration = OrchestrationGeneration(1),
        principal = AuthorityPrincipal("autonomy-runtime"),
        actionId = ExecutionActionId("device.safe-action")
    )

    private fun store(backend: InMemoryPersistentRecordBackend) =
        assertIs<PersistentAutonomyExecutionCheckpointOpenResult.Opened>(
            PersistentAutonomyExecutionCheckpointStore.open(foundation(), backend)
        ).store

    @Test
    fun successful_execution_is_claimed_once_and_duplicate_attempt_never_reaches_runner() {
        val backend = InMemoryPersistentRecordBackend()
        val checkpoints = store(backend)
        val pending = assertIs<AutonomyExecutionCheckpointWriteResult.Written>(
            checkpoints.prepare(request(), createdAt)
        ).snapshot
        var calls = 0
        val durable = DurableControlledAutonomyExecution(
            checkpoints,
            AutonomyExecutionRunner {
                calls += 1
                ControlledAutonomyExecutionResult.Succeeded
            },
            Duration.ofMinutes(5)
        )

        assertIs<DurableControlledAutonomyExecutionResult.Succeeded>(
            durable.execute(pending, createdAt.plusSeconds(1))
        )
        assertEquals(1, calls)
        assertEquals(
            AutonomyExecutionCheckpointState.COMPLETED,
            checkpoints.inspect(pending.checkpoint.request.orchestrationIntentId)?.checkpoint?.state
        )

        assertIs<DurableControlledAutonomyExecutionResult.Rejected>(
            durable.execute(pending, createdAt.plusSeconds(2))
        )
        assertEquals(1, calls)
    }

    @Test
    fun executing_checkpoint_after_restart_is_never_replayed_and_requires_explicit_recovery() {
        val backend = InMemoryPersistentRecordBackend()
        val first = store(backend)
        val pending = assertIs<AutonomyExecutionCheckpointWriteResult.Written>(
            first.prepare(request("intent-interrupted"), createdAt)
        ).snapshot
        assertIs<AutonomyExecutionCheckpointWriteResult.Written>(
            first.markExecuting(
                pending.checkpoint.request.orchestrationIntentId,
                pending.generation,
                createdAt.plusSeconds(1)
            )
        )

        val reopened = store(backend)
        assertTrue(reopened.pending().isEmpty())
        assertEquals(1, reopened.recoveryRequired().size)
        var calls = 0
        val durable = DurableControlledAutonomyExecution(
            reopened,
            AutonomyExecutionRunner {
                calls += 1
                ControlledAutonomyExecutionResult.Succeeded
            },
            Duration.ofMinutes(5)
        )

        assertIs<DurableControlledAutonomyExecutionResult.Rejected>(
            durable.execute(pending, createdAt.plusSeconds(2))
        )
        assertEquals(0, calls)
    }

    @Test
    fun expired_pending_checkpoint_is_cancelled_before_runner() {
        val backend = InMemoryPersistentRecordBackend()
        val checkpoints = store(backend)
        val pending = assertIs<AutonomyExecutionCheckpointWriteResult.Written>(
            checkpoints.prepare(request("intent-expired"), createdAt)
        ).snapshot
        var calls = 0
        val durable = DurableControlledAutonomyExecution(
            checkpoints,
            AutonomyExecutionRunner {
                calls += 1
                ControlledAutonomyExecutionResult.Succeeded
            },
            Duration.ofSeconds(30)
        )

        assertIs<DurableControlledAutonomyExecutionResult.Rejected>(
            durable.execute(pending, createdAt.plusSeconds(31))
        )
        assertEquals(0, calls)
        assertEquals(
            AutonomyExecutionCheckpointState.CANCELLED,
            checkpoints.inspect(pending.checkpoint.request.orchestrationIntentId)?.checkpoint?.state
        )
    }

    @Test
    fun known_rejection_is_terminal_and_not_retried() {
        val backend = InMemoryPersistentRecordBackend()
        val checkpoints = store(backend)
        val pending = assertIs<AutonomyExecutionCheckpointWriteResult.Written>(
            checkpoints.prepare(request("intent-rejected"), createdAt)
        ).snapshot
        var calls = 0
        val durable = DurableControlledAutonomyExecution(
            checkpoints,
            AutonomyExecutionRunner {
                calls += 1
                ControlledAutonomyExecutionResult.Rejected("fresh Authority denied")
            },
            Duration.ofMinutes(5)
        )

        assertIs<DurableControlledAutonomyExecutionResult.Rejected>(
            durable.execute(pending, createdAt.plusSeconds(1))
        )
        assertEquals(1, calls)
        assertEquals(
            AutonomyExecutionCheckpointState.REJECTED,
            checkpoints.inspect(pending.checkpoint.request.orchestrationIntentId)?.checkpoint?.state
        )
        assertIs<DurableControlledAutonomyExecutionResult.Rejected>(
            durable.execute(pending, createdAt.plusSeconds(2))
        )
        assertEquals(1, calls)
    }

    @Test
    fun ambiguous_failure_is_recovery_required_and_never_automatically_retried() {
        val backend = InMemoryPersistentRecordBackend()
        val checkpoints = store(backend)
        val pending = assertIs<AutonomyExecutionCheckpointWriteResult.Written>(
            checkpoints.prepare(request("intent-failed"), createdAt)
        ).snapshot
        var calls = 0
        val durable = DurableControlledAutonomyExecution(
            checkpoints,
            AutonomyExecutionRunner {
                calls += 1
                ControlledAutonomyExecutionResult.Failed("executor result unknown")
            },
            Duration.ofMinutes(5)
        )

        assertIs<DurableControlledAutonomyExecutionResult.RecoveryRequired>(
            durable.execute(pending, createdAt.plusSeconds(1))
        )
        assertEquals(1, calls)
        assertEquals(
            AutonomyExecutionCheckpointState.RECOVERY_REQUIRED,
            checkpoints.inspect(pending.checkpoint.request.orchestrationIntentId)?.checkpoint?.state
        )
        assertTrue(store(backend).pending().isEmpty())
        assertEquals(1, store(backend).recoveryRequired().size)
    }
}
