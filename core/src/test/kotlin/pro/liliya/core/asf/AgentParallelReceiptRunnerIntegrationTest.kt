package pro.liliya.core.asf

import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.InMemoryPersistentRecordBackend
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult

class AgentParallelReceiptRunnerIntegrationTest {
    @Test
    fun completed_factory_wave_receipt_is_readable_after_store_reopen() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val now = Instant.parse("2026-10-12T00:00:00Z")
            val blueprint = AgentBlueprint.create(
                AgentBlueprintVersion(1), "researcher", "analysis",
                AgentCognitiveScope.create(listOf("research"))
            )
            val budget = AgentWorkBudget(30_000, 20_000, 128_000, 16, 4, 2)
            val factory = AgentFactory(
                AgentBlueprintRegistry(listOf(blueprint)),
                AgentAdmissionPolicy(
                    AgentFactoryBounds.PROTOTYPE, blueprint.cognitiveScope, budget
                ),
                AgentRuntimeAdapter { context ->
                    AgentRuntimeOutcome.Completed(
                        "research-report", "sha256:result",
                        context.workspace.inputReferences,
                        AgentRuntimeUsage(1, 1, 1, 0, 1)
                    )
                },
                AgentAuditLedger { }, timeSource = { now }
            )
            val logs = InMemoryLogWriter()
            val sequence = AtomicInteger()
            val foundation = FoundationComposition(
                diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
                loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
                correlationIds = CorrelationIdGenerator { "asf-receipt-${sequence.incrementAndGet()}" }
            )
            val backend = InMemoryPersistentRecordBackend()
            val id = PersistentStoreId("asf-receipt-runner")
            fun receiptStore(): AgentParallelArtifactReceiptStore =
                AgentParallelArtifactReceiptStore(
                    assertIs<PersistentStoreOpenResult.Opened>(
                        PersistentRecordStore.open(foundation, id, backend)
                    ).store
                )
            val receipts = receiptStore()
            val committed = AtomicReference<AgentArtifact?>()
            val step = AgentCoordinatorStepId("step-1")
            val request = AgentSpawnRequest.create(
                AgentBlueprintReference(blueprint.id, blueprint.version),
                AgentSpawnProvenance(AgentRootTaskId("receipt-root"), null, null, 0),
                blueprint.cognitiveScope, budget
            )
            val entry = AgentParallelFactoryWaveRunner.Entry(
                request, AgentInstanceGeneration(1), listOf("input:one")
            )
            val result = AgentParallelFactoryWaveRunner(
                factory, AgentParallelWaveExecutor(pool)
            ).runReservedCommitted(
                ledger = AgentParallelPopulationLedger(1, 1),
                wave = AgentParallelWave(
                    0, listOf(step),
                    AgentParallelWaveReservation(2_000, 20_000, 128_000, 16, 4, 1)
                ),
                entries = mapOf(step to entry),
                baselinePopulation = AgentPopulationSnapshot(0, 0, 0),
                admittedAt = now, expiresAt = now.plusSeconds(300), timeoutMillis = 2_000,
                commitAndVerify = { stepId, artifact ->
                    receipts.commitAndVerify(stepId, artifact).also { accepted ->
                        if (accepted) committed.set(artifact)
                    }
                }
            )
            assertEquals(AgentParallelWaveExecutionState.COMPLETED, result.state)
            assertTrue(receiptStore().commitAndVerify(step, assertNotNull(committed.get())))
        } finally {
            pool.shutdownNow()
        }
    }
}
