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
    fun atomic_payload_receipt_survives_reopen_with_exact_content() {
        val bytes = "bounded durable artifact".toByteArray()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val a = artifact("sha256:$sha")
        assertTrue(store().commitPayloadAndReceipt(step, a, bytes))
        assertTrue(store().commitPayloadAndReceipt(step, a, bytes))
        assertFalse(store().commitPayloadAndReceipt(step, a, "forged".toByteArray()))
    }

    @Test
    fun atomic_payload_receipt_rejects_wrong_digest_and_overlimit_size() {
        assertFalse(store().commitPayloadAndReceipt(step, artifact("sha256:wrong"), "wrong".toByteArray()))
        val bytes = ByteArray(512 * 1024 + 1)
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertFalse(store().commitPayloadAndReceipt(step, artifact("sha256:$sha"), bytes))
    }

    @Test
    fun missing_payload_fails_closed_without_installing_receipt() {
        val a = artifact("sha256:missing")
        assertFalse(store().commitAndVerifyWithPayload(step, a) { false })
        // No receipt may have been installed by the failed verification.
        assertTrue(store().commitAndVerifyWithPayload(step, artifact("sha256:present")) { true })
    }

    @Test
    fun verifier_exception_fails_closed() {
        val a = artifact("sha256:missing")
        assertFalse(store().commitAndVerifyWithPayload(step, a) { error("backend unavailable") })
        assertTrue(store().commitAndVerifyWithPayload(step, artifact("sha256:present")) { true })
    }

    @Test
    fun payload_must_remain_verifiable_after_receipt_readback() {
        val a = artifact("sha256:present")
        val checks = AtomicInteger()
        assertFalse(store().commitAndVerifyWithPayload(step, a) {
            checks.incrementAndGet() == 1
        })
        kotlin.test.assertEquals(2, checks.get())
    }

    @Test
    fun failed_backend_commit_never_returns_a_receipt() {
        backend.failNextCommit()
        assertFalse(store().commitAndVerify(step, artifact("sha256:a")))
        assertTrue(store().commitAndVerify(step, artifact("sha256:a")))
    }
}
