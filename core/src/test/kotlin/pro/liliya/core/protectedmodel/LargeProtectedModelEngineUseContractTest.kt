package pro.liliya.core.protectedmodel

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.modelengine.ModelEngineBackendId
import pro.liliya.core.modelengine.ModelEngineCloseFailure
import pro.liliya.core.modelengine.ModelEngineCloseResult
import pro.liliya.core.modelengine.ModelEngineHandleId
import pro.liliya.core.modelengine.ModelEngineInferenceRequest
import pro.liliya.core.modelengine.ModelEngineInferenceResult
import pro.liliya.core.modelengine.ModelEngineLoadFailure
import pro.liliya.core.modelengine.ModelEngineLoadResult
import pro.liliya.core.modelengine.ModelEngineSessionOwnership
import pro.liliya.core.modelengine.StagedModelEngineLoadCoordinator
import pro.liliya.core.modelengine.StagedModelEngineLoaderPort

class LargeProtectedModelEngineUseContractTest {
    @Test
    fun active_lease_blocks_retire_and_release_allows_exact_retirement() {
        val backend = FakeBackend()
        val coordinator = coordinator(backend)
        val ownership = publish(coordinator)

        val acquired = assertIs<LargeProtectedModelEngineUseAcquireResult.Acquired>(
            coordinator.acquireEngineUse(ownership)
        )
        val duplicateAcquire = assertIs<LargeProtectedModelEngineUseAcquireResult.Rejected>(
            coordinator.acquireEngineUse(ownership)
        )
        assertEquals(
            LargeProtectedModelEngineUseFailure.SOURCE_ALREADY_IN_USE,
            duplicateAcquire.reason
        )

        val blocked = assertIs<LargeProtectedModelStagingRetireResult.Rejected>(ownership.retire())
        assertEquals(LargeProtectedModelStagingFailure.RETIRE_IN_USE, blocked.reason)
        assertEquals(0, backend.deleteCalls)

        assertIs<LargeProtectedModelEngineUseReleaseResult.Released>(acquired.lease.release())
        val duplicateRelease = assertIs<LargeProtectedModelEngineUseReleaseResult.Rejected>(
            acquired.lease.release()
        )
        assertEquals(LargeProtectedModelEngineUseFailure.LEASE_STALE, duplicateRelease.reason)

        assertIs<LargeProtectedModelStagingRetireResult.Retired>(ownership.retire())
        assertEquals(1, backend.deleteCalls)
    }

    @Test
    fun retire_in_progress_rejects_engine_use_without_resurrection() {
        val deleteEntered = CountDownLatch(1)
        val releaseDelete = CountDownLatch(1)
        val backend = FakeBackend(deleteEntered, releaseDelete)
        val coordinator = coordinator(backend)
        val ownership = publish(coordinator)

        val executor = Executors.newSingleThreadExecutor()
        val retire = executor.submit<LargeProtectedModelStagingRetireResult> { ownership.retire() }
        assertTrue(deleteEntered.await(2, TimeUnit.SECONDS))

        val acquire = assertIs<LargeProtectedModelEngineUseAcquireResult.Rejected>(
            coordinator.acquireEngineUse(ownership)
        )
        assertEquals(LargeProtectedModelEngineUseFailure.SOURCE_RETIRING, acquire.reason)

        releaseDelete.countDown()
        assertIs<LargeProtectedModelStagingRetireResult.Retired>(retire.get(2, TimeUnit.SECONDS))
        executor.shutdownNow()

        val stale = assertIs<LargeProtectedModelEngineUseAcquireResult.Rejected>(
            coordinator.acquireEngineUse(ownership)
        )
        assertEquals(LargeProtectedModelEngineUseFailure.SOURCE_STALE, stale.reason)
    }

    @Test
    fun forged_or_foreign_ownership_cannot_acquire_lease_and_coordinators_are_isolated() {
        val first = coordinator(FakeBackend("first-backend"))
        val firstOwnership = publish(first)
        val source = firstOwnership.source

        val forgedExactSourceOwnership = object : LargeProtectedModelStagedSourceOwnership {
            override val source: LargeProtectedModelStagedSource = firstOwnership.source
            override fun retire(): LargeProtectedModelStagingRetireResult =
                LargeProtectedModelStagingRetireResult.Rejected(
                    LargeProtectedModelStagingFailure.RETIRE_STALE
                )
        }
        val forgedExact = assertIs<LargeProtectedModelEngineUseAcquireResult.Rejected>(
            first.acquireEngineUse(forgedExactSourceOwnership)
        )
        assertEquals(LargeProtectedModelEngineUseFailure.SOURCE_STALE, forgedExact.reason)

        val copied = LargeProtectedModelStagedSource(
            backendId = source.backendId,
            sourceId = source.sourceId,
            model = source.model,
            stagingGeneration = source.stagingGeneration,
            plaintextBytes = source.plaintextBytes,
            profile = source.profile,
            durabilityLevel = source.durabilityLevel
        )
        val copiedOwnership = object : LargeProtectedModelStagedSourceOwnership {
            override val source: LargeProtectedModelStagedSource = copied
            override fun retire(): LargeProtectedModelStagingRetireResult =
                LargeProtectedModelStagingRetireResult.Rejected(
                    LargeProtectedModelStagingFailure.RETIRE_STALE
                )
        }
        val copiedResult = assertIs<LargeProtectedModelEngineUseAcquireResult.Rejected>(
            first.acquireEngineUse(copiedOwnership)
        )
        assertEquals(LargeProtectedModelEngineUseFailure.SOURCE_STALE, copiedResult.reason)

        val second = coordinator(FakeBackend("second-backend"))
        val foreign = assertIs<LargeProtectedModelEngineUseAcquireResult.Rejected>(
            second.acquireEngineUse(firstOwnership)
        )
        assertEquals(LargeProtectedModelEngineUseFailure.SOURCE_STALE, foreign.reason)

        assertIs<LargeProtectedModelEngineUseAcquireResult.Acquired>(
            first.acquireEngineUse(firstOwnership)
        )
    }

    @Test
    fun rejected_or_throwing_engine_load_releases_lease() {
        val rejectedBackend = FakeBackend()
        val rejectedStaging = coordinator(rejectedBackend)
        val rejectedOwnership = publish(rejectedStaging)
        val rejectedLoader = StagedModelEngineLoadCoordinator(
            rejectedStaging,
            StagedModelEngineLoaderPort {
                ModelEngineLoadResult.Rejected(ModelEngineLoadFailure.UNSUPPORTED_MODEL)
            }
        )

        val rejected = assertIs<ModelEngineLoadResult.Rejected>(rejectedLoader.load(rejectedOwnership))
        assertEquals(ModelEngineLoadFailure.UNSUPPORTED_MODEL, rejected.reason)
        assertIs<LargeProtectedModelStagingRetireResult.Retired>(rejectedOwnership.retire())

        val throwingBackend = FakeBackend()
        val throwingStaging = coordinator(throwingBackend)
        val throwingOwnership = publish(throwingStaging)
        val throwingLoader = StagedModelEngineLoadCoordinator(
            throwingStaging,
            StagedModelEngineLoaderPort { throw IllegalStateException("private-provider-secret") }
        )

        val failed = assertIs<ModelEngineLoadResult.Rejected>(throwingLoader.load(throwingOwnership))
        assertEquals(ModelEngineLoadFailure.PROVIDER_FAILED, failed.reason)
        assertFalse(failed.toString().contains("private-provider-secret"))
        assertIs<LargeProtectedModelStagingRetireResult.Retired>(throwingOwnership.retire())
    }

    @Test
    fun successful_session_retains_lease_failed_close_keeps_barrier_and_successful_close_releases_it() {
        val backend = FakeBackend()
        val staging = coordinator(backend)
        val ownership = publish(staging)
        val engine = FakeEngineSession(
            closeResults = ArrayDeque<ModelEngineCloseResult>().apply {
                add(ModelEngineCloseResult.Failed(ModelEngineCloseFailure.CLOSE_FAILED))
                add(ModelEngineCloseResult.Closed)
            }
        )
        val loader = StagedModelEngineLoadCoordinator(
            staging,
            StagedModelEngineLoaderPort { ModelEngineLoadResult.Loaded(engine) }
        )

        val loaded = assertIs<ModelEngineLoadResult.Loaded>(loader.load(ownership))
        assertIs<ModelEngineInferenceResult.Succeeded>(
            loaded.ownership.infer(ModelEngineInferenceRequest("hello", 16))
        )

        val blockedBeforeClose = assertIs<LargeProtectedModelStagingRetireResult.Rejected>(
            ownership.retire()
        )
        assertEquals(LargeProtectedModelStagingFailure.RETIRE_IN_USE, blockedBeforeClose.reason)

        assertIs<ModelEngineCloseResult.Failed>(loaded.ownership.close())
        val blockedAfterFailedClose = assertIs<LargeProtectedModelStagingRetireResult.Rejected>(
            ownership.retire()
        )
        assertEquals(LargeProtectedModelStagingFailure.RETIRE_IN_USE, blockedAfterFailedClose.reason)

        assertIs<ModelEngineCloseResult.Closed>(loaded.ownership.close())
        assertIs<LargeProtectedModelStagingRetireResult.Retired>(ownership.retire())
        assertEquals(2, engine.closeCalls)
    }

    @Test
    fun provider_load_callback_reentrancy_runs_outside_staging_ownership_lock() {
        val backend = FakeBackend()
        val staging = coordinator(backend)
        val ownership = publish(staging)
        var reentrantRetire: LargeProtectedModelStagingRetireResult? = null

        val loader = StagedModelEngineLoadCoordinator(
            staging,
            StagedModelEngineLoaderPort {
                reentrantRetire = ownership.retire()
                ModelEngineLoadResult.Rejected(ModelEngineLoadFailure.LOAD_REJECTED)
            }
        )

        assertIs<ModelEngineLoadResult.Rejected>(loader.load(ownership))
        val blocked = assertIs<LargeProtectedModelStagingRetireResult.Rejected>(reentrantRetire)
        assertEquals(LargeProtectedModelStagingFailure.RETIRE_IN_USE, blocked.reason)
        assertIs<LargeProtectedModelStagingRetireResult.Retired>(ownership.retire())
    }

    @Test
    fun capability_is_path_free_and_redacts_opaque_source_id() {
        val backend = FakeBackend()
        val staging = coordinator(backend)
        val ownership = publish(staging)
        val lease = assertIs<LargeProtectedModelEngineUseAcquireResult.Acquired>(
            staging.acquireEngineUse(ownership)
        ).lease

        val rendered = lease.source.toString()
        assertFalse(rendered.contains(ownership.source.sourceId.value))
        assertFalse(rendered.contains("/data/"))
        assertEquals(ownership.source.sourceId, lease.source.sourceId)
        assertEquals(ownership.source.plaintextBytes, lease.source.plaintextBytes)
    }

    private fun coordinator(backend: FakeBackend) = LargeProtectedModelStagingCoordinator(
        backend = backend,
        budgets = LargeProtectedModelStagingBudgets(
            maxTotalPlaintextBytes = 100,
            maxSegmentPlaintextBytes = 16,
            maxSegmentCount = 8,
            maxActiveAttempts = 1,
            maxOpaqueIdentifierChars = 64
        )
    )

    private fun publish(
        coordinator: LargeProtectedModelStagingCoordinator
    ): LargeProtectedModelStagedSourceOwnership {
        val started = assertIs<LargeProtectedModelStagingStartResult.Started>(
            coordinator.start(
                LargeProtectedModelStagingRequest(
                    model = ProtectedModelReference(
                        ProtectedModelPackageId("model-package"),
                        ProtectedModelGeneration(1)
                    ),
                    profile = LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1,
                    expectedPlaintextBytes = 5,
                    expectedSegmentCount = 1
                )
            )
        )
        assertIs<LargeProtectedModelStagingAppendResult.Appended>(
            started.session.append(0, "alpha".encodeToByteArray())
        )
        return assertIs<LargeProtectedModelStagingPublishResult.Published>(
            started.session.sealAndPublish()
        ).ownership
    }

    private class FakeBackend(
        private val id: String = "fake-staging",
        private val deleteEntered: CountDownLatch? = null,
        private val releaseDelete: CountDownLatch? = null
    ) : LargeProtectedModelStagingBackend {
        constructor(
            deleteEntered: CountDownLatch,
            releaseDelete: CountDownLatch
        ) : this("fake-staging", deleteEntered, releaseDelete)

        override val backendId = LargeProtectedModelStagingBackendId(id)
        var deleteCalls = 0
        private var nextArtifact = 0
        private val bytes = mutableMapOf<LargeProtectedModelOpaqueArtifactId, Long>()

        override fun prepare(
            attempt: LargeProtectedModelStagingAttemptReference,
            expectedPlaintextBytes: Long
        ): LargeProtectedModelStagingPrepareResult {
            nextArtifact += 1
            val artifactId = LargeProtectedModelOpaqueArtifactId("artifact-$nextArtifact")
            bytes[artifactId] = 0L
            return LargeProtectedModelStagingPrepareResult.Prepared(
                LargeProtectedModelWorkingArtifactHandle(backendId, attempt, artifactId)
            )
        }

        override fun append(
            handle: LargeProtectedModelWorkingArtifactHandle,
            segmentIndex: Int,
            plaintext: ByteArray
        ): LargeProtectedModelStagingAppendBackendResult {
            bytes[handle.artifactId] = (bytes[handle.artifactId] ?: 0L) + plaintext.size
            return LargeProtectedModelStagingAppendBackendResult.Appended
        }

        override fun seal(
            handle: LargeProtectedModelWorkingArtifactHandle
        ): LargeProtectedModelStagingSealResult = LargeProtectedModelStagingSealResult.Sealed(
            LargeProtectedModelSealedArtifactCandidate(
                backendId = backendId,
                attempt = handle.attempt,
                sourceId = handle.artifactId,
                plaintextBytes = bytes[handle.artifactId] ?: 0L,
                durabilityLevel = LargeProtectedModelStagingDurabilityLevel.FILE_DATA_SYNCED
            )
        )

        override fun delete(
            artifactId: LargeProtectedModelOpaqueArtifactId
        ): LargeProtectedModelStagingDeleteResult {
            deleteCalls += 1
            deleteEntered?.countDown()
            releaseDelete?.await(2, TimeUnit.SECONDS)
            bytes.remove(artifactId)
            return LargeProtectedModelStagingDeleteResult.Deleted
        }
    }

    private class FakeEngineSession(
        private val closeResults: ArrayDeque<ModelEngineCloseResult>
    ) : ModelEngineSessionOwnership {
        override val backendId = ModelEngineBackendId("fake-engine")
        override val handleId = ModelEngineHandleId("fake-handle")
        var closeCalls = 0

        override fun infer(request: ModelEngineInferenceRequest): ModelEngineInferenceResult =
            ModelEngineInferenceResult.Succeeded("ok")

        override fun close(): ModelEngineCloseResult {
            closeCalls += 1
            return if (closeResults.isEmpty()) {
                ModelEngineCloseResult.Closed
            } else {
                closeResults.removeFirst()
            }
        }
    }
}
