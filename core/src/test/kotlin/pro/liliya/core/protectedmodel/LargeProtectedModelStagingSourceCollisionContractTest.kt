package pro.liliya.core.protectedmodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LargeProtectedModelStagingSourceCollisionContractTest {
    @Test
    fun colliding_sealed_source_id_does_not_delete_existing_live_published_source() {
        val deleted = mutableListOf<String>()
        var prepareCounter = 0
        val backend = object : LargeProtectedModelStagingBackend {
            override val backendId = LargeProtectedModelStagingBackendId("collision-test")

            override fun prepare(
                attempt: LargeProtectedModelStagingAttemptReference,
                expectedPlaintextBytes: Long
            ): LargeProtectedModelStagingPrepareResult {
                prepareCounter += 1
                return LargeProtectedModelStagingPrepareResult.Prepared(
                    LargeProtectedModelWorkingArtifactHandle(
                        backendId,
                        attempt,
                        LargeProtectedModelOpaqueArtifactId("working-$prepareCounter")
                    )
                )
            }

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
                        LargeProtectedModelOpaqueArtifactId("same-published-id"),
                        1,
                        LargeProtectedModelStagingDurabilityLevel.WRITE_CLOSED
                    )
                )

            override fun delete(
                artifactId: LargeProtectedModelOpaqueArtifactId
            ): LargeProtectedModelStagingDeleteResult {
                deleted += artifactId.value
                return LargeProtectedModelStagingDeleteResult.Deleted
            }
        }

        val coordinator = LargeProtectedModelStagingCoordinator(
            backend,
            LargeProtectedModelStagingBudgets(10, 10, 1, 1, 64)
        )

        val first = start(coordinator, 1)
        first.session.append(0, byteArrayOf(1))
        val firstPublished = assertIs<LargeProtectedModelStagingPublishResult.Published>(
            first.session.sealAndPublish()
        )

        val second = start(coordinator, 2)
        second.session.append(0, byteArrayOf(2))
        val collision = assertIs<LargeProtectedModelStagingPublishResult.Rejected>(
            second.session.sealAndPublish()
        )

        assertEquals(LargeProtectedModelStagingFailure.SEALED_CANDIDATE_MISMATCH, collision.reason)
        assertEquals(LargeProtectedModelStagingCleanupStatus.NOT_REQUIRED, collision.cleanup.status)
        assertEquals(1, coordinator.publishedSources().size)
        assertEquals(firstPublished.ownership.source.stagingGeneration, coordinator.publishedSources().single().stagingGeneration)
        assertEquals(emptyList(), deleted.filter { it == "same-published-id" })
    }

    private fun start(
        coordinator: LargeProtectedModelStagingCoordinator,
        modelGeneration: Long
    ) = assertIs<LargeProtectedModelStagingStartResult.Started>(
        coordinator.start(
            LargeProtectedModelStagingRequest(
                ProtectedModelReference(
                    ProtectedModelPackageId("collision"),
                    ProtectedModelGeneration(modelGeneration)
                ),
                LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1,
                1,
                1
            )
        )
    )
}
