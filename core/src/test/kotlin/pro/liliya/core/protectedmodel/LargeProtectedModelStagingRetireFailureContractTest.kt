package pro.liliya.core.protectedmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LargeProtectedModelStagingRetireFailureContractTest {
    @Test
    fun failed_retire_restores_live_ownership_for_explicit_retry() {
        var rejectDelete = true
        val backend = object : LargeProtectedModelStagingBackend {
            override val backendId = LargeProtectedModelStagingBackendId("retire-test")
            override fun prepare(
                attempt: LargeProtectedModelStagingAttemptReference,
                expectedPlaintextBytes: Long
            ) = LargeProtectedModelStagingPrepareResult.Prepared(
                LargeProtectedModelWorkingArtifactHandle(
                    backendId,
                    attempt,
                    LargeProtectedModelOpaqueArtifactId("working")
                )
            )
            override fun append(
                handle: LargeProtectedModelWorkingArtifactHandle,
                segmentIndex: Int,
                plaintext: ByteArray
            ) = LargeProtectedModelStagingAppendBackendResult.Appended
            override fun seal(handle: LargeProtectedModelWorkingArtifactHandle) =
                LargeProtectedModelStagingSealResult.Sealed(
                    LargeProtectedModelSealedArtifactCandidate(
                        backendId,
                        handle.attempt,
                        LargeProtectedModelOpaqueArtifactId("sealed"),
                        1,
                        LargeProtectedModelStagingDurabilityLevel.WRITE_CLOSED
                    )
                )
            override fun delete(artifactId: LargeProtectedModelOpaqueArtifactId): LargeProtectedModelStagingDeleteResult {
                if (artifactId.value == "sealed" && rejectDelete) {
                    rejectDelete = false
                    return LargeProtectedModelStagingDeleteResult.Rejected()
                }
                return LargeProtectedModelStagingDeleteResult.Deleted
            }
        }
        val coordinator = LargeProtectedModelStagingCoordinator(
            backend,
            LargeProtectedModelStagingBudgets(10, 10, 1, 1, 64)
        )
        val started = assertIs<LargeProtectedModelStagingStartResult.Started>(
            coordinator.start(
                LargeProtectedModelStagingRequest(
                    ProtectedModelReference(ProtectedModelPackageId("retire"), ProtectedModelGeneration(1)),
                    LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1,
                    1,
                    1
                )
            )
        )
        started.session.append(0, byteArrayOf(1))
        val ownership = assertIs<LargeProtectedModelStagingPublishResult.Published>(
            started.session.sealAndPublish()
        ).ownership
        assertEquals(
            LargeProtectedModelStagingFailure.BACKEND_DELETE_REJECTED,
            assertIs<LargeProtectedModelStagingRetireResult.Rejected>(ownership.retire()).reason
        )
        assertIs<LargeProtectedModelStagingRetireResult.Retired>(ownership.retire())
    }
}
