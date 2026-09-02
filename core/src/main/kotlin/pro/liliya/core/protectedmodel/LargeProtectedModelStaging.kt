package pro.liliya.core.protectedmodel

import java.util.concurrent.atomic.AtomicLong

@JvmInline
value class LargeProtectedModelStagingGeneration(val value: Long) {
    init { require(value > 0L) { "staging generation must be positive" } }
}

@JvmInline
value class LargeProtectedModelStagingBackendId(val value: String) {
    init { require(value.isNotBlank()) { "staging backend id must not be blank" } }
}

@JvmInline
value class LargeProtectedModelOpaqueArtifactId(val value: String) {
    init { require(value.isNotBlank()) { "staging artifact id must not be blank" } }
    override fun toString(): String = "LargeProtectedModelOpaqueArtifactId([redacted])"
}

data class LargeProtectedModelStagingAttemptReference(
    val generation: LargeProtectedModelStagingGeneration,
    val model: ProtectedModelReference
)

data class LargeProtectedModelStagingBudgets(
    val maxTotalPlaintextBytes: Long,
    val maxSegmentPlaintextBytes: Long,
    val maxSegmentCount: Int,
    val maxActiveAttempts: Int,
    val maxOpaqueIdentifierChars: Int
) {
    init {
        require(maxTotalPlaintextBytes > 0L) { "max staging plaintext bytes must be positive" }
        require(maxSegmentPlaintextBytes > 0L) { "max staging segment bytes must be positive" }
        require(maxSegmentPlaintextBytes <= maxTotalPlaintextBytes) {
            "max staging segment bytes exceed total budget"
        }
        require(maxSegmentCount > 0) { "max staging segment count must be positive" }
        require(maxActiveAttempts > 0) { "max active staging attempts must be positive" }
        require(maxOpaqueIdentifierChars > 0) { "max opaque staging id chars must be positive" }
    }
}

data class LargeProtectedModelStagingRequest(
    val model: ProtectedModelReference,
    val profile: LargeProtectedModelPayloadProfile,
    val expectedPlaintextBytes: Long,
    val expectedSegmentCount: Int
)

enum class LargeProtectedModelStagingDurabilityLevel {
    WRITE_CLOSED,
    FILE_DATA_SYNCED,
    ATOMIC_VISIBILITY_RENAMED,
    DIRECTORY_METADATA_SYNCED
}

data class LargeProtectedModelWorkingArtifactHandle(
    val backendId: LargeProtectedModelStagingBackendId,
    val attempt: LargeProtectedModelStagingAttemptReference,
    val artifactId: LargeProtectedModelOpaqueArtifactId
) {
    override fun toString(): String =
        "LargeProtectedModelWorkingArtifactHandle(backendId=$backendId, attempt=$attempt, artifactId=<redacted>)"
}

data class LargeProtectedModelSealedArtifactCandidate(
    val backendId: LargeProtectedModelStagingBackendId,
    val attempt: LargeProtectedModelStagingAttemptReference,
    val sourceId: LargeProtectedModelOpaqueArtifactId,
    val plaintextBytes: Long,
    val durabilityLevel: LargeProtectedModelStagingDurabilityLevel
) {
    init { require(plaintextBytes > 0L) { "sealed staging plaintext bytes must be positive" } }
    override fun toString(): String =
        "LargeProtectedModelSealedArtifactCandidate(backendId=$backendId, attempt=$attempt, " +
            "sourceId=<redacted>, plaintextBytes=$plaintextBytes, durabilityLevel=$durabilityLevel)"
}

enum class LargeProtectedModelStagingBackendFailure {
    REJECTED,
    PROVIDER_FAILED
}

sealed interface LargeProtectedModelStagingPrepareResult {
    data class Prepared(val handle: LargeProtectedModelWorkingArtifactHandle) :
        LargeProtectedModelStagingPrepareResult
    data class Rejected(
        val reason: LargeProtectedModelStagingBackendFailure = LargeProtectedModelStagingBackendFailure.REJECTED
    ) : LargeProtectedModelStagingPrepareResult
    data class Failed(
        val reason: LargeProtectedModelStagingBackendFailure =
            LargeProtectedModelStagingBackendFailure.PROVIDER_FAILED,
        val throwable: Throwable? = null
    ) : LargeProtectedModelStagingPrepareResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

sealed interface LargeProtectedModelStagingAppendBackendResult {
    data object Appended : LargeProtectedModelStagingAppendBackendResult
    data class Rejected(
        val reason: LargeProtectedModelStagingBackendFailure = LargeProtectedModelStagingBackendFailure.REJECTED
    ) : LargeProtectedModelStagingAppendBackendResult
    data class Failed(
        val reason: LargeProtectedModelStagingBackendFailure =
            LargeProtectedModelStagingBackendFailure.PROVIDER_FAILED,
        val throwable: Throwable? = null
    ) : LargeProtectedModelStagingAppendBackendResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

sealed interface LargeProtectedModelStagingSealResult {
    data class Sealed(val candidate: LargeProtectedModelSealedArtifactCandidate) :
        LargeProtectedModelStagingSealResult
    data class Rejected(
        val reason: LargeProtectedModelStagingBackendFailure = LargeProtectedModelStagingBackendFailure.REJECTED
    ) : LargeProtectedModelStagingSealResult
    data class Failed(
        val reason: LargeProtectedModelStagingBackendFailure =
            LargeProtectedModelStagingBackendFailure.PROVIDER_FAILED,
        val throwable: Throwable? = null
    ) : LargeProtectedModelStagingSealResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

sealed interface LargeProtectedModelStagingDeleteResult {
    data object Deleted : LargeProtectedModelStagingDeleteResult
    data class Rejected(
        val reason: LargeProtectedModelStagingBackendFailure = LargeProtectedModelStagingBackendFailure.REJECTED
    ) : LargeProtectedModelStagingDeleteResult
    data class Failed(
        val reason: LargeProtectedModelStagingBackendFailure =
            LargeProtectedModelStagingBackendFailure.PROVIDER_FAILED,
        val throwable: Throwable? = null
    ) : LargeProtectedModelStagingDeleteResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

interface LargeProtectedModelStagingBackend {
    val backendId: LargeProtectedModelStagingBackendId

    fun prepare(
        attempt: LargeProtectedModelStagingAttemptReference,
        expectedPlaintextBytes: Long
    ): LargeProtectedModelStagingPrepareResult

    fun append(
        handle: LargeProtectedModelWorkingArtifactHandle,
        segmentIndex: Int,
        plaintext: ByteArray
    ): LargeProtectedModelStagingAppendBackendResult

    fun seal(handle: LargeProtectedModelWorkingArtifactHandle): LargeProtectedModelStagingSealResult

    fun delete(artifactId: LargeProtectedModelOpaqueArtifactId): LargeProtectedModelStagingDeleteResult
}

enum class LargeProtectedModelStagingCleanupStatus {
    NOT_REQUIRED,
    DELETED,
    REJECTED,
    FAILED
}

data class LargeProtectedModelStagingCleanupOutcome(
    val status: LargeProtectedModelStagingCleanupStatus,
    val throwableClassName: String? = null
) {
    override fun toString(): String =
        "LargeProtectedModelStagingCleanupOutcome(status=$status, throwableClassName=$throwableClassName)"

    companion object {
        val NOT_REQUIRED = LargeProtectedModelStagingCleanupOutcome(
            LargeProtectedModelStagingCleanupStatus.NOT_REQUIRED
        )
    }
}

enum class LargeProtectedModelStagingFailure {
    UNSUPPORTED_PROFILE,
    RESOURCE_LIMIT_REJECTED,
    ATTEMPT_UNAVAILABLE,
    STALE_ATTEMPT,
    SEGMENT_INDEX_INVALID,
    SEGMENT_SIZE_INVALID,
    AGGREGATE_SIZE_OVERFLOW,
    AGGREGATE_SIZE_EXCEEDED,
    BACKEND_PREPARE_REJECTED,
    BACKEND_PREPARE_FAILED,
    BACKEND_APPEND_REJECTED,
    BACKEND_APPEND_FAILED,
    SEQUENCE_INCOMPLETE,
    AGGREGATE_SIZE_MISMATCH,
    BACKEND_SEAL_REJECTED,
    BACKEND_SEAL_FAILED,
    SEALED_CANDIDATE_MISMATCH,
    STALE_PUBLICATION,
    RETIRE_STALE,
    BACKEND_DELETE_REJECTED,
    BACKEND_DELETE_FAILED,
    PROVIDER_FAILED
}

sealed interface LargeProtectedModelStagingStartResult {
    data class Started(
        val session: LargeProtectedModelStagingSession,
        val supersededCleanup: LargeProtectedModelStagingCleanupOutcome =
            LargeProtectedModelStagingCleanupOutcome.NOT_REQUIRED
    ) : LargeProtectedModelStagingStartResult

    data class Rejected(val reason: LargeProtectedModelStagingFailure) :
        LargeProtectedModelStagingStartResult

    data class Failed(
        val reason: LargeProtectedModelStagingFailure,
        val throwable: Throwable? = null
    ) : LargeProtectedModelStagingStartResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

sealed interface LargeProtectedModelStagingAppendResult {
    data class Appended(
        val nextSegmentIndex: Int,
        val plaintextBytes: Long
    ) : LargeProtectedModelStagingAppendResult

    data class Rejected(
        val reason: LargeProtectedModelStagingFailure,
        val cleanup: LargeProtectedModelStagingCleanupOutcome
    ) : LargeProtectedModelStagingAppendResult

    data class Failed(
        val reason: LargeProtectedModelStagingFailure,
        val cleanup: LargeProtectedModelStagingCleanupOutcome,
        val throwable: Throwable? = null
    ) : LargeProtectedModelStagingAppendResult {
        override fun toString(): String =
            "Failed(reason=$reason, cleanup=$cleanup, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

class LargeProtectedModelStagedSource internal constructor(
    val backendId: LargeProtectedModelStagingBackendId,
    val sourceId: LargeProtectedModelOpaqueArtifactId,
    val model: ProtectedModelReference,
    val stagingGeneration: LargeProtectedModelStagingGeneration,
    val plaintextBytes: Long,
    val profile: LargeProtectedModelPayloadProfile,
    val durabilityLevel: LargeProtectedModelStagingDurabilityLevel
) {
    override fun toString(): String =
        "LargeProtectedModelStagedSource(backendId=$backendId, sourceId=<redacted>, model=$model, " +
            "stagingGeneration=${stagingGeneration.value}, plaintextBytes=$plaintextBytes, " +
            "profile=$profile, durabilityLevel=$durabilityLevel)"
}

interface LargeProtectedModelStagedSourceOwnership {
    val source: LargeProtectedModelStagedSource
    fun retire(): LargeProtectedModelStagingRetireResult
}

sealed interface LargeProtectedModelStagingPublishResult {
    data class Published(val ownership: LargeProtectedModelStagedSourceOwnership) :
        LargeProtectedModelStagingPublishResult

    data class Rejected(
        val reason: LargeProtectedModelStagingFailure,
        val cleanup: LargeProtectedModelStagingCleanupOutcome
    ) : LargeProtectedModelStagingPublishResult

    data class Failed(
        val reason: LargeProtectedModelStagingFailure,
        val cleanup: LargeProtectedModelStagingCleanupOutcome,
        val throwable: Throwable? = null
    ) : LargeProtectedModelStagingPublishResult {
        override fun toString(): String =
            "Failed(reason=$reason, cleanup=$cleanup, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

sealed interface LargeProtectedModelStagingAbortResult {
    data class Aborted(val cleanup: LargeProtectedModelStagingCleanupOutcome) :
        LargeProtectedModelStagingAbortResult
    data class Rejected(val reason: LargeProtectedModelStagingFailure) :
        LargeProtectedModelStagingAbortResult
}

sealed interface LargeProtectedModelStagingRetireResult {
    data object Retired : LargeProtectedModelStagingRetireResult
    data class Rejected(val reason: LargeProtectedModelStagingFailure) :
        LargeProtectedModelStagingRetireResult
    data class Failed(
        val reason: LargeProtectedModelStagingFailure,
        val throwable: Throwable? = null
    ) : LargeProtectedModelStagingRetireResult {
        override fun toString(): String =
            "Failed(reason=$reason, throwable=${throwable?.javaClass?.name ?: "null"})"
    }
}

class LargeProtectedModelStagingSession internal constructor(
    val attempt: LargeProtectedModelStagingAttemptReference,
    private val coordinator: LargeProtectedModelStagingCoordinator
) {
    fun append(segmentIndex: Int, plaintext: ByteArray): LargeProtectedModelStagingAppendResult =
        coordinator.append(attempt, segmentIndex, plaintext)

    fun sealAndPublish(): LargeProtectedModelStagingPublishResult =
        coordinator.sealAndPublish(attempt)

    fun abort(): LargeProtectedModelStagingAbortResult = coordinator.abort(attempt)

    override fun toString(): String = "LargeProtectedModelStagingSession(attempt=$attempt)"
}

/**
 * Process-local staging ownership and publication coordinator.
 *
 * Backend callbacks execute outside the Core ownership lock. Every externally visible state advance is
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

    private data class PublishedEntry(
        val source: LargeProtectedModelStagedSource,
        var state: PublishedState = PublishedState.LIVE
    )

    private val lock = Any()
    private val nextGeneration = AtomicLong(initialGeneration)
    private var active: ActiveAttempt? = null
    private val published = mutableMapOf<LargeProtectedModelOpaqueArtifactId, PublishedEntry>()

    fun start(request: LargeProtectedModelStagingRequest): LargeProtectedModelStagingStartResult {
        validateRequest(request)?.let { return LargeProtectedModelStagingStartResult.Rejected(it) }

        val superseded: LargeProtectedModelWorkingArtifactHandle? = synchronized(lock) {
            val current = active
            if (current != null) {
                current.state = AttemptState.ABORTED
                active = null
                current.handle
            } else null
        }
        val supersededCleanup = superseded?.let { cleanup(it.artifactId) }
            ?: LargeProtectedModelStagingCleanupOutcome.NOT_REQUIRED

        val generationValue = nextGeneration.incrementAndGet()
        if (generationValue <= 0L) {
            return LargeProtectedModelStagingStartResult.Failed(
                LargeProtectedModelStagingFailure.PROVIDER_FAILED
            )
        }
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

        synchronized(lock) {
            // A concurrent start may have advanced generation while this backend prepare was in flight.
            if (nextGeneration.get() != generationValue || active != null) {
                cleanup(handle.artifactId)
                return LargeProtectedModelStagingStartResult.Rejected(
                    LargeProtectedModelStagingFailure.STALE_ATTEMPT
                )
            }
            active = ActiveAttempt(reference, request, handle)
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
        val entry = synchronized(lock) {
            val current = active
                ?: return LargeProtectedModelStagingAppendResult.Rejected(
                    LargeProtectedModelStagingFailure.ATTEMPT_UNAVAILABLE,
                    LargeProtectedModelStagingCleanupOutcome.NOT_REQUIRED
                )
            if (current.reference != attempt || current.state != AttemptState.OPEN) {
                return LargeProtectedModelStagingAppendResult.Rejected(
                    LargeProtectedModelStagingFailure.STALE_ATTEMPT,
                    LargeProtectedModelStagingCleanupOutcome.NOT_REQUIRED
                )
            }
            if (current.operationInProgress) {
                return abortRejectedLocked(
                    current,
                    LargeProtectedModelStagingFailure.STALE_ATTEMPT
                )
            }
            val validationFailure = validateAppend(current, segmentIndex, plaintext.size.toLong())
            if (validationFailure != null) {
                current.state = AttemptState.ABORTED
                active = null
                val handle = current.handle
                return@synchronized AppendPreparation.Abort(handle, validationFailure)
            }
            current.operationInProgress = true
            AppendPreparation.Ready(current.handle, current.reference, current.plaintextBytes)
        }

        if (entry is LargeProtectedModelStagingAppendResult) return entry
        if (entry is AppendPreparation.Abort) {
            val cleanup = cleanup(entry.handle.artifactId)
            return LargeProtectedModelStagingAppendResult.Rejected(entry.reason, cleanup)
        }
        entry as AppendPreparation.Ready

        val backendResult = try {
            backend.append(entry.handle, segmentIndex, plaintext)
        } catch (throwable: Throwable) {
            return failAppendAfterBackend(
                entry.reference,
                LargeProtectedModelStagingFailure.BACKEND_APPEND_FAILED,
                throwable
            )
        }

        return when (backendResult) {
            LargeProtectedModelStagingAppendBackendResult.Appended -> synchronized(lock) {
                val current = active
                if (current == null || current.reference != entry.reference ||
                    current.handle != entry.handle || current.state != AttemptState.OPEN
                ) {
                    LargeProtectedModelStagingAppendResult.Rejected(
                        LargeProtectedModelStagingFailure.STALE_ATTEMPT,
                        LargeProtectedModelStagingCleanupOutcome.NOT_REQUIRED
                    )
                } else {
                    val nextBytes = try {
                        Math.addExact(entry.previousBytes, plaintext.size.toLong())
                    } catch (_: ArithmeticException) {
                        current.state = AttemptState.ABORTED
                        active = null
                        return@synchronized LargeProtectedModelStagingAppendResult.Rejected(
                            LargeProtectedModelStagingFailure.AGGREGATE_SIZE_OVERFLOW,
                            cleanup(current.handle.artifactId)
                        )
                    }
                    current.plaintextBytes = nextBytes
                    current.nextSegmentIndex += 1
                    current.operationInProgress = false
                    LargeProtectedModelStagingAppendResult.Appended(
                        current.nextSegmentIndex,
                        current.plaintextBytes
                    )
                }
            }
            is LargeProtectedModelStagingAppendBackendResult.Rejected ->
                failAppendAfterBackend(
                    entry.reference,
                    LargeProtectedModelStagingFailure.BACKEND_APPEND_REJECTED,
                    null,
                    rejected = true
                )
            is LargeProtectedModelStagingAppendBackendResult.Failed ->
                failAppendAfterBackend(
                    entry.reference,
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
                ?: return LargeProtectedModelStagingPublishResult.Rejected(
                    LargeProtectedModelStagingFailure.ATTEMPT_UNAVAILABLE,
                    LargeProtectedModelStagingCleanupOutcome.NOT_REQUIRED
                )
            if (current.reference != attempt || current.state != AttemptState.OPEN ||
                current.operationInProgress
            ) {
                return LargeProtectedModelStagingPublishResult.Rejected(
                    LargeProtectedModelStagingFailure.STALE_ATTEMPT,
                    LargeProtectedModelStagingCleanupOutcome.NOT_REQUIRED
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

        if (preparation is SealPreparation.Abort) {
            return LargeProtectedModelStagingPublishResult.Rejected(
                preparation.reason,
                cleanup(preparation.handle.artifactId)
            )
        }
        preparation as SealPreparation.Ready

        val sealed = try {
            backend.seal(preparation.handle)
        } catch (throwable: Throwable) {
            return failSeal(preparation.reference, LargeProtectedModelStagingFailure.BACKEND_SEAL_FAILED, throwable)
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

        return synchronized(lock) {
            val current = active
            if (current == null || current.reference != preparation.reference ||
                current.handle != preparation.handle || current.state != AttemptState.SEALING
            ) {
                return@synchronized LargeProtectedModelStagingPublishResult.Rejected(
                    LargeProtectedModelStagingFailure.STALE_PUBLICATION,
                    cleanup(candidate.sourceId)
                )
            }
            if (published.containsKey(candidate.sourceId)) {
                current.state = AttemptState.ABORTED
                active = null
                return@synchronized LargeProtectedModelStagingPublishResult.Rejected(
                    LargeProtectedModelStagingFailure.SEALED_CANDIDATE_MISMATCH,
                    cleanup(candidate.sourceId)
                )
            }
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
            LargeProtectedModelStagingPublishResult.Published(ownership(entry))
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

    private fun ownership(entry: PublishedEntry): LargeProtectedModelStagedSourceOwnership =
        object : LargeProtectedModelStagedSourceOwnership {
            override val source: LargeProtectedModelStagedSource = entry.source
            override fun retire(): LargeProtectedModelStagingRetireResult = retireExact(entry)
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

    private fun validateRequest(request: LargeProtectedModelStagingRequest): LargeProtectedModelStagingFailure? {
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

    private fun cleanup(artifactId: LargeProtectedModelOpaqueArtifactId): LargeProtectedModelStagingCleanupOutcome {
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
                LargeProtectedModelStagingCleanupOutcome(LargeProtectedModelStagingCleanupStatus.DELETED)
            is LargeProtectedModelStagingDeleteResult.Rejected ->
                LargeProtectedModelStagingCleanupOutcome(LargeProtectedModelStagingCleanupStatus.REJECTED)
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

    private fun abortRejectedLocked(
        current: ActiveAttempt,
        reason: LargeProtectedModelStagingFailure
    ): LargeProtectedModelStagingAppendResult {
        current.state = AttemptState.ABORTED
        active = null
        return LargeProtectedModelStagingAppendResult.Rejected(
            reason,
            cleanup(current.handle.artifactId)
        )
    }

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
    }
}
