package pro.liliya.core.protectedmodel

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
    RETIRE_IN_USE,
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
