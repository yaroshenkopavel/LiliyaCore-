package pro.liliya.core.semantic

import pro.liliya.core.retrieval.RetrievalCandidateId
import pro.liliya.core.retrieval.RetrievalChannelId
import pro.liliya.core.retrieval.RetrievalChannelRequirement
import pro.liliya.core.retrieval.RetrievalChannelResult
import pro.liliya.core.retrieval.RankedRetrievalCandidate

enum class SemanticClaimLexicalIndexState {
    INCOMPLETE,
    COMPLETE
}

data class SemanticClaimLexicalPolicy(
    val version: Int = CURRENT_VERSION,
    val tokenizerVersion: Int = SemanticClaimLexicalTokenizer.VERSION,
    val postingPageEntries: Int = DEFAULT_POSTING_PAGE_ENTRIES,
    val maxPostingEntriesPerToken: Int = DEFAULT_MAX_POSTING_ENTRIES_PER_TOKEN,
    val maxCandidateWorkingSet: Int = DEFAULT_MAX_CANDIDATE_WORKING_SET,
    val maxReturnedCandidates: Int = DEFAULT_MAX_RETURNED_CANDIDATES,
    val bm25K1: Double = DEFAULT_BM25_K1,
    val bm25B: Double = DEFAULT_BM25_B
) {
    init {
        require(version == CURRENT_VERSION)
        require(tokenizerVersion == SemanticClaimLexicalTokenizer.VERSION)
        require(postingPageEntries in 1..MAX_POSTING_PAGE_ENTRIES)
        require(maxPostingEntriesPerToken in postingPageEntries..MAX_POSTING_SCAN)
        require(maxCandidateWorkingSet in maxReturnedCandidates..MAX_CANDIDATE_WORKING_SET)
        require(maxReturnedCandidates in 1..MAX_RETURNED_CANDIDATES)
        require(bm25K1.isFinite() && bm25K1 > 0.0)
        require(bm25B.isFinite() && bm25B in 0.0..1.0)
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val DEFAULT_POSTING_PAGE_ENTRIES = 128
        const val MAX_POSTING_PAGE_ENTRIES = 128
        const val DEFAULT_MAX_POSTING_ENTRIES_PER_TOKEN = 1024
        const val MAX_POSTING_SCAN = 4096
        const val DEFAULT_MAX_CANDIDATE_WORKING_SET = 2048
        const val MAX_CANDIDATE_WORKING_SET = 8192
        const val DEFAULT_MAX_RETURNED_CANDIDATES = 128
        const val MAX_RETURNED_CANDIDATES = 128
        const val DEFAULT_BM25_K1 = 1.2
        const val DEFAULT_BM25_B = 0.75
    }
}

data class SemanticClaimLexicalManifest(
    val version: Int = CURRENT_VERSION,
    val buildEpoch: String,
    val source: SemanticClaimSourceCheckpoint,
    val state: SemanticClaimLexicalIndexState,
    val policyVersion: Int,
    val tokenizerVersion: Int,
    val postingPageEntries: Int,
    val bm25K1: Double,
    val bm25B: Double,
    val indexedDocumentCount: Long,
    val totalIndexedTokenCount: Long,
    val truncatedDocumentCount: Long
) {
    init {
        require(version == CURRENT_VERSION)
        require(buildEpoch.isNotBlank() && buildEpoch.length <= MAX_EPOCH_LENGTH)
        require(policyVersion == SemanticClaimLexicalPolicy.CURRENT_VERSION)
        require(tokenizerVersion == SemanticClaimLexicalTokenizer.VERSION)
        require(postingPageEntries in 1..SemanticClaimLexicalPolicy.MAX_POSTING_PAGE_ENTRIES)
        require(bm25K1.isFinite() && bm25K1 > 0.0)
        require(bm25B.isFinite() && bm25B in 0.0..1.0)
        require(indexedDocumentCount >= 0L)
        require(totalIndexedTokenCount >= 0L)
        require(truncatedDocumentCount in 0L..indexedDocumentCount)
        require(indexedDocumentCount > 0L || totalIndexedTokenCount == 0L)
    }

    val averageDocumentLength: Double
        get() = if (indexedDocumentCount == 0L) 0.0
        else totalIndexedTokenCount.toDouble() / indexedDocumentCount.toDouble()

    companion object {
        const val CURRENT_VERSION = 1
        const val MAX_EPOCH_LENGTH = 64
    }
}

data class SemanticClaimLexicalTokenRoot(
    val version: Int = CURRENT_VERSION,
    val buildEpoch: String,
    val token: String,
    val pageCount: Long,
    val postingCount: Long
) {
    init {
        require(version == CURRENT_VERSION)
        require(buildEpoch.isNotBlank())
        require(token.isNotBlank())
        require(pageCount > 0L)
        require(postingCount > 0L)
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

data class SemanticClaimLexicalPosting(
    val reference: SemanticClaimVersionReference,
    val termFrequency: Int,
    val documentLength: Int
) {
    init {
        require(termFrequency > 0)
        require(documentLength > 0)
        require(termFrequency <= documentLength)
    }
}

data class SemanticClaimLexicalPostingPage(
    val version: Int = CURRENT_VERSION,
    val buildEpoch: String,
    val token: String,
    val ordinal: Long,
    val entries: List<SemanticClaimLexicalPosting>
) {
    init {
        require(version == CURRENT_VERSION)
        require(buildEpoch.isNotBlank())
        require(token.isNotBlank())
        require(ordinal >= 0L)
        require(entries.isNotEmpty())
        require(entries.size <= SemanticClaimLexicalPolicy.MAX_POSTING_PAGE_ENTRIES)
        require(entries.map { it.reference }.distinct().size == entries.size)
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

data class SemanticClaimLexicalCandidate(
    val reference: SemanticClaimVersionReference,
    val candidateId: RetrievalCandidateId,
    val score: Double,
    val matchedTokens: List<String>
) {
    init {
        require(score.isFinite() && score > 0.0)
        require(matchedTokens.isNotEmpty())
        require(matchedTokens.distinct().size == matchedTokens.size)
    }
}

data class SemanticClaimLexicalAudit(
    val policyVersion: Int,
    val tokenizerVersion: Int,
    val source: SemanticClaimSourceCheckpoint,
    val queryTokenCount: Int,
    val postingEntriesScanned: Int,
    val candidateWorkingSetSize: Int,
    val postingBudgetTruncated: Boolean,
    val candidateBudgetTruncated: Boolean,
    val returnedCandidates: Int,
    val advisoryOnly: Boolean = true
) {
    init {
        require(policyVersion == SemanticClaimLexicalPolicy.CURRENT_VERSION)
        require(tokenizerVersion == SemanticClaimLexicalTokenizer.VERSION)
        require(queryTokenCount in 1..SemanticClaimLexicalTokenizer.MAX_QUERY_TOKENS)
        require(postingEntriesScanned >= 0)
        require(candidateWorkingSetSize >= 0)
        require(returnedCandidates >= 0)
        require(advisoryOnly)
    }
}

sealed interface SemanticClaimLexicalQueryResult {
    data class Ranked(
        val candidates: List<SemanticClaimLexicalCandidate>,
        val audit: SemanticClaimLexicalAudit
    ) : SemanticClaimLexicalQueryResult

    data class FallbackRequired(val reason: String) : SemanticClaimLexicalQueryResult {
        init { require(reason.isNotBlank()) }
    }

    data class Rejected(val reason: String) : SemanticClaimLexicalQueryResult {
        init { require(reason.isNotBlank()) }
    }

    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : SemanticClaimLexicalQueryResult {
        init { require(reason.isNotBlank()) }
    }
}

sealed interface SemanticClaimLexicalRebuildResult {
    data class Complete(
        val source: SemanticClaimSourceCheckpoint,
        val indexedDocuments: Long,
        val totalIndexedTokens: Long,
        val truncatedDocuments: Long,
        val buildEpoch: String
    ) : SemanticClaimLexicalRebuildResult

    data class SourceDrift(
        val started: SemanticClaimSourceCheckpoint,
        val ended: SemanticClaimSourceCheckpoint
    ) : SemanticClaimLexicalRebuildResult

    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : SemanticClaimLexicalRebuildResult
}

fun SemanticClaimLexicalQueryResult.toRetrievalChannelResult(
    requirement: RetrievalChannelRequirement,
    channelId: RetrievalChannelId = RetrievalChannelId("semantic-lexical-v1")
): RetrievalChannelResult =
    when (this) {
        is SemanticClaimLexicalQueryResult.Ranked ->
            RetrievalChannelResult.Ranked(
                channelId = channelId,
                requirement = requirement,
                candidates = candidates.map { RankedRetrievalCandidate(it.candidateId) }
            )
        is SemanticClaimLexicalQueryResult.FallbackRequired ->
            RetrievalChannelResult.Unavailable(channelId, requirement, reason)
        is SemanticClaimLexicalQueryResult.Rejected ->
            RetrievalChannelResult.Failed(channelId, requirement, reason)
        is SemanticClaimLexicalQueryResult.Failed ->
            RetrievalChannelResult.Failed(channelId, requirement, reason)
    }
