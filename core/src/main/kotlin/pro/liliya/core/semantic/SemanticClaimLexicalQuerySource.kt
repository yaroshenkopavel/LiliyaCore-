package pro.liliya.core.semantic

import java.util.TreeMap
import java.util.TreeSet
import kotlin.math.ln
import pro.liliya.core.retrieval.RetrievalCandidateId
import pro.liliya.core.retrieval.compareUtf8

class SemanticClaimLexicalQuerySource(
    private val repository: EncryptedPersistentSemanticClaimRepository,
    private val indexStore: EncryptedPersistentSemanticClaimLexicalIndexStore,
    private val policy: SemanticClaimLexicalPolicy = SemanticClaimLexicalPolicy()
) {
    fun query(text: String): SemanticClaimLexicalQueryResult {
        if (text.isBlank()) {
            return SemanticClaimLexicalQueryResult.Rejected(
                "semantic lexical query must not be blank"
            )
        }

        val tokenization = SemanticClaimLexicalTokenizer.tokenizeQuery(text)
        if (tokenization.tokens.isEmpty()) {
            return SemanticClaimLexicalQueryResult.Rejected(
                "semantic lexical query contains no searchable tokens"
            )
        }

        val queryTokens = tokenization.tokens
            .distinct()
            .sortedWith(::compareUtf8)

        val manifest = when (val loaded = indexStore.readManifest()) {
            SemanticClaimLexicalManifestLoadResult.Missing ->
                return fallback("semantic lexical index manifest is missing")
            is SemanticClaimLexicalManifestLoadResult.Loaded -> loaded.manifest
            SemanticClaimLexicalManifestLoadResult.Corrupt ->
                return fallback("semantic lexical index manifest is corrupt")
            is SemanticClaimLexicalManifestLoadResult.Incompatible ->
                return fallback(loaded.reason)
            is SemanticClaimLexicalManifestLoadResult.EncryptionUnavailable ->
                return fallback(
                    "semantic lexical index manifest encryption unavailable: " +
                        loaded.category
                )
            is SemanticClaimLexicalManifestLoadResult.Failed ->
                return SemanticClaimLexicalQueryResult.Failed(
                    loaded.reason,
                    loaded.throwable
                )
        }

        if (manifest.state != SemanticClaimLexicalIndexState.COMPLETE) {
            return fallback("semantic lexical index is incomplete")
        }
        if (
            manifest.policyVersion != policy.version ||
            manifest.tokenizerVersion != policy.tokenizerVersion ||
            manifest.postingPageEntries != policy.postingPageEntries ||
            manifest.bm25K1 != policy.bm25K1 ||
            manifest.bm25B != policy.bm25B
        ) {
            return fallback("semantic lexical index policy does not match query policy")
        }

        val sourceBefore = repository.sourceCheckpoint()
        if (sourceBefore != manifest.source) {
            return fallback("semantic lexical index source checkpoint is stale")
        }

        if (
            manifest.indexedDocumentCount > 0L &&
            manifest.averageDocumentLength <= 0.0
        ) {
            return fallback("semantic lexical corpus statistics are invalid")
        }

        val accumulators = TreeMap<RetrievalCandidateId, CandidateAccumulator> {
            left,
            right -> compareUtf8(left.value, right.value)
        }
        var postingEntriesScanned = 0
        var postingBudgetTruncated = false
        var candidateBudgetTruncated = false

        for (token in queryTokens) {
            val root = when (val loaded = indexStore.readRoot(token)) {
                SemanticClaimLexicalRootLoadResult.Missing -> continue
                is SemanticClaimLexicalRootLoadResult.Loaded -> loaded.root
                SemanticClaimLexicalRootLoadResult.Corrupt ->
                    return fallback("semantic lexical token root is corrupt")
                is SemanticClaimLexicalRootLoadResult.Incompatible ->
                    return fallback(loaded.reason)
                is SemanticClaimLexicalRootLoadResult.EncryptionUnavailable ->
                    return fallback(
                        "semantic lexical token root encryption unavailable: " +
                            loaded.category
                    )
                is SemanticClaimLexicalRootLoadResult.Failed ->
                    return SemanticClaimLexicalQueryResult.Failed(
                        loaded.reason,
                        loaded.throwable
                    )
            }

            if (root.buildEpoch != manifest.buildEpoch || root.token != token) {
                return fallback(
                    "semantic lexical token root does not match active build"
                )
            }
            if (
                manifest.indexedDocumentCount == 0L ||
                root.postingCount > manifest.indexedDocumentCount
            ) {
                return fallback("semantic lexical token root corpus count is invalid")
            }

            val expectedPages =
                ((root.postingCount - 1L) / manifest.postingPageEntries.toLong()) + 1L
            if (root.pageCount != expectedPages) {
                return fallback("semantic lexical token root page count is invalid")
            }

            val tokenBudget = minOf(
                root.postingCount,
                policy.maxPostingEntriesPerToken.toLong()
            )
            if (root.postingCount > tokenBudget) {
                postingBudgetTruncated = true
            }

            val idf = bm25Idf(
                manifest.indexedDocumentCount,
                root.postingCount
            ) ?: return fallback("semantic lexical BM25 document frequency is invalid")

            var scannedForToken = 0L
            var pageOrdinal = 0L
            while (scannedForToken < tokenBudget) {
                val page = when (val loaded = indexStore.readPage(token, pageOrdinal)) {
                    SemanticClaimLexicalPageLoadResult.Missing ->
                        return fallback("semantic lexical posting page is missing")
                    is SemanticClaimLexicalPageLoadResult.Loaded -> loaded.page
                    SemanticClaimLexicalPageLoadResult.Corrupt ->
                        return fallback("semantic lexical posting page is corrupt")
                    is SemanticClaimLexicalPageLoadResult.Incompatible ->
                        return fallback(loaded.reason)
                    is SemanticClaimLexicalPageLoadResult.EncryptionUnavailable ->
                        return fallback(
                            "semantic lexical posting page encryption unavailable: " +
                                loaded.category
                        )
                    is SemanticClaimLexicalPageLoadResult.Failed ->
                        return SemanticClaimLexicalQueryResult.Failed(
                            loaded.reason,
                            loaded.throwable
                        )
                }

                if (
                    page.buildEpoch != manifest.buildEpoch ||
                    page.token != token ||
                    page.ordinal != pageOrdinal
                ) {
                    return fallback(
                        "semantic lexical posting page does not match active build"
                    )
                }

                val pageStart = pageOrdinal * manifest.postingPageEntries.toLong()
                val expectedEntries = minOf(
                    manifest.postingPageEntries.toLong(),
                    root.postingCount - pageStart
                ).toInt()
                if (expectedEntries <= 0 || page.entries.size != expectedEntries) {
                    return fallback("semantic lexical posting page size is invalid")
                }

                for (posting in page.entries) {
                    if (scannedForToken >= tokenBudget) break
                    scannedForToken += 1L
                    postingEntriesScanned += 1

                    if (
                        posting.documentLength >
                            SemanticClaimLexicalTokenizer.MAX_DOCUMENT_TOKENS
                    ) {
                        return fallback(
                            "semantic lexical posting document length is invalid"
                        )
                    }

                    val candidateId = candidateId(posting.reference)
                    var accumulator = accumulators[candidateId]
                    if (accumulator == null) {
                        if (
                            accumulators.size >= policy.maxCandidateWorkingSet
                        ) {
                            candidateBudgetTruncated = true
                            continue
                        }
                        accumulator = CandidateAccumulator(posting.reference)
                        accumulators[candidateId] = accumulator
                    } else if (accumulator.reference != posting.reference) {
                        return fallback(
                            "semantic lexical candidate identity collision"
                        )
                    }

                    if (accumulator.postings.containsKey(token)) {
                        return fallback(
                            "semantic lexical posting duplicates token/candidate"
                        )
                    }

                    val score = bm25TermScore(
                        idf = idf,
                        posting = posting,
                        averageDocumentLength = manifest.averageDocumentLength,
                        k1 = manifest.bm25K1,
                        b = manifest.bm25B
                    )
                    if (score == null) {
                        return fallback("semantic lexical BM25 score is invalid")
                    }
                    accumulator.score += score
                    if (!accumulator.score.isFinite() || accumulator.score <= 0.0) {
                        return fallback("semantic lexical accumulated score is invalid")
                    }
                    accumulator.matchedTokens += token
                    accumulator.postings[token] = posting
                }

                pageOrdinal += 1L
            }
        }

        val ranked = accumulators.map { (id, accumulator) ->
            PendingCandidate(
                reference = accumulator.reference,
                candidateId = id,
                score = accumulator.score,
                matchedTokens = accumulator.matchedTokens.toList(),
                postings = accumulator.postings.toMap()
            )
        }.sortedWith { left, right ->
            val scoreCompare = right.score.compareTo(left.score)
            if (scoreCompare != 0) scoreCompare
            else compareUtf8(left.candidateId.value, right.candidateId.value)
        }.take(policy.maxReturnedCandidates)

        val validated = ArrayList<SemanticClaimLexicalCandidate>(ranked.size)
        for (candidate in ranked) {
            when (val result = revalidate(candidate)) {
                is Revalidation.Valid -> validated += result.candidate
                is Revalidation.Fallback -> return fallback(result.reason)
                is Revalidation.Failed ->
                    return SemanticClaimLexicalQueryResult.Failed(
                        result.reason,
                        result.throwable
                    )
            }
        }

        val sourceAfter = repository.sourceCheckpoint()
        if (sourceAfter != manifest.source) {
            return fallback(
                "canonical semantic claim source changed during lexical query"
            )
        }

        return SemanticClaimLexicalQueryResult.Ranked(
            candidates = validated,
            audit = SemanticClaimLexicalAudit(
                indexVersion = manifest.version,
                buildEpoch = manifest.buildEpoch,
                policyVersion = manifest.policyVersion,
                tokenizerVersion = manifest.tokenizerVersion,
                source = manifest.source,
                queryTokenCount = tokenization.tokens.size,
                queryTokenTruncated = tokenization.truncated,
                postingEntriesScanned = postingEntriesScanned,
                candidateWorkingSetSize = accumulators.size,
                postingBudgetTruncated = postingBudgetTruncated,
                candidateBudgetTruncated = candidateBudgetTruncated,
                returnedCandidates = validated.size
            )
        )
    }

    private fun revalidate(candidate: PendingCandidate): Revalidation {
        val canonical = when (val loaded = repository.readExact(candidate.reference)) {
            SemanticClaimReadResult.Missing ->
                return Revalidation.Fallback(
                    "canonical semantic claim referenced by lexical index is missing"
                )
            is SemanticClaimReadResult.Found -> loaded.record
            SemanticClaimReadResult.Corrupt ->
                return Revalidation.Fallback(
                    "canonical semantic claim referenced by lexical index is corrupt"
                )
            is SemanticClaimReadResult.Incompatible ->
                return Revalidation.Fallback(loaded.reason)
            is SemanticClaimReadResult.EncryptionUnavailable ->
                return Revalidation.Fallback(
                    "canonical semantic claim encryption unavailable: " +
                        loaded.category
                )
            is SemanticClaimReadResult.Failed ->
                return Revalidation.Failed(
                    loaded.reason,
                    loaded.throwable
                )
        }

        if (
            canonical.id != candidate.reference.claimId ||
            canonical.version != candidate.reference.version
        ) {
            return Revalidation.Fallback(
                "semantic lexical index reference does not match canonical claim"
            )
        }

        val text = (canonical.objectValue as? SemanticClaimObject.Text)?.value
            ?: return Revalidation.Fallback(
                "semantic lexical index points to non-text canonical claim"
            )
        val tokenization = SemanticClaimLexicalTokenizer.tokenizeDocument(text)
        if (tokenization.tokens.isEmpty()) {
            return Revalidation.Fallback(
                "semantic lexical index points to empty canonical text tokens"
            )
        }

        val frequencies = HashMap<String, Int>()
        tokenization.tokens.forEach { token ->
            frequencies[token] = (frequencies[token] ?: 0) + 1
        }
        candidate.postings.forEach { (token, posting) ->
            if (
                posting.reference != candidate.reference ||
                posting.documentLength != tokenization.tokens.size ||
                frequencies[token] != posting.termFrequency
            ) {
                return Revalidation.Fallback(
                    "semantic lexical posting metadata does not match canonical text"
                )
            }
        }

        if (candidate.candidateId != candidateId(candidate.reference)) {
            return Revalidation.Fallback(
                "semantic lexical candidate identity does not match canonical reference"
            )
        }

        return Revalidation.Valid(
            SemanticClaimLexicalCandidate(
                reference = candidate.reference,
                candidateId = candidate.candidateId,
                score = candidate.score,
                matchedTokens = candidate.matchedTokens
            )
        )
    }

    private fun bm25Idf(documentCount: Long, documentFrequency: Long): Double? {
        if (
            documentCount <= 0L ||
            documentFrequency <= 0L ||
            documentFrequency > documentCount
        ) return null
        val numerator =
            documentCount.toDouble() - documentFrequency.toDouble() + 0.5
        val denominator = documentFrequency.toDouble() + 0.5
        val value = ln(1.0 + numerator / denominator)
        return value.takeIf { it.isFinite() && it > 0.0 }
    }

    private fun bm25TermScore(
        idf: Double,
        posting: SemanticClaimLexicalPosting,
        averageDocumentLength: Double,
        k1: Double,
        b: Double
    ): Double? {
        if (averageDocumentLength <= 0.0) return null
        val tf = posting.termFrequency.toDouble()
        val normalization =
            1.0 - b + b * posting.documentLength.toDouble() / averageDocumentLength
        val denominator = tf + k1 * normalization
        if (!denominator.isFinite() || denominator <= 0.0) return null
        val score = idf * ((tf * (k1 + 1.0)) / denominator)
        return score.takeIf { it.isFinite() && it > 0.0 }
    }

    private fun candidateId(
        reference: SemanticClaimVersionReference
    ): RetrievalCandidateId =
        SemanticClaimRetrievalCandidateIdentity.encode(reference)

    private fun fallback(reason: String) =
        SemanticClaimLexicalQueryResult.FallbackRequired(reason)

    private data class CandidateAccumulator(
        val reference: SemanticClaimVersionReference,
        var score: Double = 0.0,
        val matchedTokens: TreeSet<String> =
            TreeSet { left, right -> compareUtf8(left, right) },
        val postings: TreeMap<String, SemanticClaimLexicalPosting> =
            TreeMap { left, right -> compareUtf8(left, right) }
    )

    private data class PendingCandidate(
        val reference: SemanticClaimVersionReference,
        val candidateId: RetrievalCandidateId,
        val score: Double,
        val matchedTokens: List<String>,
        val postings: Map<String, SemanticClaimLexicalPosting>
    )

    private sealed interface Revalidation {
        data class Valid(
            val candidate: SemanticClaimLexicalCandidate
        ) : Revalidation

        data class Fallback(val reason: String) : Revalidation

        data class Failed(
            val reason: String,
            val throwable: Throwable? = null
        ) : Revalidation
    }
}
