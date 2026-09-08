package pro.liliya.android.runtime

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import pro.liliya.core.protectedmodel.LargeProtectedModelEncryptedSegmentSource
import pro.liliya.core.protectedmodel.LargeProtectedModelManifest
import pro.liliya.core.protectedmodel.LargeProtectedModelManifestFactory
import pro.liliya.core.protectedmodel.LargeProtectedModelManifestRequest
import pro.liliya.core.protectedmodel.LargeProtectedModelManifestResult
import pro.liliya.core.protectedmodel.LargeProtectedModelOpaqueArtifactId
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageEnvelope
import pro.liliya.core.protectedmodel.LargeProtectedModelPayloadProfile
import pro.liliya.core.protectedmodel.LargeProtectedModelResourceBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelSegmentDraft
import pro.liliya.core.protectedmodel.LargeProtectedModelSegmentReadResult
import pro.liliya.core.protectedmodel.LargeProtectedModelSegmentedOpenFailure
import pro.liliya.core.protectedmodel.LargeProtectedModelSegmentedOpenResult
import pro.liliya.core.protectedmodel.LargeProtectedModelSealedArtifactCandidate
import pro.liliya.core.protectedmodel.LargeProtectedModelSignedManifest
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAppendBackendResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingBackend
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingBackendId
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingCleanupStatus
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingCoordinator
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingDeleteResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingDurabilityLevel
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingPrepareResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingSealResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAttemptReference
import pro.liliya.core.protectedmodel.LargeProtectedModelWorkingArtifactHandle
import pro.liliya.core.protectedmodel.ModelDekGeneration
import pro.liliya.core.protectedmodel.ModelDekId
import pro.liliya.core.protectedmodel.ModelDekReference
import pro.liliya.core.protectedmodel.ProtectedModelEncryptionProfile
import pro.liliya.core.protectedmodel.ProtectedModelFormatVersion
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelProfileId
import pro.liliya.core.protectedmodel.ProtectedModelReference
import pro.liliya.core.protectedmodel.ProtectedModelSignatureAlgorithm
import pro.liliya.core.protectedmodel.ProtectedModelSignerId

class ProductGenerationStagingProvisionerContractTest {

    @Test
    fun exact_signed_metadata_streams_segments_then_publishes_one_staged_ownership() {
        val fixture = fixture()
        val backend = FakeBackend()
        val coordinator = coordinator(backend)
        val provisioner = ProductGenerationStagingProvisioner(
            openPort = ProductGenerationSegmentedOpenPort { _, _, consumer ->
                consumer(fixture.manifest.model, 0, "alpha".encodeToByteArray())
                consumer(fixture.manifest.model, 1, "omega".encodeToByteArray())
                LargeProtectedModelSegmentedOpenResult.Completed(
                    model = fixture.manifest.model,
                    segmentCount = 2,
                    plaintextBytes = 10
                )
            },
            staging = coordinator
        )

        val result = provisioner.provision(fixture.envelope, fixture.source)

        val ready = assertIs<ProductGenerationStagingProvisionResult.Ready>(result)
        assertEquals(fixture.manifest.model, ready.ownership.source.model)
        assertEquals(10L, ready.ownership.source.plaintextBytes)
        assertEquals(listOf(0, 1), backend.appendIndices)
        assertEquals(10L, backend.preparedExpectedBytes)
        assertEquals(1, backend.prepareCalls)
        assertEquals(1, backend.sealCalls)
        assertTrue(backend.deletedIds.isEmpty())
        assertEquals(1, coordinator.publishedSources().size)
    }

    @Test
    fun package_rejection_aborts_exact_attempt_and_never_publishes() {
        val fixture = fixture()
        val backend = FakeBackend()
        val coordinator = coordinator(backend)
        val provisioner = ProductGenerationStagingProvisioner(
            openPort = ProductGenerationSegmentedOpenPort { _, _, _ ->
                LargeProtectedModelSegmentedOpenResult.Rejected(
                    LargeProtectedModelSegmentedOpenFailure.PACKAGE_SIGNATURE_INVALID
                )
            },
            staging = coordinator
        )

        val result = provisioner.provision(fixture.envelope, fixture.source)

        val rejected = assertIs<ProductGenerationStagingProvisionResult.Rejected>(result)
        assertEquals(ProductGenerationStagingProvisionFailure.PACKAGE_REJECTED, rejected.reason)
        assertEquals(LargeProtectedModelStagingCleanupStatus.DELETED, rejected.cleanup?.status)
        assertEquals(1, backend.prepareCalls)
        assertEquals(0, backend.sealCalls)
        assertEquals(1, backend.deletedIds.size)
        assertTrue(coordinator.publishedSources().isEmpty())
    }

    @Test
    fun append_rejection_preserves_authoritative_cleanup_and_does_not_retry() {
        val fixture = fixture()
        val backend = FakeBackend().apply { rejectAppend = true }
        val coordinator = coordinator(backend)
        val provisioner = ProductGenerationStagingProvisioner(
            openPort = ProductGenerationSegmentedOpenPort { _, _, consumer ->
                consumer(fixture.manifest.model, 0, "alpha".encodeToByteArray())
                error("consumer must stop on append rejection")
            },
            staging = coordinator
        )

        val result = provisioner.provision(fixture.envelope, fixture.source)

        val rejected = assertIs<ProductGenerationStagingProvisionResult.Rejected>(result)
        assertEquals(
            ProductGenerationStagingProvisionFailure.STAGING_APPEND_REJECTED,
            rejected.reason
        )
        assertEquals(LargeProtectedModelStagingCleanupStatus.DELETED, rejected.cleanup?.status)
        assertEquals(1, backend.prepareCalls)
        assertEquals(1, backend.appendCalls)
        assertEquals(1, backend.deletedIds.size)
        assertEquals(0, backend.sealCalls)
    }

    @Test
    fun completion_mismatch_aborts_and_does_not_publish() {
        val fixture = fixture()
        val backend = FakeBackend()
        val coordinator = coordinator(backend)
        val provisioner = ProductGenerationStagingProvisioner(
            openPort = ProductGenerationSegmentedOpenPort { _, _, consumer ->
                consumer(fixture.manifest.model, 0, "alpha".encodeToByteArray())
                consumer(fixture.manifest.model, 1, "omega".encodeToByteArray())
                LargeProtectedModelSegmentedOpenResult.Completed(
                    model = fixture.manifest.model,
                    segmentCount = 2,
                    plaintextBytes = 9
                )
            },
            staging = coordinator
        )

        val result = provisioner.provision(fixture.envelope, fixture.source)

        val rejected = assertIs<ProductGenerationStagingProvisionResult.Rejected>(result)
        assertEquals(
            ProductGenerationStagingProvisionFailure.COMPLETION_MISMATCH,
            rejected.reason
        )
        assertEquals(LargeProtectedModelStagingCleanupStatus.DELETED, rejected.cleanup?.status)
        assertEquals(1, backend.prepareCalls)
        assertEquals(0, backend.sealCalls)
        assertTrue(coordinator.publishedSources().isEmpty())
    }

    @Test
    fun publish_rejection_is_bounded_and_uses_staging_cleanup() {
        val fixture = fixture()
        val backend = FakeBackend().apply { rejectSeal = true }
        val coordinator = coordinator(backend)
        val provisioner = ProductGenerationStagingProvisioner(
            openPort = ProductGenerationSegmentedOpenPort { _, _, consumer ->
                consumer(fixture.manifest.model, 0, "alpha".encodeToByteArray())
                consumer(fixture.manifest.model, 1, "omega".encodeToByteArray())
                LargeProtectedModelSegmentedOpenResult.Completed(
                    model = fixture.manifest.model,
                    segmentCount = 2,
                    plaintextBytes = 10
                )
            },
            staging = coordinator
        )

        val result = provisioner.provision(fixture.envelope, fixture.source)

        val rejected = assertIs<ProductGenerationStagingProvisionResult.Rejected>(result)
        assertEquals(
            ProductGenerationStagingProvisionFailure.STAGING_PUBLISH_REJECTED,
            rejected.reason
        )
        assertEquals(LargeProtectedModelStagingCleanupStatus.DELETED, rejected.cleanup?.status)
        assertEquals(1, backend.prepareCalls)
        assertEquals(1, backend.sealCalls)
        assertEquals(1, backend.deletedIds.size)
        assertTrue(coordinator.publishedSources().isEmpty())
    }

    @Test
    fun unexpected_loader_exception_is_bounded_without_private_text_and_no_retry() {
        val fixture = fixture()
        val backend = FakeBackend()
        val provisioner = ProductGenerationStagingProvisioner(
            openPort = ProductGenerationSegmentedOpenPort { _, _, _ ->
                error("PRIVATE-PROTECTED-MODEL-SOURCE")
            },
            staging = coordinator(backend)
        )

        val result = provisioner.provision(fixture.envelope, fixture.source)

        val rejected = assertIs<ProductGenerationStagingProvisionResult.Rejected>(result)
        assertEquals(ProductGenerationStagingProvisionFailure.INTERNAL_FAILURE, rejected.reason)
        assertTrue(!rejected.toString().contains("PRIVATE-PROTECTED-MODEL-SOURCE"))
        assertEquals(1, backend.prepareCalls)
        assertEquals(1, backend.deletedIds.size)
    }

    private data class Fixture(
        val manifest: LargeProtectedModelManifest,
        val envelope: LargeProtectedModelPackageEnvelope,
        val source: LargeProtectedModelEncryptedSegmentSource
    )

    private fun fixture(): Fixture {
        val model = ProtectedModelReference(
            ProtectedModelPackageId("product-generation-package"),
            ProtectedModelGeneration(7)
        )
        val dek = ModelDekReference(
            ModelDekId("product-generation-dek"),
            ModelDekGeneration(3)
        )
        val drafts = listOf(
            LargeProtectedModelSegmentDraft(
                index = 0,
                plaintextSizeBytes = 5,
                ciphertextBodySizeBytes = 5,
                nonce = ByteArray(12) { (it + 1).toByte() },
                protectedPayloadDigest = ByteArray(32) { 1 }
            ),
            LargeProtectedModelSegmentDraft(
                index = 1,
                plaintextSizeBytes = 5,
                ciphertextBodySizeBytes = 5,
                nonce = ByteArray(12) { (it + 21).toByte() },
                protectedPayloadDigest = ByteArray(32) { 2 }
            )
        )
        val request = LargeProtectedModelManifestRequest(
            profile = LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1,
            model = model,
            modelDek = dek,
            totalPlaintextSizeBytes = 10,
            totalCiphertextBodySizeBytes = 10,
            totalProtectedPayloadSizeBytes = 42,
            declaredSegmentCount = 2,
            segments = drafts
        )
        val manifest = assertIs<LargeProtectedModelManifestResult.Accepted>(
            LargeProtectedModelManifestFactory.create(
                request,
                LargeProtectedModelResourceBudgets(
                    maxTotalPlaintextBytes = 1024,
                    maxTotalCiphertextBodyBytes = 1024,
                    maxTotalProtectedPayloadBytes = 2048,
                    maxSegmentCount = 8,
                    minNonFinalSegmentPlaintextBytes = 1,
                    maxSegmentPlaintextBytes = 512,
                    maxSegmentCiphertextBodyBytes = 512,
                    maxStructuralIdentifierChars = 128,
                    maxCanonicalManifestBytes = 64 * 1024
                )
            )
        ).manifest
        val signed = LargeProtectedModelSignedManifest(
            formatVersion = ProtectedModelFormatVersion(1),
            modelProfileId = ProtectedModelProfileId("gguf-product-v1"),
            payload = manifest,
            encryptionProfile = ProtectedModelEncryptionProfile.AES_256_GCM,
            signatureAlgorithm = ProtectedModelSignatureAlgorithm.ED25519,
            signerId = ProtectedModelSignerId("product-signer")
        )
        val envelope = LargeProtectedModelPackageEnvelope(signed, byteArrayOf(1))
        val source = object : LargeProtectedModelEncryptedSegmentSource {
            override val segmentCount: Int = 2
            override fun read(index: Int): LargeProtectedModelSegmentReadResult =
                LargeProtectedModelSegmentReadResult.Missing
        }
        return Fixture(manifest, envelope, source)
    }

    private fun coordinator(backend: FakeBackend) =
        LargeProtectedModelStagingCoordinator(
            backend = backend,
            budgets = LargeProtectedModelStagingBudgets(
                maxTotalPlaintextBytes = 1024,
                maxSegmentPlaintextBytes = 512,
                maxSegmentCount = 8,
                maxActiveAttempts = 1,
                maxOpaqueIdentifierChars = 64
            )
        )

    private class FakeBackend : LargeProtectedModelStagingBackend {
        override val backendId = LargeProtectedModelStagingBackendId("product-generation-staging")
        var prepareCalls = 0
        var appendCalls = 0
        var sealCalls = 0
        var preparedExpectedBytes: Long? = null
        var rejectAppend = false
        var rejectSeal = false
        val appendIndices = mutableListOf<Int>()
        val deletedIds = mutableListOf<String>()
        private var appendedBytes = 0L

        override fun prepare(
            attempt: LargeProtectedModelStagingAttemptReference,
            expectedPlaintextBytes: Long
        ): LargeProtectedModelStagingPrepareResult {
            prepareCalls += 1
            preparedExpectedBytes = expectedPlaintextBytes
            appendedBytes = 0
            return LargeProtectedModelStagingPrepareResult.Prepared(
                LargeProtectedModelWorkingArtifactHandle(
                    backendId = backendId,
                    attempt = attempt,
                    artifactId = LargeProtectedModelOpaqueArtifactId("working-$prepareCalls")
                )
            )
        }

        override fun append(
            handle: LargeProtectedModelWorkingArtifactHandle,
            segmentIndex: Int,
            plaintext: ByteArray
        ): LargeProtectedModelStagingAppendBackendResult {
            appendCalls += 1
            appendIndices += segmentIndex
            if (rejectAppend) {
                return LargeProtectedModelStagingAppendBackendResult.Rejected()
            }
            appendedBytes += plaintext.size
            return LargeProtectedModelStagingAppendBackendResult.Appended
        }

        override fun seal(
            handle: LargeProtectedModelWorkingArtifactHandle
        ): LargeProtectedModelStagingSealResult {
            sealCalls += 1
            if (rejectSeal) {
                return LargeProtectedModelStagingSealResult.Rejected()
            }
            return LargeProtectedModelStagingSealResult.Sealed(
                LargeProtectedModelSealedArtifactCandidate(
                    backendId = backendId,
                    attempt = handle.attempt,
                    sourceId = LargeProtectedModelOpaqueArtifactId("sealed-$sealCalls"),
                    plaintextBytes = appendedBytes,
                    durabilityLevel =
                        LargeProtectedModelStagingDurabilityLevel.FILE_DATA_SYNCED
                )
            )
        }

        override fun delete(
            artifactId: LargeProtectedModelOpaqueArtifactId
        ): LargeProtectedModelStagingDeleteResult {
            deletedIds += artifactId.value
            return LargeProtectedModelStagingDeleteResult.Deleted
        }
    }
}
