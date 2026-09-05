package pro.liliya.android.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.knowledge.KnowledgeItem
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeOrigin
import pro.liliya.core.knowledge.KnowledgeSourceId
import pro.liliya.core.knowledge.KnowledgeSourceReference
import pro.liliya.core.knowledge.PersistentKnowledgeComposition
import pro.liliya.core.knowledge.PersistentKnowledgeCreateResult
import pro.liliya.core.knowledge.PersistentKnowledgeOpenResult
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.memory.MemorySourceReference
import pro.liliya.core.memory.PersistentMemoryComposition
import pro.liliya.core.memory.PersistentMemoryOpenResult
import pro.liliya.core.memory.PersistentMemoryRememberResult
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.PersistentStoreId

@RunWith(AndroidJUnit4::class)
class AndroidDurablePersistenceDomainReopenInstrumentedTest {

    @Test
    fun persistent_memory_and_knowledge_reopen_exactly_over_android_backend() = withCleanRoot {
        context ->
        val memoryBackend = AndroidDurablePersistentRecordBackend.create(context, TEST_DIRECTORY)
        val knowledgeBackend = AndroidDurablePersistentRecordBackend.create(context, TEST_DIRECTORY)

        val memoryStoreId = PersistentStoreId("memory-production")
        val knowledgeStoreId = PersistentStoreId("knowledge-production")

        val memoryRecord = MemoryRecord(
            id = MemoryRecordId("memory-keys"),
            provenance = MemoryProvenance(
                sourceId = MemorySourceId("conversation"),
                sourceReference = MemorySourceReference("turn-42")
            ),
            content = "The keys are on the kitchen table.",
            createdAt = Instant.parse("2026-09-05T15:10:00Z")
        )
        val knowledgeItem = KnowledgeItem(
            id = KnowledgeItemId("knowledge-bus"),
            origin = KnowledgeOrigin.Declared(
                sourceId = KnowledgeSourceId("user-note"),
                sourceReference = KnowledgeSourceReference("note-7")
            ),
            content = "Bus twelve goes to the railway station.",
            createdAt = Instant.parse("2026-09-05T15:11:00Z")
        )

        val firstMemory = openMemory(memoryBackend, memoryStoreId)
        val remembered = assertIs<PersistentMemoryRememberResult.Remembered>(
            firstMemory.remember(memoryRecord)
        )

        val firstKnowledge = openKnowledge(knowledgeBackend, knowledgeStoreId)
        val created = assertIs<PersistentKnowledgeCreateResult.Created>(
            firstKnowledge.create(knowledgeItem)
        )

        val reopenedMemory = openMemory(
            AndroidDurablePersistentRecordBackend.create(context, TEST_DIRECTORY),
            memoryStoreId
        )
        val reopenedKnowledge = openKnowledge(
            AndroidDurablePersistentRecordBackend.create(context, TEST_DIRECTORY),
            knowledgeStoreId
        )

        assertEquals(memoryRecord, reopenedMemory.find(memoryRecord.id))
        assertEquals(
            remembered.ownership.generation,
            reopenedMemory.inspect(memoryRecord.id)?.generation
        )
        assertEquals(knowledgeItem, reopenedKnowledge.find(knowledgeItem.id))
        assertEquals(
            created.ownership.generation,
            reopenedKnowledge.inspect(knowledgeItem.id)?.generation
        )
    }

    private fun openMemory(
        backend: AndroidDurablePersistentRecordBackend,
        storeId: PersistentStoreId
    ): PersistentMemoryComposition =
        assertIs<PersistentMemoryOpenResult.Opened>(
            PersistentMemoryComposition.open(
                foundation = foundation(),
                storeId = storeId,
                backend = backend
            )
        ).composition

    private fun openKnowledge(
        backend: AndroidDurablePersistentRecordBackend,
        storeId: PersistentStoreId
    ): PersistentKnowledgeComposition =
        assertIs<PersistentKnowledgeOpenResult.Opened>(
            PersistentKnowledgeComposition.open(
                foundation = foundation(),
                storeId = storeId,
                backend = backend
            )
        ).composition

    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger(0)
        val writer = InMemoryLogWriter()
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, writer) },
            correlationIds = CorrelationIdGenerator {
                "android-durable-domain-${sequence.incrementAndGet()}"
            }
        )
    }

    private inline fun withCleanRoot(block: (android.content.Context) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val root = File(context.filesDir, TEST_DIRECTORY)
        root.deleteRecursively()
        try {
            block(context)
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val TEST_DIRECTORY = "durable-persistence-domain-reopen-test"
    }
}
