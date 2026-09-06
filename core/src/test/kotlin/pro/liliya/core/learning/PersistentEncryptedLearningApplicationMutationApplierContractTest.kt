package pro.liliya.core.learning

import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.authority.CapabilityAuthorityComposition
import pro.liliya.core.authority.CapabilityOwnershipResult
import pro.liliya.core.authority.DirectAuthorityGrant
import pro.liliya.core.authority.DirectAuthorityGrantOwnershipResult
import pro.liliya.core.capability.CapabilityDescriptor
import pro.liliya.core.capability.CapabilityProviderId
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.encryption.CognitiveAeadProvider
import pro.liliya.core.encryption.CognitiveAeadSealedData
import pro.liliya.core.encryption.CognitiveAssociatedData
import pro.liliya.core.encryption.CognitiveDekId
import pro.liliya.core.encryption.CognitiveDekGeneration
import pro.liliya.core.encryption.CognitiveDekMaterial
import pro.liliya.core.encryption.CognitiveDekMaterialResolver
import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.encryption.CognitiveEncryptionFailureCategory
import pro.liliya.core.encryption.CognitiveEncryptionProfile
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitiveEnvelopeVersion
import pro.liliya.core.encryption.CognitiveNonce
import pro.liliya.core.encryption.CognitiveNonceSource
import pro.liliya.core.encryption.CognitivePlaintext
import pro.liliya.core.encryption.EncryptedPersistentRecordStore
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.knowledge.EncryptedPersistentKnowledgeComposition
import pro.liliya.core.knowledge.EncryptedPersistentKnowledgeOpenResult
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.memory.EncryptedPersistentMemoryComposition
import pro.liliya.core.memory.EncryptedPersistentMemoryOpenResult
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.persistence.InMemoryPersistentRecordBackend
import pro.liliya.core.persistence.PersistentRecordStore
import pro.liliya.core.persistence.PersistentStoreId
import pro.liliya.core.persistence.PersistentStoreOpenResult

class PersistentEncryptedLearningApplicationMutationApplierContractTest {

    private val principal = AuthorityPrincipal("persistent-learning-controller")
    private val dekRef = CognitiveDekReference(
        CognitiveDekId("persistent-learning-dek"),
        CognitiveDekGeneration(1)
    )
    private val material = CognitiveDekMaterial(ByteArray(32) { (it * 7 + 3).toByte() })

    @Test
    fun authorized_memory_learning_commits_encrypted_state_and_completed_receipt_survives_reopen() {
        val backend = InMemoryPersistentRecordBackend()
        val foundation = foundation()
        val memoryStoreId = PersistentStoreId("h4b-encrypted-memory")
        val knowledgeStoreId = PersistentStoreId("h4b-encrypted-knowledge")
        val mutationStoreId = PersistentStoreId("h4b-learning-mutations")

        val memory = openMemory(foundation, backend, memoryStoreId)
        val knowledge = openKnowledge(foundation, backend, knowledgeStoreId)
        val mutations = openMutations(foundation, backend, mutationStoreId)

        val candidates = LearningComposition(foundation)
        val decisions = LearningDecisionComposition(foundation)
        val policies = LearningPolicyComposition(foundation)
        val applications = LearningApplicationComposition(foundation)
        val authority = CapabilityAuthorityComposition(foundation)

        val candidate = assertIs<LearningInstallResult.Installed>(
            candidates.install(
                LearningCandidate(
                    LearningCandidateId("h4b-candidate"),
                    LearningOrigin.Declared(LearningSourceId("h4b")),
                    "private proposal",
                    Instant.parse("2026-09-07T00:10:00Z")
                )
            )
        ).ownership
        val decision = assertIs<LearningDecisionInstallResult.Installed>(
            decisions.install(
                LearningDecision(
                    LearningDecisionId("h4b-decision"),
                    LearningCandidateReference(candidate.candidate.id, candidate.generation),
                    LearningDecisionDisposition.APPROVE,
                    "approved",
                    Instant.parse("2026-09-07T00:11:00Z")
                )
            )
        ).ownership
        val policy = assertIs<LearningPolicyInstallResult.Installed>(
            policies.install(
                LearningPolicy(
                    LearningPolicyId("h4b-policy"),
                    "allow persistent governed learning",
                    Instant.parse("2026-09-07T00:12:00Z")
                )
            )
        ).ownership
        val application = assertIs<LearningApplicationInstallResult.Installed>(
            applications.install(
                LearningApplicationIntent(
                    LearningApplicationId("h4b-application"),
                    LearningDecisionReference(decision.decision.id, decision.generation),
                    LearningPolicyReference(policy.policy.id, policy.generation),
                    LearningApplicationTarget.MEMORY,
                    Instant.parse("2026-09-07T00:13:00Z")
                )
            )
        ).ownership

        assertIs<CapabilityOwnershipResult.Registered>(
            authority.registerCapability(
                CapabilityDescriptor(
                    LearningApplicationAuthorityContract.capability,
                    CapabilityProviderId("h4b-learning")
                )
            )
        )
        assertIs<DirectAuthorityGrantOwnershipResult.Registered>(
            authority.registerDirectGrant(
                DirectAuthorityGrant(
                    principal,
                    LearningApplicationAuthorityContract.capability,
                    LearningApplicationAuthorityContract.scopeFor(LearningApplicationTarget.MEMORY)
                )
            )
        )

        val preflight = LearningApplicationPreflightValidator(
            applications,
            decisions,
            candidates,
            policies
        )
        val authorizer = LearningApplicationAuthorizer(preflight, authority)
        val gate = LearningApplicationMutationAuthorizationGate(
            mutations.inspectionPort(),
            authorizer
        )
        val applier = PersistentEncryptedLearningApplicationMutationApplier(
            foundation = foundation,
            mutations = mutations,
            authorizationGate = gate,
            memory = memory,
            knowledge = knowledge
        )

        val learnedRecord = MemoryRecord(
            MemoryRecordId("h4b-learned-memory"),
            MemoryProvenance(MemorySourceId("cognitive-learning")),
            "private durable learned evidence",
            Instant.parse("2026-09-07T00:14:00Z")
        )
        val plan = LearningApplicationMutationPlan(
            LearningApplicationMutationId("h4b-mutation"),
            LearningApplicationIntentReference(application.intent.id, application.generation),
            principal,
            LearningApplicationTarget.MEMORY,
            LearningApplicationIdempotencyKey("h4b-idempotency"),
            LearningApplicationMutationPayload.Memory(learnedRecord),
            Instant.parse("2026-09-07T00:15:00Z")
        )
        val prepared = assertIs<PersistentLearningApplicationMutationPrepareResult.Prepared>(
            mutations.prepare(plan)
        ).ownership
        val reference = LearningApplicationMutationReference(plan.id, prepared.generation)

        val applied = assertIs<LearningApplicationMutationApplicationResult.Applied>(
            applier.apply(reference)
        )
        val downstream = assertIs<LearningApplicationDownstreamReference.Memory>(
            applied.receipt.downstream
        )
        assertEquals(learnedRecord.id, downstream.recordId)
        assertEquals(
            downstream.generation,
            assertNotNull(memory.inspect(learnedRecord.id)).generation
        )
        assertTrue(mutations.isCompletedIdempotencyKey(plan.idempotencyKey))

        val reopenedMemory = openMemory(foundation(), backend, memoryStoreId)
        val reopenedMutations = openMutations(foundation(), backend, mutationStoreId)

        val restored = assertNotNull(reopenedMemory.inspect(learnedRecord.id))
        assertEquals(learnedRecord, restored.record)
        assertEquals(downstream.generation, restored.generation)
        assertEquals(
            applied.receipt,
            reopenedMutations.completedOutcomeByMutationId(plan.id)
        )
        assertEquals(
            applied.receipt,
            reopenedMutations.completedOutcomeByIdempotencyKey(plan.idempotencyKey)
        )
    }

    private fun openMutations(
        foundation: FoundationComposition,
        backend: InMemoryPersistentRecordBackend,
        storeId: PersistentStoreId
    ): PersistentLearningApplicationMutationComposition =
        assertIs<PersistentLearningApplicationMutationOpenResult.Opened>(
            PersistentLearningApplicationMutationComposition.open(
                foundation,
                storeId,
                backend
            )
        ).composition

    private fun openMemory(
        foundation: FoundationComposition,
        backend: InMemoryPersistentRecordBackend,
        storeId: PersistentStoreId
    ): EncryptedPersistentMemoryComposition =
        assertIs<EncryptedPersistentMemoryOpenResult.Opened>(
            EncryptedPersistentMemoryComposition.open(
                foundation,
                encryptedStore(foundation, backend, storeId),
                dekRef
            )
        ).composition

    private fun openKnowledge(
        foundation: FoundationComposition,
        backend: InMemoryPersistentRecordBackend,
        storeId: PersistentStoreId
    ): EncryptedPersistentKnowledgeComposition =
        assertIs<EncryptedPersistentKnowledgeOpenResult.Opened>(
            EncryptedPersistentKnowledgeComposition.open(
                foundation,
                encryptedStore(foundation, backend, storeId),
                dekRef
            )
        ).composition

    private fun encryptedStore(
        foundation: FoundationComposition,
        backend: InMemoryPersistentRecordBackend,
        storeId: PersistentStoreId
    ): EncryptedPersistentRecordStore {
        val store = assertIs<PersistentStoreOpenResult.Opened>(
            PersistentRecordStore.open(foundation, storeId, backend)
        ).store
        return EncryptedPersistentRecordStore(
            store = store,
            profile = CognitiveEncryptionProfile.AES_256_GCM,
            envelopeVersion = CognitiveEnvelopeVersion(1),
            nonceSource = DeterministicNonceSource(),
            aead = DeterministicAeadProvider(),
            dekResolver = object : CognitiveDekMaterialResolver {
                override fun resolve(
                    reference: CognitiveDekReference
                ): CognitiveEncryptionResult<CognitiveDekMaterial> =
                    if (reference == dekRef) {
                        CognitiveEncryptionResult.Success(material)
                    } else {
                        CognitiveEncryptionResult.Rejected(
                            CognitiveEncryptionFailureCategory.DEK_MISSING
                        )
                    }
            }
        )
    }

    private fun foundation(): FoundationComposition {
        val writer = InMemoryLogWriter()
        val sequence = AtomicInteger()
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, writer) },
            correlationIds = CorrelationIdGenerator {
                "h4b-persistent-learning-" + sequence.incrementAndGet()
            }
        )
    }

    private class DeterministicNonceSource : CognitiveNonceSource {
        private var next = 1
        override fun next(
            profile: CognitiveEncryptionProfile
        ): CognitiveEncryptionResult<CognitiveNonce> =
            CognitiveEncryptionResult.Success(
                CognitiveNonce(
                    profile,
                    ByteArray(profile.nonceSizeBytes) { (next++ + it).toByte() }
                )
            )
    }

    private class DeterministicAeadProvider : CognitiveAeadProvider {
        override fun seal(
            profile: CognitiveEncryptionProfile,
            dek: CognitiveDekMaterial,
            nonce: CognitiveNonce,
            associatedData: CognitiveAssociatedData,
            plaintext: CognitivePlaintext
        ): CognitiveEncryptionResult<CognitiveAeadSealedData> {
            val key = dek.copyBytes()
            val n = nonce.copyBytes()
            val plain = plaintext.copyBytes()
            val cipher = ByteArray(plain.size) { i ->
                (plain[i].toInt() xor key[i % key.size].toInt() xor n[i % n.size].toInt()).toByte()
            }
            return CognitiveEncryptionResult.Success(
                CognitiveAeadSealedData(
                    cipher,
                    tag(key, n, associatedData.copyBytes(), cipher)
                )
            )
        }

        override fun open(
            profile: CognitiveEncryptionProfile,
            dek: CognitiveDekMaterial,
            nonce: CognitiveNonce,
            associatedData: CognitiveAssociatedData,
            sealed: CognitiveAeadSealedData
        ): CognitiveEncryptionResult<CognitivePlaintext> {
            val key = dek.copyBytes()
            val n = nonce.copyBytes()
            val cipher = sealed.copyCiphertext()
            val expected = tag(key, n, associatedData.copyBytes(), cipher)
            if (!MessageDigest.isEqual(expected, sealed.copyAuthenticationTag())) {
                return CognitiveEncryptionResult.Rejected(
                    CognitiveEncryptionFailureCategory.CIPHERTEXT_AUTHENTICATION_FAILED
                )
            }
            return CognitiveEncryptionResult.Success(
                CognitivePlaintext(
                    ByteArray(cipher.size) { i ->
                        (cipher[i].toInt() xor key[i % key.size].toInt() xor n[i % n.size].toInt()).toByte()
                    }
                )
            )
        }

        private fun tag(
            key: ByteArray,
            nonce: ByteArray,
            aad: ByteArray,
            cipher: ByteArray
        ): ByteArray = MessageDigest.getInstance("SHA-256")
            .digest(key + nonce + aad + cipher)
            .copyOf(16)
    }
}
