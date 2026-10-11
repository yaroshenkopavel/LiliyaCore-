package pro.liliya.core.asf

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

class AgentParallelArtifactReceiptStoreTest {
    private val step = AgentCoordinatorStepId("step-1")
    private val root = AgentRootTaskId("root-1")
    private val backend = InMemoryPersistentRecordBackend()
    private val storeId = PersistentStoreId("asf-receipt-test")
    private val sequence = AtomicInteger()
    private val logs = InMemoryLogWriter()
    private val foundation = FoundationComposition(
        diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
        loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
        correlationIds = CorrelationIdGenerator { "asf-receipt-${sequence.incrementAndGet()}" }
    )

    private fun store() = AgentParallelArtifactReceiptStore(
        assertIs<PersistentStoreOpenResult.Opened>(
            PersistentRecordStore.open(foundation, storeId, backend)
        ).store
    )

    private fun artifact(digest: String) = AgentArtifact.create(
        producerId = AgentInstanceId("worker-1"),
        producerGeneration = AgentInstanceGeneration(1),
        rootTaskId = root,
        kind = "analysis",
        payloadDigest = digest,
        provenanceReferences = listOf("input:one"),
        createdAt = Instant.parse("2026-10-12T00:00:00Z")
    )

    @Test
    fun commit_readback_and_reopen_are_idempotent() {
        val a = artifact("sha256:a")
        assertTrue(store().commitAndVerify(step, a))
        assertTrue(store().commitAndVerify(step, a))
    }

    @Test
    fun same_root_and_step_cannot_be_rebound_to_a_different_artifact() {
        assertTrue(store().commitAndVerify(step, artifact("sha256:a")))
        assertFalse(store().commitAndVerify(step, artifact("sha256:forged")))
    }

    @Test
    fun failed_backend_commit_never_returns_a_receipt() {
        backend.failNextCommit()
        assertFalse(store().commitAndVerify(step, artifact("sha256:a")))
        assertTrue(store().commitAndVerify(step, artifact("sha256:a")))
    }
}
