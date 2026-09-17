package pro.liliya.core.autonomy

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
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningProposalId
import pro.liliya.core.reasoning.ReasoningArtifactId
import pro.liliya.core.reasoning.ReasoningGeneration

class PersistentAutonomyExecutionCheckpointStoreContractTest {
    private val createdAt = Instant.parse("2026-09-17T17:00:00Z")
    private val completedAt = Instant.parse("2026-09-17T17:01:00Z")

    private fun foundation(): FoundationComposition {
        val logs = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "autonomy-checkpoint-${sequence.incrementAndGet()}" }
        )
    }

    private fun request(intent: String = "intent-1") = ControlledAutonomyExecutionRequest(
        deliberationRequestId = AutonomyDeliberationRequestId("deliberation-1"),
        deliberationGeneration = AutonomyDeliberationGeneration(3),
        planningProposalId = PlanningProposalId("planning-1"),
        planningGeneration = PlanningGeneration(4),
        reasoningArtifactId = ReasoningArtifactId("reasoning-1"),
        reasoningGeneration = ReasoningGeneration(5),
        decisionId = DecisionId("decision-1"),
        decisionGeneration = DecisionGeneration(6),
        orchestrationIntentId = OrchestrationIntentId(intent),
        orchestrationGeneration = OrchestrationGeneration(7),
        principal = AuthorityPrincipal("autonomy-runtime"),
        actionId = ExecutionActionId("device.safe-action")
    )

    private fun open(backend: InMemoryPersistentRecordBackend): PersistentAutonomyExecutionCheckpointStore =
        assertIs<PersistentAutonomyExecutionCheckpointOpenResult.Opened>(
            PersistentAutonomyExecutionCheckpointStore.open(foundation(), backend)
        ).store

    @Test
    fun pending_checkpoint_reopens_with_exact_structural_provenance_and_no_sensitive_content() {
        val backend = InMemoryPersistentRecordBackend()
        val store = open(backend)
        val expected = request()

        val written = assertIs<AutonomyExecutionCheckpointWriteResult.Written>(
            store.prepare(expected, createdAt)
        )
        assertEquals(PersistentGeneration(1), written.snapshot.generation)

        val reopened = open(backend)
        val restored = reopened.pending().single()

        assertEquals(expected, restored.checkpoint.request)
        assertEquals(AutonomyExecutionCheckpointState.PENDING, restored.checkpoint.state)
        assertEquals(createdAt, restored.checkpoint.createdAt)
        assertEquals(createdAt, restored.checkpoint.updatedAt)
        assertEquals(PersistentGeneration(1), restored.generation)

        val raw = backend.load(pro.liliya.core.persistence.PersistentStoreId("autonomy-execution-checkpoints"))
        val payload = assertIs<pro.liliya.core.persistence.PersistentBackendLoadResult.Loaded>(raw)
            .state.entries.values.single().record.payload.copyBytes().decodeToString()
        assertTrue("prompt" !in payload.lowercase())
        assertTrue("objective" !in payload.lowercase())
        assertTrue("description" !in payload.lowercase())
        assertTrue("authority-grant" !in payload.lowercase())
        assertTrue("execution-grant" !in payload.lowercase())
    }

    @Test
    fun completed_checkpoint_is_not_restored_as_pending_and_transition_is_restart_safe() {
        val backend = InMemoryPersistentRecordBackend()
        val store = open(backend)
        val prepared = assertIs<AutonomyExecutionCheckpointWriteResult.Written>(
            store.prepare(request(), createdAt)
        ).snapshot

        val completed = assertIs<AutonomyExecutionCheckpointWriteResult.Written>(
            store.markCompleted(
                prepared.checkpoint.request.orchestrationIntentId,
                prepared.generation,
                completedAt
            )
        ).snapshot

        assertEquals(AutonomyExecutionCheckpointState.COMPLETED, completed.checkpoint.state)
        assertEquals(completedAt, completed.checkpoint.updatedAt)
        assertTrue(open(backend).pending().isEmpty())
        assertEquals(
            AutonomyExecutionCheckpointState.COMPLETED,
            open(backend).inspect(OrchestrationIntentId("intent-1"))?.checkpoint?.state
        )
    }

    @Test
    fun stale_generation_and_duplicate_prepare_fail_closed() {
        val backend = InMemoryPersistentRecordBackend()
        val store = open(backend)
        val prepared = assertIs<AutonomyExecutionCheckpointWriteResult.Written>(
            store.prepare(request(), createdAt)
        ).snapshot

        assertIs<AutonomyExecutionCheckpointWriteResult.Rejected>(
            store.prepare(request(), createdAt.plusSeconds(1))
        )
        assertIs<AutonomyExecutionCheckpointWriteResult.Rejected>(
            store.cancel(
                prepared.checkpoint.request.orchestrationIntentId,
                PersistentGeneration(prepared.generation.value + 1),
                completedAt
            )
        )
        assertEquals(
            AutonomyExecutionCheckpointState.PENDING,
            store.inspect(prepared.checkpoint.request.orchestrationIntentId)?.checkpoint?.state
        )
    }

    @Test
    fun cancelled_checkpoint_never_reappears_in_pending_after_reopen() {
        val backend = InMemoryPersistentRecordBackend()
        val store = open(backend)
        val prepared = assertIs<AutonomyExecutionCheckpointWriteResult.Written>(
            store.prepare(request("intent-cancelled"), createdAt)
        ).snapshot

        assertIs<AutonomyExecutionCheckpointWriteResult.Written>(
            store.cancel(
                prepared.checkpoint.request.orchestrationIntentId,
                prepared.generation,
                completedAt
            )
        )

        val reopened = open(backend)
        assertTrue(reopened.pending().isEmpty())
        assertEquals(
            AutonomyExecutionCheckpointState.CANCELLED,
            reopened.inspect(OrchestrationIntentId("intent-cancelled"))?.checkpoint?.state
        )
    }
}
