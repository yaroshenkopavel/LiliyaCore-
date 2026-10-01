package pro.liliya.core.semantic

import java.time.Instant
import pro.liliya.core.retrieval.HybridFusedCandidate
import pro.liliya.core.retrieval.RetrievalCandidateId

enum class SemanticClaimAdjacencyIndexState {
    INCOMPLETE,
    COMPLETE
}

enum class SemanticClaimAdjacencyDirection {
    OUTGOING,
    INCOMING,
    SYMMETRIC
}

data class SemanticClaimAdjacencyPolicy(
    val version: Int = CURRENT_VERSION,
    val pageEntries: Int = DEFAULT_PAGE_ENTRIES,
    val maxSeeds: Int = DEFAULT_MAX_SEEDS,
    val maxRelationsPerSeed: Int = DEFAULT_MAX_RELATIONS_PER_SEED,
    val maxWorkingSet: Int = DEFAULT_MAX_WORKING_SET,
    val maxReturnedCandidates: Int = DEFAULT_MAX_RETURNED_CANDIDATES
) {
    init {
        require(version == CURRENT_VERSION)
        require(pageEntries in 1..MAX_PAGE_ENTRIES)
        require(maxSeeds in 1..MAX_SEEDS)
        require(maxRelationsPerSeed in pageEntries..MAX_RELATIONS_PER_SEED)
        require(maxWorkingSet in maxReturnedCandidates..MAX_WORKING_SET)
        require(maxReturnedCandidates in 1..MAX_RETURNED_CANDIDATES)
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val DEFAULT_PAGE_ENTRIES = 128
        const val MAX_PAGE_ENTRIES = 128
        const val DEFAULT_MAX_SEEDS = 128
        const val MAX_SEEDS = 128
        const val DEFAULT_MAX_RELATIONS_PER_SEED = 128
        const val MAX_RELATIONS_PER_SEED = 128
        const val DEFAULT_MAX_WORKING_SET = 2048
        const val MAX_WORKING_SET = 2048
        const val DEFAULT_MAX_RETURNED_CANDIDATES = 128
        const val MAX_RETURNED_CANDIDATES = 128
    }
}

data class SemanticClaimAdjacencyManifest(
    val version: Int = CURRENT_VERSION,
    val buildEpoch: String,
    val source: SemanticClaimSourceCheckpoint,
    val state: SemanticClaimAdjacencyIndexState,
    val policyVersion: Int,
    val pageEntries: Int,
    val indexedRelationCount: Long,
    val adjacencyEntryCount: Long
) {
    init {
        require(version == CURRENT_VERSION)
        require(buildEpoch.isNotBlank() && buildEpoch.length <= 64)
        require(policyVersion == SemanticClaimAdjacencyPolicy.CURRENT_VERSION)
        require(pageEntries in 1..SemanticClaimAdjacencyPolicy.MAX_PAGE_ENTRIES)
        require(indexedRelationCount >= 0L)
        require(adjacencyEntryCount >= 0L)
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

data class SemanticClaimAdjacencyRoot(
    val version: Int = CURRENT_VERSION,
    val buildEpoch: String,
    val reference: SemanticClaimVersionReference,
    val pageCount: Long,
    val entryCount: Long
) {
    init {
        require(version == CURRENT_VERSION)
        require(buildEpoch.isNotBlank())
        require(pageCount > 0L)
        require(entryCount > 0L)
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

data class SemanticClaimAdjacencyEntry(
    val neighbor: SemanticClaimVersionReference,
    val relationType: SemanticClaimRelationType,
    val direction: SemanticClaimAdjacencyDirection,
    val recordedAt: Instant
)

data class SemanticClaimAdjacencyPage(
    val version: Int = CURRENT_VERSION,
    val buildEpoch: String,
    val reference: SemanticClaimVersionReference,
    val ordinal: Long,
    val entries: List<SemanticClaimAdjacencyEntry>
) {
    init {
        require(version == CURRENT_VERSION)
        require(buildEpoch.isNotBlank())
        require(ordinal >= 0L)
        require(entries.isNotEmpty())
        require(entries.size <= SemanticClaimAdjacencyPolicy.MAX_PAGE_ENTRIES)
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

data class SemanticClaimAdjacencyCandidate(
    val reference: SemanticClaimVersionReference,
    val candidateId: RetrievalCandidateId,
    val relationType: SemanticClaimRelationType,
    val direction: SemanticClaimAdjacencyDirection,
    val seedRank: Int,
    val relationOrdinal: Int
) {
    init {
        require(seedRank > 0)
        require(relationOrdinal > 0)
    }
}

enum class SemanticClaimAdjacencyQueryStatus {
    EXPANDED,
    REJECTED,
    FALLBACK_REQUIRED,
    FAILED
}

data class SemanticClaimAdjacencyAudit(
    val source: SemanticClaimSourceCheckpoint?,
    val seedCount: Int,
    val relationEntriesScanned: Int,
    val workingSetSize: Int,
    val duplicateSuppressed: Int,
    val returnedCount: Int,
    val truncated: Boolean,
    val status: SemanticClaimAdjacencyQueryStatus,
    val advisoryOnly: Boolean = true
) {
    init {
        require(seedCount >= 0)
        require(relationEntriesScanned >= 0)
        require(workingSetSize >= 0)
        require(duplicateSuppressed >= 0)
        require(returnedCount >= 0)
        require(advisoryOnly)
    }
}

sealed interface SemanticClaimAdjacencyQueryResult {
    data class Expanded(
        val candidates: List<SemanticClaimAdjacencyCandidate>,
        val audit: SemanticClaimAdjacencyAudit
    ) : SemanticClaimAdjacencyQueryResult

    data class FallbackRequired(
        val reason: String,
        val audit: SemanticClaimAdjacencyAudit
    ) : SemanticClaimAdjacencyQueryResult {
        init { require(reason.isNotBlank()) }
    }

    data class Rejected(
        val reason: String,
        val audit: SemanticClaimAdjacencyAudit
    ) : SemanticClaimAdjacencyQueryResult {
        init { require(reason.isNotBlank()) }
    }

    data class Failed(
        val reason: String,
        val throwable: Throwable? = null,
        val audit: SemanticClaimAdjacencyAudit
    ) : SemanticClaimAdjacencyQueryResult {
        init { require(reason.isNotBlank()) }
    }
}

sealed interface SemanticClaimAdjacencyRebuildResult {
    data class Complete(
        val source: SemanticClaimSourceCheckpoint,
        val indexedRelations: Long,
        val adjacencyEntries: Long,
        val buildEpoch: String
    ) : SemanticClaimAdjacencyRebuildResult

    data class SourceDrift(
        val started: SemanticClaimSourceCheckpoint,
        val ended: SemanticClaimSourceCheckpoint
    ) : SemanticClaimAdjacencyRebuildResult

    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : SemanticClaimAdjacencyRebuildResult
}