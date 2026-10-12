package pro.liliya.android.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.asf.AgentArtifact
import pro.liliya.core.asf.AgentCoordinatorStepId
import pro.liliya.core.asf.AgentInstanceGeneration
import pro.liliya.core.asf.AgentInstanceId
import pro.liliya.core.asf.AgentParallelArtifactReceiptStore
import pro.liliya.core.asf.AgentRootTaskId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult

@RunWith(AndroidJUnit4::class)
class AsfAtomicArtifactDurabilityInstrumentedTest {
    @Test
    fun artifact_payload_and_receipt_survive_independent_android_backend_reopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val directory = "asf-atomic-instrumented-test"
        val root = File(context.filesDir, directory)
        root.deleteRecursively()
        try {
            val sequence = AtomicInteger()
            val logs = InMemoryLogWriter()
            val foundation = FoundationComposition(
                diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
                loggerProvider = LoggerProvider { ctx -> StructuredLogger(ctx, logs) },
                correlationIds = CorrelationIdGenerator { "asf-android-${sequence.incrementAndGet()}" }
            )
            fun open(): AgentParallelArtifactReceiptStore {
                val backend = AndroidDurablePersistentRecordBackend.create(context, directory)
                val opened = assertIs<PersistentStoreOpenResult.Opened>(
                    PersistentRecordStore.open(foundation, PersistentStoreId("asf-atomic"), backend)
                )
                return AgentParallelArtifactReceiptStore(opened.store)
            }
            val payload = "actual-artifact-payload".toByteArray()
            val digest = MessageDigest.getInstance("SHA-256").digest(payload)
                .joinToString("") { "%02x".format(it) }
            val artifact = AgentArtifact.create(
                producerId = AgentInstanceId("asf-device-worker"),
                producerGeneration = AgentInstanceGeneration(1),
                rootTaskId = AgentRootTaskId("asf-device-root"),
                kind = "report",
                payloadDigest = "sha256:$digest",
                provenanceReferences = listOf("evidence:android"),
                createdAt = Instant.parse("2026-10-12T00:00:00Z")
            )
            val step = AgentCoordinatorStepId("device-step")
            assertTrue(open().commitPayloadAndReceipt(step, artifact, payload))
            // Fresh backend and store instances must recover exact payload+receipt.
            assertTrue(open().commitPayloadAndReceipt(step, artifact, payload))
            assertFalse(open().commitPayloadAndReceipt(step, artifact, "forged".toByteArray()))
            val published = root.listFiles()?.singleOrNull { it.extension == "lpr" }
            assertTrue(published != null && published.isFile)
            val file = published!!
            val bytes = file.readBytes()
            file.writeBytes(bytes.copyOf(bytes.size - 5))
            // A damaged published snapshot must never be treated as a valid receipt.
            assertFalse(openOrCorrupt(foundation, context, directory, step, artifact, payload))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun openOrCorrupt(
        foundation: FoundationComposition,
        context: android.content.Context,
        directory: String,
        step: AgentCoordinatorStepId,
        artifact: AgentArtifact,
        payload: ByteArray
    ): Boolean {
        val backend = AndroidDurablePersistentRecordBackend.create(context, directory)
        val opened = PersistentRecordStore.open(foundation, PersistentStoreId("asf-atomic"), backend)
        return opened is PersistentStoreOpenResult.Opened &&
            AgentParallelArtifactReceiptStore(opened.store).commitPayloadAndReceipt(step, artifact, payload)
    }
}
