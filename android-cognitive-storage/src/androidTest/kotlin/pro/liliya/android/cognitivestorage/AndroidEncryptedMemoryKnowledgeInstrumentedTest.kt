package pro.liliya.android.cognitivestorage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.encryption.CognitiveDekId
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitiveKeyProtectorCreationRequest
import pro.liliya.core.encryption.CognitiveKeyProtectorDescriptor
import pro.liliya.core.encryption.CognitiveKeyProtectorGeneration
import pro.liliya.core.encryption.CognitiveKeyProtectorId
import pro.liliya.core.encryption.CognitiveKeyProtectorSecurityLevel
import pro.liliya.core.encryption.PersistentCognitiveDekRegistrationResult
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.knowledge.KnowledgeItem
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeOrigin
import pro.liliya.core.knowledge.KnowledgeSourceId
import pro.liliya.core.knowledge.PersistentKnowledgeCreateResult
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.memory.PersistentMemoryRememberResult
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.PersistentBackendLoadResult
import pro.liliya.core.persistence.PersistentStoreId

@RunWith(AndroidJUnit4::class)
class AndroidEncryptedMemoryKnowledgeInstrumentedTest {

    @Test
    fun real_android_storage_reopens_encrypted_memory_and_knowledge_after_reconstruction() =
        withCleanRoot { context ->
            val first = assertIs<AndroidCognitiveStorageOpenResult.Ready>(
                AndroidCognitiveStorageAssembly.open(
                    context = context,
                    foundation = foundation(),
                    directoryName = TEST_DIRECTORY
                )
            ).assembly

            val descriptor = assertIs<CognitiveEncryptionResult.Success<CognitiveKeyProtectorDescriptor>>(
                first.keyProtector.create(
                    CognitiveKeyProtectorCreationRequest(
                        id = CognitiveKeyProtectorId("domain-protector-${System.nanoTime()}"),
                        generation = CognitiveKeyProtectorGeneration(1),
                        requestedSecurityLevel = CognitiveKeyProtectorSecurityLevel.SOFTWARE
                    )
                )
            ).value

            val registered = assertIs<PersistentCognitiveDekRegistrationResult.Registered>(
                first.dekStore.register(
                    CognitiveDekId("domain-dek"),
                    descriptor
                )
            )
            val dek = registered.ownership.reference

            val memoryStoreId = PersistentStoreId("encrypted-memory-production")
            val knowledgeStoreId = PersistentStoreId("encrypted-knowledge-production")

            val memory = assertIs<AndroidEncryptedMemoryOpenResult.Opened>(
                first.openEncryptedMemory(memoryStoreId, dek)
            ).composition
            val memoryRecord = MemoryRecord(
                id = MemoryRecordId("memory-keys"),
                provenance = MemoryProvenance(MemorySourceId("conversation")),
                content = "The keys are on the kitchen table.",
                createdAt = Instant.parse("2026-09-05T16:10:00Z")
            )
            val remembered = assertIs<PersistentMemoryRememberResult.Remembered>(
                memory.remember(memoryRecord)
            )

            val knowledge = assertIs<AndroidEncryptedKnowledgeOpenResult.Opened>(
                first.openEncryptedKnowledge(knowledgeStoreId, dek)
            ).composition
            val knowledgeItem = KnowledgeItem(
                id = KnowledgeItemId("knowledge-bus"),
                origin = KnowledgeOrigin.Declared(KnowledgeSourceId("user-note")),
                content = "Bus twelve goes to the railway station.",
                createdAt = Instant.parse("2026-09-05T16:11:00Z")
            )
            val created = assertIs<PersistentKnowledgeCreateResult.Created>(
                knowledge.create(knowledgeItem)
            )

            val memoryDurable = assertIs<PersistentBackendLoadResult.Loaded>(
                first.backend.load(memoryStoreId)
            )
            val knowledgeDurable = assertIs<PersistentBackendLoadResult.Loaded>(
                first.backend.load(knowledgeStoreId)
            )
            val memoryBytes = memoryDurable.state.entries.values.single()
                .record.payload.copyBytes()
            val knowledgeBytes = knowledgeDurable.state.entries.values.single()
                .record.payload.copyBytes()
            assertFalse(
                containsSubsequence(
                    memoryBytes,
                    memoryRecord.content.encodeToByteArray()
                )
            )
            assertFalse(
                containsSubsequence(
                    knowledgeBytes,
                    knowledgeItem.content.encodeToByteArray()
                )
            )

            val reconstructed = assertIs<AndroidCognitiveStorageOpenResult.Ready>(
                AndroidCognitiveStorageAssembly.open(
                    context = context,
                    foundation = foundation(),
                    directoryName = TEST_DIRECTORY
                )
            ).assembly

            val reopenedMemory = assertIs<AndroidEncryptedMemoryOpenResult.Opened>(
                reconstructed.openEncryptedMemory(memoryStoreId, dek)
            ).composition
            val reopenedKnowledge = assertIs<AndroidEncryptedKnowledgeOpenResult.Opened>(
                reconstructed.openEncryptedKnowledge(knowledgeStoreId, dek)
            ).composition

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

            assertIs<CognitiveEncryptionResult.Success<Unit>>(
                first.keyProtector.retire(descriptor)
            )
        }

    private fun foundation(): FoundationComposition {
        val writer = InMemoryLogWriter()
        val sequence = AtomicInteger()
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context ->
                StructuredLogger(context, writer)
            },
            correlationIds = CorrelationIdGenerator {
                "android-encrypted-domain-${sequence.incrementAndGet()}"
            }
        )
    }

    private inline fun withCleanRoot(
        block: (android.content.Context) -> Unit
    ) {
        val context = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext
        val root = File(context.filesDir, TEST_DIRECTORY)
        root.deleteRecursively()
        try {
            block(context)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun containsSubsequence(
        haystack: ByteArray,
        needle: ByteArray
    ): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        for (start in 0..haystack.size - needle.size) {
            var matches = true
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private companion object {
        const val TEST_DIRECTORY = "android-encrypted-memory-knowledge-test"
    }
}
