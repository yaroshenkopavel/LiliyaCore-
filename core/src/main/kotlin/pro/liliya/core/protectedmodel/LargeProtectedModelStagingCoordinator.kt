package pro.liliya.core.protectedmodel

import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local staging ownership and publication coordinator.
 *
 * Backend callbacks always execute outside the Core ownership lock. Every externally visible state advance is
 * revalidated against exact attempt/source identity afterward, so a newer attempt can invalidate an older
 * in-flight append/seal without allowing stale publication.
 */
class LargeProtectedModelStagingCoordinator(
    private val backend: LargeProtectedModelStagingBackend,
    private val budgets: LargeProtectedModelStagingBudgets,
    initialGeneration: Long = 0L
) {
    private enum class AttemptState { OPEN, SEALING, ABORTED }
    private enum class PublishedState { LIVE, RETIRING }

    private data class ActiveAttempt(
        val reference: LargeProtectedModelStagingAttemptReference,
        val request: LargeProtectedModelStagingRequest,
        val handle: LargeProtectedModelWorkingArtifactHandle,
        var nextSegmentIndex: Int = 0,
        var plaintextBytes: Long = 0L,
        var state: AttemptState = AttemptState.OPEN,
        var operationInProgress: Boolean = false
    )

    private data class EngineUseLeaseEntry(
        val source: LargeProtectedModelStagedSource
    )

    private data class PublishedEntry(
        val source: LargeProtectedModelStagedSource,
        var state: PublishedState = PublishedState.LIVE,
        var engineUseLease: EngineUseLeaseEntry? = null
    )

    private sealed interface AppendPreparation {
        data class Ready(
            val handle: LargeProtectedModelWorkingArtifactHandle,
            val reference: LargeProtectedModelStagingAttemptReference,
            val previousBytes: Long
        ) : AppendPreparation

        data class Abort(
            val handle: LargeProtectedModelWorkingArtifactHandle,
            val reason: LargeProtectedModelStagingFailure
        ) : AppendPreparation

        data class Reject(
            val reason: LargeProtectedModelStagingFailure
        ) : AppendPreparation
    }

    private sealed interface SealPreparation {
        data class Ready(
            val handle: LargeProtectedModelWorkingArtifactHandle,
            val reference: LargeProtectedModelStagingAttemptReference,
            val request: LargeProtectedModelStagingRequest
        ) : SealPreparation

        data class Abort(
            val handle: LargeProtectedModelWorkingArtifactHandle,
            val reason: LargeProtectedModelStagingFailure
        ) : SealPreparation

        data class Reject(
            val reason: LargeProtectedModelStagingFailure
        ) : SealPreparation
    }

    private sealed interface PublicationDecision {
        data class Published(val ownership: LargeProtectedModelStagedSourceOwnership) : PublicationDecision
        data class Reject(
            val reason: LargeProtectedModelStagingFailure,
            val cleanupCandidate: Boolean
        ) : PublicationDecision
    }

    private val lock = Any()
    private val nextGeneration = AtomicLong(initialGeneration)
    private var active: ActiveAttempt? = null
    private val published = mutableMapOf<LargeProtectedModelOpaqueArtifactId, PublishedEntry>()

    fun start(request: LargeProtectedModelStagingRequest): LargeProtectedModelStagingStartResult {
        validateRequest(request)?.let { return LargeProtectedModelStagingStartResult.Rejected(it) }

        val superseded = synchronized(lock) {
            active?.also {
                it.state = AttemptState.ABORTED
                active = null
            }?.handle
        }
        val supersededCleanup = superseded?.let { cleanup(it.artifactId) }
            ?: LargeProtectedModelStagingCleanupOutcome.NOT_REQUIRED

        val generationValue = allocateGeneration()
            ?: return LargeProtectedModelStagingStartResult.Failed(
                LargeProtectedModelStagingFailure.PROVIDER_FAILED
            )
        val reference = LargeProtectedModelStagingAttemptReference(
            LargeProtectedModelStagingGeneration(generationValue),
            request.model
        )

        val prepared = try {
            backend.prepare(reference, request.expectedPlaintextBytes)
        } catch (throwable: Throwable) {
            return LargeProtectedModelStagingStartResult.Failed(
                LargeProtectedModelStagingFailure.BACKEND_PREPARE_FAILED,
                throwable
            )
        }
        val handle = when (prepared) {
            is LargeProtectedModelStagingPrepareResult.Prepared -> prepared.handle
            is LargeProtectedModelStagingPrepareResult.Rejected ->
                return LargeProtectedModelStagingStartResult.Rejected(
                    LargeProtectedModelStagingFailure.BACKEND_PREPARE_REJECTED
                )
            is LargeProtectedModelStagingPrepareResult.Failed ->
                return LargeProtectedModelStagingStartResult.Failed(
                    LargeProtectedModelStagingFailure.BACKEND_PREPARE_FAILED,
                    prepared.throwable
                )
        }

        if (!validHandle(handle, reference)) {
            cleanup(handle.artifactId)
            return LargeProtectedModelStagingStartResult.Rejected(
                LargeProtectedModelStagingFailure.RESOURCE_LIMIT_REJECTED
            )
        }

        val installed = synchronized(lock) {
            if (nextGeneration.get() != generationValue || active != null) {
                false
            } else {
                active = ActiveAttempt(reference, request, handle)
                true
            }
        }
        if (!installed) {
            cleanup(handle.artifactId)
            return LargeProtectedModelStagingStartResult.Rejected(
                LargeProtectedModelStagingFailure.STALE_ATTEMPT
            )
        }

        return LargeProtectedModelStagingStartResult.Started(
            LargeProtectedModelStagingSession(reference, this),
            supersededCleanup
        )
    }

    internal fun append(
        attempt: LargeProtectedModelStagingAttemptReference,
        segmentIndex: Int,
        plaintext: ByteArray
    ): LargeProtectedModelStagingAppendResult {
        val preparation = synchronized(lock) {
            val current = active
                ?: return@synchronized AppendPreparation.Reject(
                    LargeProtectedModelStagingFailure.ATTEMPT_UNAVAILABLE
                )
            if (current.reference != attempt || current.state != AttemptState.OPEN) {
                return@synchronized AppendPreparation.Reject(
                    LargeProtectedModelStagingFailure.STALE_ATTEMPT
                )
            }
            if (current.operationInProgress) {
                current.state = AttemptState.ABORTED
                active = null
                return@synchronized AppendPreparation.Abort(
                    current.handle,
                    LargeProtectedModelStagingFailure.STALE_ATTEMPT
                )
            }
            val validationFailure = validateAppend(current, segmentIndex, plaintext.size.toLong())
            if (validationFailure != null) {
                current.state = AttemptState.ABORTED
                active = null
                return@synchronized AppendPreparation.Abort(current.handle, validationFailure)
            }
            current.operationInProgress = true
            AppendPreparation.Ready(current.handle, current.reference, current.plaintextBytes)
        }

        when (preparation) {
            is AppendPreparation.Reject ->
                return LargeProtectedModelStagingAppendResult.Rejected(
                    preparation.reason,
                    LargeProtectedModelStagingCleanupOutcome.NOT_REQUIRED
                )
            is AppendPreparation.Abort ->
                return LargeProtectedModelStagingAppendResult.Rejected(
                    preparation.reason,
                    cleanup(preparation.handle.artifactId)
                )
            is AppendPreparation.Ready -> Unit
        }
        preparation as AppendPreparation.Ready

        val backendResult = try {
            backend.append(preparation.handle, segmentIndex, plaintext)
        } catch (throwable: Throwable) {
            return failAppendAfterBackend(
                preparation.reference,
                LargeProtectedModelStagingFailure.BACKEND_APPEND_FAILED,
                throwable
            )
        }

        return when (backendResult) {
            LargeProtectedModelStagingAppendBackendResult.Appended -> {
                val nextBytes = try {
                    Math.addExact(preparation.previousBytes, plaintext.size.toLong())
                } catch (_: ArithmeticException) {
                    return failAppendAfterBackend(
                        preparation.reference,
                        LargeProtectedModelStagingFailure.AGGREGATE_SIZE_OVERFLOW,
                        null,
                        rejected = true
                    )
                }
                synchronized(lock) {
                    val current = active
                    if (current == null || current.reference != preparation.reference ||
                        current.handle != preparation.handle || current.state != AttemptState.OPEN
                    ) {
                        LargeProtectedModelStagingAppendResult.Rejected(
                            LargeProtectedModelStagingFailure.STALE_ATTEMPT,
                            LargeProtectedModelStagingCleanupOutcome.NOT_REQUIRED
                        )
                    } else {
                        current.plaintextBytes = nextBytes
                        current.nextSegmentIndex += 1
                        current.operationInProgress = false
                        LargeProtectedModelStagingAppendResult.Appended(
                            current.nextSegmentIndex,
                            current.plaintextBytes
                        )
                    }
                }
            }
            is LargeProtectedModelStagingAppendBackendResult.Rejected ->
                failAppendAfterBackend(
                    preparation.reference,
                    LargeProtectedModelStagingFailure.BACKEND_APPEND_REJECTED,
                    null,
                    rejected = true
                )
            is LargeProtectedModelStagingAppendBackendResult.Failed ->
                failAppendAfterBackend(
                    preparation.reference,
                    LargeProtectedModelStagingFailure.BACKEND_APPEND_FAILED,
                    backendResult.throwable
                )
        }
    }

    internal fun sealAndPublish(
        attempt: LargeProtectedModelStagingAttemptReference
    ): LargeProtectedModelStagingPublishResult {
        val preparation = synchronized(lock) {
            val current = active
                ?: return@synchronized SealPreparation.Reject(
                    LargeProtectedModelStagingFailure.ATTEMPT_UNAVAILABLE
                )
            if (current.reference != attempt || current.state != AttemptState.OPEN ||
                current.operationInProgress
            ) {
                return@synchronized SealPreparation.Reject(
                    LargeProtectedModelStagingFailure.STALE_ATTEMPT
                )
            }
            if (current.nextSegmentIndex != current.request.expectedSegmentCount) {
                current.state = AttemptState.ABORTED
                active = null
                return@synchronized SealPreparation.Abort(
                    current.handle,
                    LargeProtectedModelStagingFailure.SEQUENCE_INCOMPLETE
                )
            }
            if (current.plaintextBytes != current.request.expectedPlaintextBytes) {
                current.state = AttemptState.ABORTED
                active = null
                return@synchronized SealPreparation.Abort(
                    current.handle,
                    LargeProtectedModelStagingFailure.AGGREGATE_SIZE_MISMATCH
                )
            }
            current.state = AttemptState.SEALING
            current.operationInProgress = true
            SealPreparation.Ready(current.handle, current.reference, current.request)
        }

        when (preparation) {
            is SealPreparation.Reject ->
                return LargeProtectedModelStagingPublishResult.Rejected(
                    preparation.reason,
                    LargeProtectedModelStagingCleanupOutcome.NOT_REQUIRED
                )
            is SealPreparation.Abort ->
                return LargeProtectedModelStagingPublishResult.Rejected(
                    preparation.reason,
                    cleanup(preparation.handle.artifactId)
                )
            is SealPreparation.Ready -> Unit
        }
        preparation as SealPreparation.Ready

        val sealed = try {
            backend.seal(preparation.handle)
        } catch (throwable: Throwable) {
            return failSeal(
                preparation.reference,
                LargeProtectedModelStagingFailure.BACKEND_SEAL_FAILED,
                throwable
            )
        }
        val candidate = when (sealed) {
            is LargeProtectedModelStagingSealResult.Sealed -> sealed.candidate
            is LargeProtectedModelStagingSealResult.Rejected ->
                return failSeal(
                    preparation.reference,
                    LargeProtectedModelStagingFailure.BACKEND_SEAL_REJECTED,
                    null,
                    rejected = true
                )
            is LargeProtectedModelStagingSealResult.Failed ->
                return failSeal(
                    preparation.reference,
                    LargeProtectedModelStagingFailure.BACKEND_SEAL_FAILED,
                    sealed.throwable
                )
        }

        val candidateFailure = validateCandidate(candidate, preparation)
        if (candidateFailure != null) {
            synchronized(lock) {
                val current = active
                if (current?.reference == preparation.reference) {
                    current.state = AttemptState.ABORTED
                    active = null
                }
            }
            return LargeProtectedModelStagingPublishResult.Rejected(
                candidateFailure,
                cleanup(candidate.sourceId)
            )
        }

        val decision = synchronized(lock) {
            val current = active
            if (current == null || current.reference != preparation.reference ||
                current.handle != preparation.handle || current.state != AttemptState.SEALING
            ) {
                PublicationDecision.Reject(
                    LargeProtectedModelStagingFailure.STALE_PUBLICATION,
                    cleanupCandidate = true
                )
            } else if (published.containsKey(candidate.sourceId)) {
                current.state = AttemptState.ABORTED
                active = null
                // The conflicting id is already owned by a live published source. Deleting it would
                // risk destroying that live artifact, so Core deliberately performs no delete here.
                PublicationDecision.Reject(
                    LargeProtectedModelStagingFailure.SEALED_CANDIDATE_MISMATCH,
                    cleanupCandidate = false
                )
            } else {
                val source = LargeProtectedModelStagedSource(
                    backendId = candidate.backendId,
                    sourceId = candidate.sourceId,
                    model = preparation.request.model,
                    stagingGeneration = preparation.reference.generation,
                    plaintextBytes = candidate.plaintextBytes,
                    profile = preparation.request.profile,
                    durabilityLevel = candidate.durabilityLevel
                )
                val entry = PublishedEntry(source)
                published[source.sourceId] = entry
                active = null
                PublicationDecision.Published(ownership(entry))
            }
        }

        return when (decision) {
            is PublicationDecision.Published ->
                LargeProtectedModelStagingPublishResult.Published(decision.ownership)
            is PublicationDecision.Reject ->
                LargeProtectedModelStagingPublishResult.Rejected(
                    decision.reason,
                    if (decision.cleanupCandidate) {
                        cleanup(candidate.sourceId)
                    } else {
                        LargeProtectedModelStagingCleanupOutcome.NOT_REQUIRED
                    }
                )
        }
    }

    internal fun abort(
        attempt: LargeProtectedModelStagingAttemptReference
    ): LargeProtectedModelStagingAbortResult {
        val handle = synchronized(lock) {
            val current = active
                ?: return LargeProtectedModelStagingAbortResult.Rejected(
                    LargeProtectedModelStagingFailure.ATTEMPT_UNAVAILABLE
                )
            if (current.reference != attempt) {
                return LargeProtectedModelStagingAbortResult.Rejected(
                    LargeProtectedModelStagingFailure.STALE_ATTEMPT
                )
            }
            current.state = AttemptState.ABORTED
            active = null
            current.handle
        }
        return LargeProtectedModelStagingAbortResult.Aborted(cleanup(handle.artifactId))
    }

    fun currentAttempt(): LargeProtectedModelStagingAttemptReference? = synchronized(lock) {
        active?.reference
    }

    fun publishedSources(): List<LargeProtectedModelStagedSource> = synchronized(lock) {
        published.values.map { it.source }.sortedBy { it.stagingGeneration.value }
    }

    fun acquireEngineUse(
        source: LargeProtectedModelStagedSource
    ): LargeProtectedModelEngineUseAcquireResult = synchronized(lock) {
        val current = published[source.sourceId]
            ?: return@synchronized LargeProtectedModelEngineUseAcquireResult.Rejected(
                LargeProtectedModelEngineUseFailure.SOURCE_STALE
            )
        if (current.source !== source) {
            return@synchronized LargeProtectedModelEngineUseAcquireResult.Rejected(
                LargeProtectedModelEngineUseFailure.SOURCE_STALE
            )
        }
        if (current.state != PublishedState.LIVE) {
            return@synchronized LargeProtectedModelEngineUseAcquireResult.Rejected(
                LargeProtectedModelEngineUseFailure.SOURCE_RETIRING
            )
        }
        if (current.engineUseLease != null) {
            return@synchronized LargeProtectedModelEngineUseAcquireResult.Rejected(
                LargeProtectedModelEngineUseFailure.SOURCE_ALREADY_IN_USE
            )
        }

        val leaseEntry = EngineUseLeaseEntry(current.source)
        current.engineUseLease = leaseEntry
        LargeProtectedModelEngineUseAcquireResult.Acquired(engineUseLease(current, leaseEntry))
    }

    private fun ownership(entry: PublishedEntry): LargeProtectedModelStagedSourceOwnership =
        object : LargeProtectedModelStagedSourceOwnership {
            override val source: LargeProtectedModelStagedSource = entry.source
            override fun retire(): LargeProtectedModelStagingRetireResult = retireExact(entry)
        }

    private fun engineUseLease(
        entry: PublishedEntry,
        leaseEntry: EngineUseLeaseEntry
    ): LargeProtectedModelEngineUseLease {
        val capability = LargeProtectedModelEngineSourceCapability(
            backendId = entry.source.backendId,
            model = entry.source.model,
            stagingGeneration = entry.source.stagingGeneration,
            plaintextBytes = entry.source.plaintextBytes,
            profile = entry.source.profile,
            durabilityLevel = entry.source.durabilityLevel,
            sourceIdentity = entry.source
        )
        return object : LargeProtectedModelEngineUseLease {
            override val source: LargeProtectedModelEngineSourceCapability = capability

            override fun release(): LargeProtectedModelEngineUseReleaseResult =
                releaseEngineUseExact(entry, leaseEntry)
        }
    }

    private fun releaseEngineUseExact(
        entry: PublishedEntry,
        leaseEntry: EngineUseLeaseEntry
    ): LargeProtectedModelEngineUseReleaseResult = synchronized(lock) {
        val current = published[entry.source.sourceId]
            ?: return@synchronized LargeProtectedModelEngineUseReleaseResult.Rejected(
                LargeProtectedModelEngineUseFailure.LEASE_STALE
            )
        if (current !== entry || current.engineUseLease !== leaseEntry || leaseEntry.source !== entry.source) {
            return@synchronized LargeProtectedModelEngineUseReleaseResult.Rejected(
                LargeProtectedModelEngineUseFailure.LEASE_STALE
            )
        }
        current.engineUseLease = null
        LargeProtectedModelEngineUseReleaseResult.Released
    }

    private fun retireExact(entry: PublishedEntry): LargeProtectedModelStagingRetireResult {
        synchronized(lock) {
            val current = published[entry.source.sourceId]
                ?: return LargeProtectedModelStagingRetireResult.Rejected(
                    LargeProtectedModelStagingFailure.RETIRE_STALE
                )
            if (current !== entry || current.state != PublishedState.LIVE) {
                return LargeProtectedModelStagingRetireResult.Rejected(
                    LargeProtectedModelStagingFailure.RETIRE_STALE
                )
            }
            if (current.engineUseLease != null) {
                return LargeProtectedModelStagingRetireResult.Rejected(
                    LargeProtectedModelStagingFailure.RETIRE_IN_USE
                )
            }
            current.state = PublishedState.RETIRING
        }

        val deleted = try {
            backend.delete(entry.source.sourceId)
        } catch (throwable: Throwable) {
            synchronized(lock) {
                if (published[entry.source.sourceId] === entry) entry.state = PublishedState.LIVE
            }
            return LargeProtectedModelStagingRetireResult.Failed(
                LargeProtectedModelStagingFailure.BACKEND_DELETE_FAILED,
                throwable
            )
        }
        return when (deleted) {
            LargeProtectedModelStagingDeleteResult.Deleted -> synchronized(lock) {
                if (published[entry.source.sourceId] !== entry) {
                    LargeProtectedModelStagingRetireResult.Rejected(
                        LargeProtectedModelStagingFailure.RETIRE_STALE
                    )
                } else {
                    published.remove(entry.source.sourceId)
                    LargeProtectedModelStagingRetireResult.Retired
                }
            }
            is LargeProtectedModelStagingDeleteResult.Rejected -> {
                synchronized(lock) {
                    if (published[entry.source.sourceId] === entry) entry.state = PublishedState.LIVE
                }
                LargeProtectedModelStagingRetireResult.Rejected(
                    LargeProtectedModelStagingFailure.BACKEND_DELETE_REJECTED
                )
            }
            is LargeProtectedModelStagingDeleteResult.Failed -> {
                synchronized(lock) {
                    if (published[entry.source.sourceId] === entry) entry.state = PublishedState.LIVE
                }
                LargeProtectedModelStagingRetireResult.Failed(
                    LargeProtectedModelStagingFailure.BACKEND_DELETE_FAILED,
                    deleted.throwable
                )
            }
        }
    }

    private fun allocateGeneration(): Long? {
        while (true) {
            val current = nextGeneration.get()
            if (current < 0L || current == Long.MAX_VALUE) return null
            val next = current + 1L
            if (nextGeneration.compareAndSet(current, next)) return next
        }
    }

    private fun validateRequest(
        request: LargeProtectedModelStagingRequest
    ): LargeProtectedModelStagingFailure? {
        if (request.profile != LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1) {
            return LargeProtectedModelStagingFailure.UNSUPPORTED_PROFILE
        }
        if (request.expectedPlaintextBytes <= 0L ||
            request.expectedPlaintextBytes > budgets.maxTotalPlaintextBytes ||
            request.expectedSegmentCount <= 0 ||
            request.expectedSegmentCount > budgets.maxSegmentCount ||
            budgets.maxActiveAttempts < 1
        ) {
            return LargeProtectedModelStagingFailure.RESOURCE_LIMIT_REJECTED
        }
        return null
    }

    private fun validateAppend(
        current: ActiveAttempt,
        segmentIndex: Int,
        segmentBytes: Long
    ): LargeProtectedModelStagingFailure? {
        if (segmentIndex != current.nextSegmentIndex ||
            segmentIndex < 0 || segmentIndex >= current.request.expectedSegmentCount
        ) {
            return LargeProtectedModelStagingFailure.SEGMENT_INDEX_INVALID
        }
        if (segmentBytes <= 0L || segmentBytes > budgets.maxSegmentPlaintextBytes) {
            return LargeProtectedModelStagingFailure.SEGMENT_SIZE_INVALID
        }
        val total = try {
            Math.addExact(current.plaintextBytes, segmentBytes)
        } catch (_: ArithmeticException) {
            return LargeProtectedModelStagingFailure.AGGREGATE_SIZE_OVERFLOW
        }
        if (total > current.request.expectedPlaintextBytes) {
            return LargeProtectedModelStagingFailure.AGGREGATE_SIZE_EXCEEDED
        }
        return null
    }

    private fun validHandle(
        handle: LargeProtectedModelWorkingArtifactHandle,
        reference: LargeProtectedModelStagingAttemptReference
    ): Boolean =
        handle.backendId == backend.backendId &&
            handle.attempt == reference &&
            handle.artifactId.value.length <= budgets.maxOpaqueIdentifierChars

    private fun validateCandidate(
        candidate: LargeProtectedModelSealedArtifactCandidate,
        preparation: SealPreparation.Ready
    ): LargeProtectedModelStagingFailure? {
        if (candidate.backendId != backend.backendId ||
            candidate.attempt != preparation.reference ||
            candidate.sourceId.value.length > budgets.maxOpaqueIdentifierChars ||
            candidate.plaintextBytes != preparation.request.expectedPlaintextBytes
        ) {
            return LargeProtectedModelStagingFailure.SEALED_CANDIDATE_MISMATCH
        }
        return null
    }

    private fun cleanup(
        artifactId: LargeProtectedModelOpaqueArtifactId
    ): LargeProtectedModelStagingCleanupOutcome {
        val deleted = try {
            backend.delete(artifactId)
        } catch (throwable: Throwable) {
            return LargeProtectedModelStagingCleanupOutcome(
                LargeProtectedModelStagingCleanupStatus.FAILED,
                throwable.javaClass.name
            )
        }
        return when (deleted) {
            LargeProtectedModelStagingDeleteResult.Deleted ->
                LargeProtectedModelStagingCleanupOutcome(
                    LargeProtectedModelStagingCleanupStatus.DELETED
                )
            is LargeProtectedModelStagingDeleteResult.Rejected ->
                LargeProtectedModelStagingCleanupOutcome(
                    LargeProtectedModelStagingCleanupStatus.REJECTED
                )
            is LargeProtectedModelStagingDeleteResult.Failed ->
                LargeProtectedModelStagingCleanupOutcome(
                    LargeProtectedModelStagingCleanupStatus.FAILED,
                    deleted.throwable?.javaClass?.name
                )
        }
    }

    private fun failAppendAfterBackend(
        reference: LargeProtectedModelStagingAttemptReference,
        reason: LargeProtectedModelStagingFailure,
        throwable: Throwable?,
        rejected: Boolean = false
    ): LargeProtectedModelStagingAppendResult {
        val handle = synchronized(lock) {
            val current = active
            if (current?.reference != reference) return@synchronized null
            current.state = AttemptState.ABORTED
            active = null
            current.handle
        }
        val cleanup = handle?.let { cleanup(it.artifactId) }
            ?: LargeProtectedModelStagingCleanupOutcome.NOT_REQUIRED
        return if (rejected) {
            LargeProtectedModelStagingAppendResult.Rejected(reason, cleanup)
        } else {
            LargeProtectedModelStagingAppendResult.Failed(reason, cleanup, throwable)
        }
    }

    private fun failSeal(
        reference: LargeProtectedModelStagingAttemptReference,
        reason: LargeProtectedModelStagingFailure,
        throwable: Throwable?,
        rejected: Boolean = false
    ): LargeProtectedModelStagingPublishResult {
        val handle = synchronized(lock) {
            val current = active
            if (current?.reference != reference) return@synchronized null
            current.state = AttemptState.ABORTED
            active = null
            current.handle
        }
        val cleanup = handle?.let { cleanup(it.artifactId) }
            ?: LargeProtectedModelStagingCleanupOutcome.NOT_REQUIRED
        return if (rejected) {
            LargeProtectedModelStagingPublishResult.Rejected(reason, cleanup)
        } else {
            LargeProtectedModelStagingPublishResult.Failed(reason, cleanup, throwable)
        }
    }
}
