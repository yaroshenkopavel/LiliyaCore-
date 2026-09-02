package pro.liliya.core.protectedmodel

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LargeProtectedModelStagingContractTest {
    @Test
    fun exact_segments_seal_then_publish_and_retire() {
        val backend = FakeBackend()
        val coordinator = coordinator(backend)
        val started = assertIs<LargeProtectedModelStagingStartResult.Started>(
            coordinator.start(request(total = 10, count = 2))
        )

        assertEquals(1, assertIs<LargeProtectedModelStagingAppendResult.Appended>(
            started.session.append(0, "alpha".encodeToByteArray())
        ).nextSegmentIndex)
        assertEquals(10L, assertIs<LargeProtectedModelStagingAppendResult.Appended>(
            started.session.append(1, "omega".encodeToByteArray())
        ).plaintextBytes)
        assertTrue(coordinator.publishedSources().isEmpty())

        val published = assertIs<LargeProtectedModelStagingPublishResult.Published>(
            started.session.sealAndPublish()
        )
        assertNull(coordinator.currentAttempt())
        assertEquals(1, coordinator.publishedSources().size)
        assertEquals(10L, published.ownership.source.plaintextBytes)
        assertEquals(
            LargeProtectedModelStagingDurabilityLevel.FILE_DATA_SYNCED,
            published.ownership.source.durabilityLevel
        )

        assertIs<LargeProtectedModelStagingRetireResult.Retired>(published.ownership.retire())
        assertTrue(coordinator.publishedSources().isEmpty())
        assertIs<LargeProtectedModelStagingRetireResult.Rejected>(published.ownership.retire())
    }

    @Test
    fun request_and_segment_budgets_fail_before_backend_write() {
        val backend = FakeBackend()
        val coordinator = coordinator(backend)

        val oversized = coordinator.start(request(total = 101, count = 1))
        assertEquals(
            LargeProtectedModelStagingFailure.RESOURCE_LIMIT_REJECTED,
            assertIs<LargeProtectedModelStagingStartResult.Rejected>(oversized).reason
        )
        assertEquals(0, backend.prepareCalls)

        val started = assertIs<LargeProtectedModelStagingStartResult.Started>(
            coordinator.start(request(total = 10, count = 1))
        )
        val rejected = assertIs<LargeProtectedModelStagingAppendResult.Rejected>(
            started.session.append(0, ByteArray(9))
        )
        assertEquals(LargeProtectedModelStagingFailure.SEGMENT_SIZE_INVALID, rejected.reason)
        assertEquals(0, backend.appendCalls)
        assertEquals(LargeProtectedModelStagingCleanupStatus.DELETED, rejected.cleanup.status)
    }

    @Test
    fun out_of_order_append_aborts_exact_attempt_and_cleans_working_artifact() {
        val backend = FakeBackend()
        val coordinator = coordinator(backend)
        val started = assertIs<LargeProtectedModelStagingStartResult.Started>(
            coordinator.start(request(total = 10, count = 2))
        )

        val rejected = assertIs<LargeProtectedModelStagingAppendResult.Rejected>(
            started.session.append(1, "alpha".encodeToByteArray())
        )
        assertEquals(LargeProtectedModelStagingFailure.SEGMENT_INDEX_INVALID, rejected.reason)
        assertNull(coordinator.currentAttempt())
        assertEquals(listOf("working-1"), backend.deletedIds)
    }

    @Test
    fun incomplete_or_size_mismatched_sequence_never_publishes() {
        val backend = FakeBackend()
        val coordinator = coordinator(backend)
        val incomplete = assertIs<LargeProtectedModelStagingStartResult.Started>(
            coordinator.start(request(total = 10, count = 2))
        )
        incomplete.session.append(0, "alpha".encodeToByteArray())
        val incompleteResult = assertIs<LargeProtectedModelStagingPublishResult.Rejected>(
            incomplete.session.sealAndPublish()
        )
        assertEquals(LargeProtectedModelStagingFailure.SEQUENCE_INCOMPLETE, incompleteResult.reason)
        assertEquals(0, backend.sealCalls)
        assertTrue(coordinator.publishedSources().isEmpty())

        val mismatch = assertIs<LargeProtectedModelStagingStartResult.Started>(
            coordinator.start(request(total = 6, count = 1))
        )
        mismatch.session.append(0, "alpha".encodeToByteArray())
        val mismatchResult = assertIs<LargeProtectedModelStagingPublishResult.Rejected>(
            mismatch.session.sealAndPublish()
        )
        assertEquals(LargeProtectedModelStagingFailure.AGGREGATE_SIZE_MISMATCH, mismatchResult.reason)
        assertTrue(coordinator.publishedSources().isEmpty())
    }

    @Test
    fun sealed_candidate_identity_mismatch_is_rejected_and_candidate_is_deleted() {
        val backend = FakeBackend().apply { mismatchCandidate = true }
        val coordinator = coordinator(backend)
        val started = assertIs<LargeProtectedModelStagingStartResult.Started>(
            coordinator.start(request(total = 5, count = 1))
        )
        started.session.append(0, "alpha".encodeToByteArray())

        val rejected = assertIs<LargeProtectedModelStagingPublishResult.Rejected>(
            started.session.sealAndPublish()
        )
        assertEquals(LargeProtectedModelStagingFailure.SEALED_CANDIDATE_MISMATCH, rejected.reason)
        assertTrue(backend.deletedIds.any { it.startsWith("sealed-") })
        assertTrue(coordinator.publishedSources().isEmpty())
    }

    @Test
    fun newer_attempt_while_old_seal_is_in_flight_blocks_stale_publication() {
        val sealEntered = CountDownLatch(1)
        val releaseSeal = CountDownLatch(1)
        val backend = FakeBackend(sealEntered = sealEntered, releaseSeal = releaseSeal)
        val coordinator = coordinator(backend)
        val first = assertIs<LargeProtectedModelStagingStartResult.Started>(
            coordinator.start(request(modelGeneration = 1, total = 5, count = 1))
        )
        first.session.append(0, "alpha".encodeToByteArray())

        val executor = Executors.newSingleThreadExecutor()
        val oldResult = executor.submit<LargeProtectedModelStagingPublishResult> {
            first.session.sealAndPublish()
        }
        assertTrue(sealEntered.await(2, TimeUnit.SECONDS))

        val second = assertIs<LargeProtectedModelStagingStartResult.Started>(
            coordinator.start(request(modelGeneration = 2, total = 5, count = 1))
        )
        releaseSeal.countDown()

        val stale = assertIs<LargeProtectedModelStagingPublishResult.Rejected>(
            oldResult.get(2, TimeUnit.SECONDS)
        )
        executor.shutdownNow()
        assertEquals(LargeProtectedModelStagingFailure.STALE_PUBLICATION, stale.reason)
        assertEquals(second.session.attempt, coordinator.currentAttempt())
        assertTrue(coordinator.publishedSources().isEmpty())
        assertTrue(backend.deletedIds.any { it.startsWith("sealed-") })
    }

    @Test
    fun backend_append_and_seal_failures_are_typed_and_cleanup_is_explicit() {
        val appendBackend = FakeBackend().apply { failAppend = true }
        val appendCoordinator = coordinator(appendBackend)
        val appendStarted = assertIs<LargeProtectedModelStagingStartResult.Started>(
            appendCoordinator.start(request(total = 5, count = 1))
        )
        val appendFailed = assertIs<LargeProtectedModelStagingAppendResult.Failed>(
            appendStarted.session.append(0, "alpha".encodeToByteArray())
        )
        assertEquals(LargeProtectedModelStagingFailure.BACKEND_APPEND_FAILED, appendFailed.reason)
        assertEquals(LargeProtectedModelStagingCleanupStatus.DELETED, appendFailed.cleanup.status)

        val sealBackend = FakeBackend().apply { rejectSeal = true }
        val sealCoordinator = coordinator(sealBackend)
        val sealStarted = assertIs<LargeProtectedModelStagingStartResult.Started>(
            sealCoordinator.start(request(total = 5, count = 1))
        )
        sealStarted.session.append(0, "alpha".encodeToByteArray())
        val sealRejected = assertIs<LargeProtectedModelStagingPublishResult.Rejected>(
            sealStarted.session.sealAndPublish()
        )
        assertEquals(LargeProtectedModelStagingFailure.BACKEND_SEAL_REJECTED, sealRejected.reason)
        assertEquals(LargeProtectedModelStagingCleanupStatus.DELETED, sealRejected.cleanup.status)
    }

    @Test
    fun second_append_while_first_backend_append_is_in_flight_aborts_without_backend_callback_under_core_lock() {
        val appendEntered = CountDownLatch(1)
        val releaseAppend = CountDownLatch(1)
        val backend = FakeBackend(appendEntered = appendEntered, releaseAppend = releaseAppend)
        val coordinator = coordinator(backend)
        backend.coordinator = coordinator
        val started = assertIs<LargeProtectedModelStagingStartResult.Started>(
            coordinator.start(request(total = 10, count = 2))
        )

        val executor = Executors.newSingleThreadExecutor()
        val first = executor.submit<LargeProtectedModelStagingAppendResult> {
            started.session.append(0, "alpha".encodeToByteArray())
        }
        assertTrue(appendEntered.await(2, TimeUnit.SECONDS))

        val second = assertIs<LargeProtectedModelStagingAppendResult.Rejected>(
            started.session.append(0, "omega".encodeToByteArray())
        )
        releaseAppend.countDown()
        first.get(2, TimeUnit.SECONDS)
        executor.shutdownNow()

        assertEquals(LargeProtectedModelStagingFailure.STALE_ATTEMPT, second.reason)
        assertFalse(backend.deleteObservedCoreLockHeld)
    }

    @Test
    fun identifiers_and_backend_exception_messages_are_not_exposed_by_result_rendering() {
        val backend = FakeBackend().apply {
            failAppend = true
            appendThrowable = IllegalStateException("private-staging-path-secret")
        }
        val coordinator = coordinator(backend)
        val started = assertIs<LargeProtectedModelStagingStartResult.Started>(
            coordinator.start(request(total = 5, count = 1))
        )
        val failed = assertIs<LargeProtectedModelStagingAppendResult.Failed>(
            started.session.append(0, "alpha".encodeToByteArray())
        )

        assertFalse(failed.toString().contains("private-staging-path-secret"))
        assertFalse(LargeProtectedModelOpaqueArtifactId("/private/model.gguf").toString().contains("/private/model.gguf"))
    }

    private fun coordinator(backend: FakeBackend) = LargeProtectedModelStagingCoordinator(
        backend = backend,
        budgets = LargeProtectedModelStagingBudgets(
            maxTotalPlaintextBytes = 100,
            maxSegmentPlaintextBytes = 8,
            maxSegmentCount = 8,
            maxActiveAttempts = 1,
            maxOpaqueIdentifierChars = 64
        )
    )

    private fun request(
        modelGeneration: Long = 1,
        total: Long,
        count: Int
    ) = LargeProtectedModelStagingRequest(
        model = ProtectedModelReference(
            ProtectedModelPackageId("model-package"),
            ProtectedModelGeneration(modelGeneration)
        ),
        profile = LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1,
        expectedPlaintextBytes = total,
        expectedSegmentCount = count
    )

    private class FakeBackend(
        private val sealEntered: CountDownLatch? = null,
        private val releaseSeal: CountDownLatch? = null,
        private val appendEntered: CountDownLatch? = null,
        private val releaseAppend: CountDownLatch? = null
    ) : LargeProtectedModelStagingBackend {
        override val backendId = LargeProtectedModelStagingBackendId("fake-staging")
        var coordinator: LargeProtectedModelStagingCoordinator? = null
        var prepareCalls = 0
        var appendCalls = 0
        var sealCalls = 0
        var mismatchCandidate = false
        var failAppend = false
        var rejectSeal = false
        var appendThrowable: Throwable = IllegalStateException("append failed")
        var deleteObservedCoreLockHeld = false
        val deletedIds = mutableListOf<String>()
        private var nextArtifact = 0
        private val bytes = mutableMapOf<String, Long>()

        override fun prepare(
            attempt: LargeProtectedModelStagingAttemptReference,
            expectedPlaintextBytes: Long
        ): LargeProtectedModelStagingPrepareResult {
            prepareCalls += 1
            nextArtifact += 1
            val id = LargeProtectedModelOpaqueArtifactId("working-$nextArtifact")
            bytes[id.value] = 0L
            return LargeProtectedModelStagingPrepareResult.Prepared(
                LargeProtectedModelWorkingArtifactHandle(backendId, attempt, id)
            )
        }

        override fun append(
            handle: LargeProtectedModelWorkingArtifactHandle,
            segmentIndex: Int,
            plaintext: ByteArray
        ): LargeProtectedModelStagingAppendBackendResult {
            appendCalls += 1
            appendEntered?.countDown()
            releaseAppend?.await(2, TimeUnit.SECONDS)
            if (failAppend) {
                return LargeProtectedModelStagingAppendBackendResult.Failed(throwable = appendThrowable)
            }
            bytes[handle.artifactId.value] = (bytes[handle.artifactId.value] ?: 0L) + plaintext.size
            return LargeProtectedModelStagingAppendBackendResult.Appended
        }

        override fun seal(handle: LargeProtectedModelWorkingArtifactHandle): LargeProtectedModelStagingSealResult {
            sealCalls += 1
            val sealedBytes = bytes[handle.artifactId.value] ?: 1L
            sealEntered?.countDown()
            releaseSeal?.await(2, TimeUnit.SECONDS)
            if (rejectSeal) return LargeProtectedModelStagingSealResult.Rejected()
            val sourceId = LargeProtectedModelOpaqueArtifactId("sealed-${handle.attempt.generation.value}-$sealCalls")
            return LargeProtectedModelStagingSealResult.Sealed(
                LargeProtectedModelSealedArtifactCandidate(
                    backendId = backendId,
                    attempt = if (mismatchCandidate) {
                        handle.attempt.copy(
                            generation = LargeProtectedModelStagingGeneration(handle.attempt.generation.value + 100)
                        )
                    } else handle.attempt,
                    sourceId = sourceId,
                    plaintextBytes = sealedBytes,
                    durabilityLevel = LargeProtectedModelStagingDurabilityLevel.FILE_DATA_SYNCED
                )
            )
        }

        override fun delete(
            artifactId: LargeProtectedModelOpaqueArtifactId
        ): LargeProtectedModelStagingDeleteResult {
            deletedIds += artifactId.value
            val activeCoordinator = coordinator
            if (activeCoordinator != null) {
                val executor = Executors.newSingleThreadExecutor()
                val probe = executor.submit<LargeProtectedModelStagingAttemptReference?> {
                    activeCoordinator.currentAttempt()
                }
                try {
                    probe.get(300, TimeUnit.MILLISECONDS)
                } catch (_: Exception) {
                    deleteObservedCoreLockHeld = true
                } finally {
                    executor.shutdownNow()
                }
            }
            bytes.remove(artifactId.value)
            return LargeProtectedModelStagingDeleteResult.Deleted
        }
    }
}
