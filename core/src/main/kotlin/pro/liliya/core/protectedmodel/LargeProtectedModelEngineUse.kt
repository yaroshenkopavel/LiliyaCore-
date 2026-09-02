package pro.liliya.core.protectedmodel

/**
 * Core-neutral, path-free capability proving an active engine-use lease for one exact
 * published staged source. Construction remains inside Core ownership code so callers
 * cannot mint a capability from copied source metadata.
 */
class LargeProtectedModelEngineSourceCapability internal constructor(
    val backendId: LargeProtectedModelStagingBackendId,
    val model: ProtectedModelReference,
    val stagingGeneration: LargeProtectedModelStagingGeneration,
    val plaintextBytes: Long,
    val profile: LargeProtectedModelPayloadProfile,
    val durabilityLevel: LargeProtectedModelStagingDurabilityLevel,
    internal val sourceIdentity: LargeProtectedModelStagedSource,
    val sourceId: LargeProtectedModelOpaqueArtifactId = sourceIdentity.sourceId
) {
    init {
        require(plaintextBytes > 0L) { "engine source plaintext bytes must be positive" }
        require(sourceId === sourceIdentity.sourceId || sourceId == sourceIdentity.sourceId) {
            "engine source identity mismatch"
        }
    }

    override fun toString(): String =
        "LargeProtectedModelEngineSourceCapability(backendId=$backendId, sourceId=<redacted>, model=$model, " +
            "stagingGeneration=${stagingGeneration.value}, plaintextBytes=$plaintextBytes, " +
            "profile=$profile, durabilityLevel=$durabilityLevel)"
}

enum class LargeProtectedModelEngineUseFailure {
    SOURCE_STALE,
    SOURCE_RETIRING,
    SOURCE_ALREADY_IN_USE,
    LEASE_STALE
}

sealed interface LargeProtectedModelEngineUseAcquireResult {
    data class Acquired(
        val lease: LargeProtectedModelEngineUseLease
    ) : LargeProtectedModelEngineUseAcquireResult

    data class Rejected(
        val reason: LargeProtectedModelEngineUseFailure
    ) : LargeProtectedModelEngineUseAcquireResult
}

sealed interface LargeProtectedModelEngineUseReleaseResult {
    data object Released : LargeProtectedModelEngineUseReleaseResult

    data class Rejected(
        val reason: LargeProtectedModelEngineUseFailure
    ) : LargeProtectedModelEngineUseReleaseResult
}

interface LargeProtectedModelEngineUseLease {
    val source: LargeProtectedModelEngineSourceCapability

    fun release(): LargeProtectedModelEngineUseReleaseResult
}
