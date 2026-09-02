package pro.liliya.core.protectedmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LargeProtectedModelStagingGenerationOverflowContractTest {
    @Test
    fun generation_overflow_fails_closed_before_backend_prepare() {
        var prepareCalls = 0
        val backend = object : LargeProtectedModelStagingBackend {
            override val backendId = LargeProtectedModelStagingBackendId("overflow-test")
            override fun prepare(
                attempt: LargeProtectedModelStagingAttemptReference,
                expectedPlaintextBytes: Long
            ): LargeProtectedModelStagingPrepareResult {
                prepareCalls += 1
                error("prepare must not be reached after generation overflow")
            }
            override fun append(
                handle: LargeProtectedModelWorkingArtifactHandle,
                segmentIndex: Int,
                plaintext: ByteArray
            ) = LargeProtectedModelStagingAppendBackendResult.Appended
            override fun seal(handle: LargeProtectedModelWorkingArtifactHandle) =
                LargeProtectedModelStagingSealResult.Rejected()
            override fun delete(artifactId: LargeProtectedModelOpaqueArtifactId) =
                LargeProtectedModelStagingDeleteResult.Deleted
        }
        val coordinator = LargeProtectedModelStagingCoordinator(
            backend,
            LargeProtectedModelStagingBudgets(100, 100, 1, 1, 64),
            initialGeneration = Long.MAX_VALUE
        )
        val result = coordinator.start(
            LargeProtectedModelStagingRequest(
                ProtectedModelReference(ProtectedModelPackageId("overflow"), ProtectedModelGeneration(1)),
                LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1,
                1,
                1
            )
        )
        assertEquals(
            LargeProtectedModelStagingFailure.PROVIDER_FAILED,
            assertIs<LargeProtectedModelStagingStartResult.Failed>(result).reason
        )
        assertEquals(0, prepareCalls)
    }
}
