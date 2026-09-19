package pro.liliya.android.cognitivestorage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.core.cognitive.CognitiveConversationContextMessage
import pro.liliya.core.cognitive.CognitiveConversationRole
import pro.liliya.core.cognitive.CognitiveConversationSequence
import pro.liliya.core.cognitive.CognitiveConversationSessionId
import pro.liliya.core.cognitive.PersistentConversationAppendPairResult
import pro.liliya.core.cognitive.PersistentConversationHistoryResult
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.encryption.CognitiveCiphertextDependency
import pro.liliya.core.encryption.CognitiveCiphertextDependencyRegistry
import pro.liliya.core.encryption.CognitiveDekId
import pro.liliya.core.encryption.CognitiveEncryptionFailureCategory
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitiveKeyProtectorCreationRequest
import pro.liliya.core.encryption.CognitiveKeyProtectorDescriptor
import pro.liliya.core.encryption.CognitiveKeyProtectorGeneration
import pro.liliya.core.encryption.CognitiveKeyProtectorId
import pro.liliya.core.encryption.CognitiveKeyProtectorSecurityLevel
import pro.liliya.core.encryption.CognitivePersistentRecordDraft
import pro.liliya.core.encryption.CognitivePlaintext
import pro.liliya.core.encryption.PersistentCognitiveDekMutationResult
import pro.liliya.core.encryption.PersistentCognitiveDekRegistrationResult
import pro.liliya.core.encryption.PersistentCognitiveDekStore
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.PersistentBackendLoadResult
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentMutationResult
import pro.liliya.core.persistence.PersistentRecordOwnership
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion
import pro.liliya.core.persistence.PersistentStoreId

@RunWith(AndroidJUnit4::class)
class AndroidCognitiveStorageAssemblyInstrumentedTest {

    @Test
    fun real_keystore_wrapped_dek_and_encrypted_record_survive_reconstruction() =
        withCleanRoot { context, root ->
            val first = assertIs<AndroidCognitiveStorageOpenResult.Ready>(
                AndroidCognitiveStorageAssembly.open(
                    context = context,
                    foundation = foundation(),
                    directoryName = TEST_DIRECTORY
                )
            ).assembly

            val protectorId = CognitiveKeyProtectorId(
                "storage-test-${System.nanoTime()}"
            )
            val descriptor = assertIs<CognitiveEncryptionResult.Success<CognitiveKeyProtectorDescriptor>>(
                first.keyProtector.create(
                    CognitiveKeyProtectorCreationRequest(
                        id = protectorId,
                        generation = CognitiveKeyProtectorGeneration(1),
                        requestedSecurityLevel = CognitiveKeyProtectorSecurityLevel.SOFTWARE
                    )
                )
            ).value

            val registered = assertIs<PersistentCognitiveDekRegistrationResult.Registered>(
                first.dekStore.register(CognitiveDekId("memory-main"), descriptor)
            )
            val dekReference = registered.ownership.reference

            val encryptedStoreId = PersistentStoreId("encrypted-memory-main")
            val encrypted = assertIs<AndroidEncryptedRecordStoreOpenResult.Opened>(
                first.openEncryptedRecordStore(encryptedStoreId)
            ).store
            val entityId = PersistentEntityId("memory-1")
            val plaintextBytes = "private durable encrypted memory".encodeToByteArray()

            val installed = assertIs<CognitiveEncryptionResult.Success<PersistentRecordOwnership>>(
                encrypted.install(
                    CognitivePersistentRecordDraft(
                        id = entityId,
                        schemaId = PersistentSchemaId("memory-record"),
                        schemaVersion = PersistentSchemaVersion(1),
                        plaintext = CognitivePlaintext(plaintextBytes),
                        createdAt = Instant.parse("2026-09-05T15:45:00Z"),
                        dek = dekReference
                    )
                )
            ).value

            val loadedPayload = assertIs<PersistentBackendLoadResult.Loaded>(
                first.backend.load(encryptedStoreId)
            )
            val durablePayload = loadedPayload.state.entries.getValue(entityId)
                .record.payload.copyBytes()
            assertFalse(containsSubsequence(durablePayload, plaintextBytes))

            val wrappedState = assertIs<PersistentBackendLoadResult.Loaded>(
                first.backend.load(PersistentCognitiveDekStore.STORE_ID)
            )
            val wrappedPayload = wrappedState.state.entries.values.single()
                .record.payload.copyBytes()
            assertFalse(containsSubsequence(wrappedPayload, plaintextBytes))

            val reconstructed = assertIs<AndroidCognitiveStorageOpenResult.Ready>(
                AndroidCognitiveStorageAssembly.open(
                    context = context,
                    foundation = foundation(),
                    directoryName = TEST_DIRECTORY
                )
            ).assembly
            assertNotNull(reconstructed.dekStore.inspect(dekReference))
            assertIs<CognitiveEncryptionResult.Success<*>>(
                reconstructed.dekStore.resolve(dekReference)
            )

            val reopened = assertIs<AndroidEncryptedRecordStoreOpenResult.Opened>(
                reconstructed.openEncryptedRecordStore(encryptedStoreId)
            ).store
            val opened = assertIs<CognitiveEncryptionResult.Success<CognitivePlaintext>>(
                reopened.open(entityId)
            )
            assertContentEquals(plaintextBytes, opened.value.copyBytes())

            val dependencies = CognitiveCiphertextDependencyRegistry()
            val dependency = CognitiveCiphertextDependency(
                storeId = encryptedStoreId,
                entityId = entityId,
                entityGeneration = installed.generation,
                dek = dekReference
            )
            assertEquals(
                pro.liliya.core.encryption.CognitiveDependencyUpdateResult.Updated,
                dependencies.registerCommitted(dependency)
            )

            assertIs<PersistentMutationResult.Committed>(installed.remove())
            assertEquals(
                pro.liliya.core.encryption.CognitiveDependencyUpdateResult.Updated,
                dependencies.releaseCommitted(dependency)
            )
            assertIs<PersistentCognitiveDekMutationResult.Retired>(
                registered.ownership.retireIfUnused(dependencies)
            )
            assertIs<CognitiveEncryptionResult.Success<Unit>>(
                first.keyProtector.retire(descriptor)
            )

            root.deleteRecursively()
        }

    @Test
    fun native_v3_conversation_survives_real_indexed_android_reconstruction() =
        withCleanRoot { context, _ ->
            val first = assertIs<AndroidCognitiveStorageOpenResult.Ready>(
                AndroidCognitiveStorageAssembly.open(
                    context = context,
                    foundation = foundation(),
                    directoryName = TEST_DIRECTORY
                )
            ).assembly

            val protectorId = CognitiveKeyProtectorId(
                "conversation-v3-" + System.nanoTime()
            )
            val descriptor =
                assertIs<CognitiveEncryptionResult.Success<CognitiveKeyProtectorDescriptor>>(
                    first.keyProtector.create(
                        CognitiveKeyProtectorCreationRequest(
                            id = protectorId,
                            generation = CognitiveKeyProtectorGeneration(1),
                            requestedSecurityLevel = CognitiveKeyProtectorSecurityLevel.SOFTWARE
                        )
                    )
                ).value
            val registered = assertIs<PersistentCognitiveDekRegistrationResult.Registered>(
                first.dekStore.register(
                    CognitiveDekId("conversation-v3-dek"),
                    descriptor
                )
            )
            val dekReference = registered.ownership.reference
            val conversationStoreId = PersistentStoreId("conversation-v3-real-indexed")
            val firstConversation = assertIs<AndroidEncryptedConversationOpenResult.Opened>(
                first.openEncryptedConversation(
                    storeId = conversationStoreId,
                    activeDek = dekReference,
                    maxRetainedMessages = 4,
                    maxMessageChars = 8 * 1024
                )
            ).store

            val sessionA = CognitiveConversationSessionId("private-session-A")
            val sessionB = CognitiveConversationSessionId("private-session-B")
            repeat(3) { turn ->
                val sequence = turn.toLong() * 2L + 1L
                assertIs<PersistentConversationAppendPairResult.Appended>(
                    firstConversation.appendPair(
                        sessionId = sessionA,
                        user = CognitiveConversationContextMessage(
                            CognitiveConversationSequence(sequence),
                            CognitiveConversationRole.USER,
                            "secret-a-user-" + turn
                        ),
                        assistant = CognitiveConversationContextMessage(
                            CognitiveConversationSequence(sequence + 1L),
                            CognitiveConversationRole.ASSISTANT,
                            "secret-a-reply-" + turn
                        ),
                        persistedAt = Instant.parse("2026-09-19T00:00:0" + (turn + 1) + "Z")
                    )
                )
            }
            assertIs<PersistentConversationAppendPairResult.Appended>(
                firstConversation.appendPair(
                    sessionId = sessionB,
                    user = CognitiveConversationContextMessage(
                        CognitiveConversationSequence(1),
                        CognitiveConversationRole.USER,
                        "secret-b-user"
                    ),
                    assistant = CognitiveConversationContextMessage(
                        CognitiveConversationSequence(2),
                        CognitiveConversationRole.ASSISTANT,
                        "secret-b-reply"
                    ),
                    persistedAt = Instant.parse("2026-09-19T00:00:10Z")
                )
            )

            val durable = assertIs<PersistentBackendLoadResult.Loaded>(
                first.backend.load(conversationStoreId)
            )
            val sensitive = listOf(
                sessionA.value,
                sessionB.value,
                "secret-a-user-0",
                "secret-a-reply-2",
                "secret-b-user",
                "secret-b-reply"
            ).map { it.encodeToByteArray() }
            for (entry in durable.state.entries.values) {
                val payload = entry.record.payload.copyBytes()
                for (needle in sensitive) {
                    assertFalse(containsSubsequence(payload, needle))
                }
            }

            val reconstructed = assertIs<AndroidCognitiveStorageOpenResult.Ready>(
                AndroidCognitiveStorageAssembly.open(
                    context = context,
                    foundation = foundation(),
                    directoryName = TEST_DIRECTORY
                )
            ).assembly
            assertNotNull(reconstructed.dekStore.inspect(dekReference))

            val reopenedConversation =
                assertIs<AndroidEncryptedConversationOpenResult.Opened>(
                    reconstructed.openEncryptedConversation(
                        storeId = conversationStoreId,
                        activeDek = dekReference,
                        maxRetainedMessages = 4,
                        maxMessageChars = 8 * 1024
                    )
                ).store

            val reopenedA = assertNotNull(reopenedConversation.reopen(sessionA))
            assertEquals(
                listOf(3L, 4L, 5L, 6L),
                reopenedA.messages.map { it.sequence.value }
            )
            assertEquals(
                listOf(
                    "secret-a-user-1",
                    "secret-a-reply-1",
                    "secret-a-user-2",
                    "secret-a-reply-2"
                ),
                reopenedA.messages.map { it.content }
            )

            val fullHistory = assertIs<PersistentConversationHistoryResult.Found>(
                reopenedConversation.history(
                    sessionId = sessionA,
                    maxMessages = 10
                )
            ).snapshot
            assertEquals(
                (1L..6L).toList(),
                fullHistory.messages.map { it.sequence.value }
            )
            assertEquals(
                listOf(
                    "secret-a-user-0",
                    "secret-a-reply-0",
                    "secret-a-user-1",
                    "secret-a-reply-1",
                    "secret-a-user-2",
                    "secret-a-reply-2"
                ),
                fullHistory.messages.map { it.content }
            )

            val reopenedB = assertNotNull(reopenedConversation.reopen(sessionB))
            assertEquals(
                listOf("secret-b-user", "secret-b-reply"),
                reopenedB.messages.map { it.content }
            )

            assertIs<CognitiveEncryptionResult.Success<Unit>>(
                reconstructed.keyProtector.retire(descriptor)
            )
        }

    @Test
    fun missing_exact_protector_fails_closed_after_reconstruction() =
        withCleanRoot { context, _ ->
            val first = assertIs<AndroidCognitiveStorageOpenResult.Ready>(
                AndroidCognitiveStorageAssembly.open(
                    context = context,
                    foundation = foundation(),
                    directoryName = TEST_DIRECTORY
                )
            ).assembly
            val protectorId = CognitiveKeyProtectorId(
                "missing-protector-${System.nanoTime()}"
            )
            val descriptor = assertIs<CognitiveEncryptionResult.Success<CognitiveKeyProtectorDescriptor>>(
                first.keyProtector.create(
                    CognitiveKeyProtectorCreationRequest(
                        id = protectorId,
                        generation = CognitiveKeyProtectorGeneration(1),
                        requestedSecurityLevel = CognitiveKeyProtectorSecurityLevel.SOFTWARE
                    )
                )
            ).value

            val registered = assertIs<PersistentCognitiveDekRegistrationResult.Registered>(
                first.dekStore.register(CognitiveDekId("missing-protector-dek"), descriptor)
            )

            assertIs<CognitiveEncryptionResult.Success<Unit>>(
                first.keyProtector.retire(descriptor)
            )

            val reconstructed = assertIs<AndroidCognitiveStorageOpenResult.Ready>(
                AndroidCognitiveStorageAssembly.open(
                    context = context,
                    foundation = foundation(),
                    directoryName = TEST_DIRECTORY
                )
            ).assembly
            val rejected = assertIs<CognitiveEncryptionResult.Rejected>(
                reconstructed.dekStore.resolve(registered.ownership.reference)
            )
            assertEquals(
                CognitiveEncryptionFailureCategory.PROTECTOR_MISSING,
                rejected.category
            )
        }

    private fun foundation(): FoundationComposition {
        val writer = InMemoryLogWriter()
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { logContext ->
                StructuredLogger(logContext, writer)
            },
            correlationIds = CorrelationIdGenerator {
                "android-cognitive-storage-${sequence.incrementAndGet()}"
            }
        )
    }

    private inline fun withCleanRoot(
        block: (android.content.Context, File) -> Unit
    ) {
        val context = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext
        val root = File(context.filesDir, TEST_DIRECTORY)
        root.deleteRecursively()
        try {
            block(context, root)
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
        const val TEST_DIRECTORY = "android-cognitive-storage-instrumented-test"
    }
}
