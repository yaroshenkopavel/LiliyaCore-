package pro.liliya.android.cognitivestorage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.core.authority.AuthorityPrincipal
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
import pro.liliya.core.learning.LearningApplicationGeneration
import pro.liliya.core.learning.LearningApplicationId
import pro.liliya.core.learning.LearningApplicationIdempotencyKey
import pro.liliya.core.learning.LearningApplicationIntentReference
import pro.liliya.core.learning.LearningApplicationMutationId
import pro.liliya.core.learning.LearningApplicationMutationPayload
import pro.liliya.core.learning.LearningApplicationMutationPlan
import pro.liliya.core.learning.LearningApplicationMutationPreparationResult
import pro.liliya.core.learning.LearningApplicationTarget
import pro.liliya.core.learning.preparationPort
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.PersistentBackendLoadResult
import pro.liliya.core.persistence.PersistentStoreId

@RunWith(AndroidJUnit4::class)
class AndroidEncryptedLearningMutationInstrumentedTest {

    @Test
    fun prepared_learning_payload_is_encrypted_at_rest_and_reopens_exact_generation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = java.io.File(context.filesDir, TEST_DIRECTORY)
        root.deleteRecursively()

        val foundation = foundation()
        val first = assertIs<AndroidCognitiveStorageOpenResult.Ready>(
            AndroidCognitiveStorageAssembly.open(
                context = context,
                foundation = foundation,
                directoryName = TEST_DIRECTORY
            )
        ).assembly

        val descriptor = assertIs<CognitiveEncryptionResult.Success<CognitiveKeyProtectorDescriptor>>(
            first.keyProtector.create(
                CognitiveKeyProtectorCreationRequest(
                    id = CognitiveKeyProtectorId("learning-mutation-protector-" + System.nanoTime()),
                    generation = CognitiveKeyProtectorGeneration(1),
                    requestedSecurityLevel = CognitiveKeyProtectorSecurityLevel.SOFTWARE
                )
            )
        ).value
        val dek = assertIs<PersistentCognitiveDekRegistrationResult.Registered>(
            first.dekStore.register(CognitiveDekId("learning-mutation-dek"), descriptor)
        ).ownership.reference

        val storeId = PersistentStoreId("encrypted-learning-mutations")
        val mutations = assertIs<AndroidEncryptedLearningMutationOpenResult.Opened>(
            first.openEncryptedLearningMutations(storeId, dek)
        ).composition

        val privatePayload = "private learned payload must remain encrypted"
        val prepared = assertIs<LearningApplicationMutationPreparationResult.Prepared>(
            mutations.preparationPort().prepare(
                LearningApplicationMutationPlan(
                    id = LearningApplicationMutationId("mutation-private"),
                    application = LearningApplicationIntentReference(
                        LearningApplicationId("application-private"),
                        LearningApplicationGeneration(1)
                    ),
                    principal = AuthorityPrincipal("learning-controller"),
                    target = LearningApplicationTarget.MEMORY,
                    idempotencyKey = LearningApplicationIdempotencyKey("idem-private"),
                    payload = LearningApplicationMutationPayload.Memory(
                        MemoryRecord(
                            id = MemoryRecordId("memory-private"),
                            provenance = MemoryProvenance(
                                MemorySourceId("learning-application")
                            ),
                            content = privatePayload,
                            createdAt = Instant.parse("2026-09-07T01:00:00Z")
                        )
                    ),
                    createdAt = Instant.parse("2026-09-07T01:01:00Z")
                )
            )
        ).ownership

        val raw = assertIs<PersistentBackendLoadResult.Loaded>(
            first.backend.load(storeId)
        )
        val rawPayload = raw.state.entries.values.single().record.payload.copyBytes()
        assertFalse(containsSubsequence(rawPayload, privatePayload.encodeToByteArray()))

        val reconstructed = assertIs<AndroidCognitiveStorageOpenResult.Ready>(
            AndroidCognitiveStorageAssembly.open(
                context = context,
                foundation = foundation(),
                directoryName = TEST_DIRECTORY
            )
        ).assembly
        val reopened = assertIs<AndroidEncryptedLearningMutationOpenResult.Opened>(
            reconstructed.openEncryptedLearningMutations(storeId, dek)
        ).composition

        val restored = assertNotNull(reopened.inspect(prepared.plan.id))
        assertEquals(prepared.generation, restored.generation)
        val restoredMemory = assertIs<LearningApplicationMutationPayload.Memory>(
            restored.plan.payload
        )
        assertEquals(privatePayload, restoredMemory.record.content)

        assertIs<CognitiveEncryptionResult.Success<Unit>>(
            first.keyProtector.retire(descriptor)
        )
    }

    private fun containsSubsequence(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (start in 0..(haystack.size - needle.size)) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }

    private fun foundation(): FoundationComposition {
        val writer = InMemoryLogWriter()
        val sequence = AtomicInteger()
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, writer) },
            correlationIds = CorrelationIdGenerator {
                "encrypted-learning-mutation-" + sequence.incrementAndGet()
            }
        )
    }

    private companion object {
        const val TEST_DIRECTORY = "android-encrypted-learning-mutation-test"
    }
}
