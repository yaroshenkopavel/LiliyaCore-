package pro.liliya.core.semantic

enum class SemanticClaimQueryIndexState {
    INCOMPLETE,
    COMPLETE
}

data class SemanticClaimQueryIndexManifest(
    val version: Int = CURRENT_VERSION,
    val buildEpoch: String,
    val source: SemanticClaimSourceCheckpoint,
    val state: SemanticClaimQueryIndexState
) {
    init {
        require(version == CURRENT_VERSION)
        require(buildEpoch.isNotBlank() && buildEpoch.length <= MAX_EPOCH_LENGTH)
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val MAX_EPOCH_LENGTH = 64
    }
}

data class SemanticClaimQueryGroupRoot(
    val version: Int = CURRENT_VERSION,
    val buildEpoch: String,
    val conflictGroupId: SemanticClaimConflictGroupId,
    val pageCount: Long,
    val entryCount: Long
) {
    init {
        require(version == CURRENT_VERSION)
        require(buildEpoch.isNotBlank() && buildEpoch.length <=
            SemanticClaimQueryIndexManifest.MAX_EPOCH_LENGTH)
        require(pageCount > 0L)
        require(entryCount > 0L)
        require(pageCount == ((entryCount - 1L) / SemanticClaimQueryPage.MAX_ENTRIES) + 1L)
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

data class SemanticClaimQueryPage(
    val version: Int = CURRENT_VERSION,
    val buildEpoch: String,
    val conflictGroupId: SemanticClaimConflictGroupId,
    val ordinal: Long,
    val entries: List<SemanticClaimVersionReference>
) {
    init {
        require(version == CURRENT_VERSION)
        require(buildEpoch.isNotBlank() && buildEpoch.length <=
            SemanticClaimQueryIndexManifest.MAX_EPOCH_LENGTH)
        require(ordinal >= 0L)
        require(entries.isNotEmpty())
        require(entries.size <= MAX_ENTRIES)
        require(entries.distinct().size == entries.size)
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val MAX_ENTRIES = 128
    }
}

data class SemanticClaimQueryCursor(
    val pageOrdinal: Long,
    val offset: Int
) {
    init {
        require(pageOrdinal >= 0L)
        require(offset in 0 until SemanticClaimQueryPage.MAX_ENTRIES)
    }
}

data class SemanticClaimQueryAudit(
    val source: SemanticClaimSourceCheckpoint,
    val conflictGroupId: SemanticClaimConflictGroupId,
    val scannedIndexEntries: Int,
    val returnedCandidates: Int
)

sealed interface SemanticClaimQueryResult {
    data class Candidates(
        val records: List<SemanticClaimRecord>,
        val nextCursor: SemanticClaimQueryCursor?,
        val audit: SemanticClaimQueryAudit
    ) : SemanticClaimQueryResult

    data class FallbackRequired(val reason: String) : SemanticClaimQueryResult
    data class Rejected(val reason: String) : SemanticClaimQueryResult
    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : SemanticClaimQueryResult
}

sealed interface SemanticClaimQueryIndexRebuildResult {
    data class Complete(
        val source: SemanticClaimSourceCheckpoint,
        val indexedClaims: Long,
        val buildEpoch: String
    ) : SemanticClaimQueryIndexRebuildResult

    data class SourceDrift(
        val started: SemanticClaimSourceCheckpoint,
        val ended: SemanticClaimSourceCheckpoint
    ) : SemanticClaimQueryIndexRebuildResult

    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : SemanticClaimQueryIndexRebuildResult
}
