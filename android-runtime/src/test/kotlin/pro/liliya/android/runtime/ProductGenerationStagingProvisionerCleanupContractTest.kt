package pro.liliya.android.runtime

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import pro.liliya.core.protectedmodel.LargeProtectedModelEncryptedSegmentSource
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
import pro.liliya.core.protectedmodel.LargeProtectedModelSignedManifest
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAppendBackendResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingBackend
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingBackendId
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingCleanupStatus
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingCoordinator
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingDeleteResult
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

class ProductGenerationStagingProvisionerCleanupContractTest {
    @Test
    fun cleanup_provider_failure_remains_explicit_and_bounded() {
        val fixture = fixture()
        val backend = CleanupFailingBackend()
        val provisioner = ProductGenerationStagingProvisioner(
            openPort = ProductGenerationSegmentedOpenPort { _, _, _ ->
                LargeProtectedModelSegmentedOpenResult.Rejected(
                    LargeProtectedModelSegmentedOpenFailure.PACKAGE_SIGNATURE_INVALID
                )
            },
            staging = LargeProtectedModelStagingCoordinator(
                backend = backend,
                budgets = LargeProtectedModelStagingBudgets(
                    maxTotalPlaintextBytes = 1024,
                    maxSegmentPlaintextBytes = 512,
                    maxSegmentCount = 8,
                    maxActiveAttempts = 1,
                    maxOpaqueIdentifierChars = 64
                )
            )
        )

        val result = provisioner.provision(fixture.first, fixture.second)

        val rejected = assertIs<ProductGenerationStagingProvisionResult.Rejected>(result)
        assertEquals(ProductGenerationStagingProvisionFailure.PACKAGE_REJECTED, rejected.reason)
        assertEquals(LargeProtectedModelStagingCleanupStatus.FAILED, rejected.cleanup?.status)
        assertEquals(1, backend.prepareCalls)
        assertEquals(1, backend.deleteCalls)
    }

    private fun fixture(): Pair<LargeProtectedModelPackageEnvelope, LargeProtectedModelEncryptedSegmentSource> {
        val model = ProtectedModelReference(
            ProtectedModelPackageId("cleanup-package"),
            ProtectedModelGeneration(1)
        )
        val dek = ModelDekReference(ModelDekId("cleanup-dek"), ModelDekGeneration(1))
        val request = LargeProtectedModelManifestRequest(
            profile = LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1,
            model = model,
            modelDek = dek,
            totalPlaintextSizeBytes = 5,
            totalCiphertextBodySizeBytes = 5,
            totalProtectedPayloadSizeBytes = 21,
            declaredSegmentCount = 1,
            segments = listOf(
                LargeProtectedModelSegmentDraft(
                    index = 0,
                    plaintextSizeBytes = 5,
                    ciphertextBodySizeBytes = 5,
                    nonce = ByteArray(12) { (it + 1).toByte() },
                    protectedPayloadDigest = ByteArray(32) { 1 }
                )
            )
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
            modelProfileId = ProtectedModelProfileId("cleanup-profile"),
            payload = manifest,
            encryptionProfile = ProtectedModelEncryptionProfile.AES_256_GCM,
            signatureAlgorithm = ProtectedModelSignatureAlgorithm.ED25519,
            signerId = ProtectedModelSignerId("cleanup-signer")
        )
        val envelope = LargeProtectedModelPackageEnvelope(signed, byteArrayOf(1))
        val source = object : LargeProtectedModelEncryptedSegmentSource {
            override val segmentCount: Int = 1
            override fun read(index: Int): LargeProtectedModelSegmentReadResult =
                LargeProtectedModelSegmentReadResult.Missing
        }
        return envelope to source
    }

    private class CleanupFailingBackend : LargeProtectedModelStagingBackend {
        override val backendId = LargeProtectedModelStagingBackendId("cleanup-failing")
        var prepareCalls = 0
        var deleteCalls = 0

        override fun prepare(
            attempt: LargeProtectedModelStagingAttemptReference,
            expectedPlaintextBytes: Long
        ): LargeProtectedModelStagingPrepareResult {
            prepareCalls += 1
            return LargeProtectedModelStagingPrepareResult.Prepared(
                LargeProtectedModelWorkingArtifactHandle(
                    backendId = backendId,
                    attempt = attempt,
                    artifactId = LargeProtectedModelOpaqueArtifactId("cleanup-working")
                )
            )
        }

        override fun append(
            handle: LargeProtectedModelWorkingArtifactHandle,
            segmentIndex: Int,
            plaintext: ByteArray
        ): LargeProtectedModelStagingAppendBackendResult =
            LargeProtectedModelStagingAppendBackendResult.Appended

        override fun seal(
            handle: LargeProtectedModelWorkingArtifactHandle
        ): LargeProtectedModelStagingSealResult = LargeProtectedModelStagingSealResult.Rejected()

        override fun delete(
            artifactId: LargeProtectedModelOpaqueArtifactId
        ): LargeProtectedModelStagingDeleteResult {
            deleteCalls += 1
            return LargeProtectedModelStagingDeleteResult.Failed(
                throwable = IllegalStateException("PRIVATE-CLEANUP-DETAIL")
            )
        }
    }
}
