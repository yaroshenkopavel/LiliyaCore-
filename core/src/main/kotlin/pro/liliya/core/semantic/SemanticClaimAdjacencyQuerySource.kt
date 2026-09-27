package pro.liliya.core.semantic

import java.util.TreeMap
import pro.liliya.core.retrieval.HybridFusedCandidate
import pro.liliya.core.retrieval.compareUtf8

internal interface SemanticClaimAdjacencyCanonicalReader {
    fun sourceCheckpoint(): SemanticClaimSourceCheckpoint
    fun readExact(reference: SemanticClaimVersionReference): SemanticClaimReadResult
}

internal interface SemanticClaimAdjacencyIndexReader {
    fun readManifest(): SemanticClaimAdjacencyManifestLoadResult
    fun readRoot(reference: SemanticClaimVersionReference): SemanticClaimAdjacencyRootLoadResult
    fun readPage(
        reference: SemanticClaimVersionReference,
        ordinal: Long
    ): SemanticClaimAdjacencyPageLoadResult
}

private class RepositorySemanticClaimAdjacencyCanonicalReader(
    private val repository: EncryptedPersistentSemanticClaimRepository
) : SemanticClaimAdjacencyCanonicalReader {
    override fun sourceCheckpoint(): SemanticClaimSourceCheckpoint =
        repository.sourceCheckpoint()

    override fun readExact(reference: SemanticClaimVersionReference): SemanticClaimReadResult =
        repository.readExact(reference)
}

private class StoreSemanticClaimAdjacencyIndexReader(
    private val store: EncryptedPersistentSemanticClaimAdjacencyIndexStore
) : SemanticClaimAdjacencyIndexReader {
    override fun readManifest(): SemanticClaimAdjacencyManifestLoadResult =
        store.readManifest()

    override fun readRoot(
        reference: SemanticClaimVersionReference
    ): SemanticClaimAdjacencyRootLoadResult = store.readRoot(reference)

    override fun readPage(
        reference: SemanticClaimVersionReference,
        ordinal: Long
    ): SemanticClaimAdjacencyPageLoadResult = store.readPage(reference, ordinal)
}

class SemanticClaimAdjacencyQuerySource internal constructor(
    private val canonical: SemanticClaimAdjacencyCanonicalReader,
    private val index: SemanticClaimAdjacencyIndexReader,
    private val policy: SemanticClaimAdjacencyPolicy = SemanticClaimAdjacencyPolicy()
) {
    constructor(
        repository: EncryptedPersistentSemanticClaimRepository,
        indexStore: EncryptedPersistentSemanticClaimAdjacencyIndexStore,
        policy: SemanticClaimAdjacencyPolicy = SemanticClaimAdjacencyPolicy()
    ) : this(
        RepositorySemanticClaimAdjacencyCanonicalReader(repository),
        StoreSemanticClaimAdjacencyIndexReader(indexStore),
        policy
    )
    fun expand(
        seeds: List<HybridFusedCandidate>
    ): SemanticClaimAdjacencyQueryResult {
        var source: SemanticClaimSourceCheckpoint? = null
        var scanned = 0
        var workingSetSize = 0
        var duplicateSuppressed = 0
        var returnedCount = 0
        var truncated = false

        fun audit(status: SemanticClaimAdjacencyQueryStatus) =
            SemanticClaimAdjacencyAudit(
                source = source,
                seedCount = seeds.size,
                relationEntriesScanned = scanned,
                workingSetSize = workingSetSize,
                duplicateSuppressed = duplicateSuppressed,
                returnedCount = returnedCount,
                truncated = truncated,
                status = status
            )

        fun rejected(reason: String) =
            SemanticClaimAdjacencyQueryResult.Rejected(
                reason,
                audit(SemanticClaimAdjacencyQueryStatus.REJECTED)
            )

        fun fallback(reason: String) =
            SemanticClaimAdjacencyQueryResult.FallbackRequired(
                reason,
                audit(SemanticClaimAdjacencyQueryStatus.FALLBACK_REQUIRED)
            )

        fun failed(reason: String, throwable: Throwable? = null) =
            SemanticClaimAdjacencyQueryResult.Failed(
                reason,
                throwable,
                audit(SemanticClaimAdjacencyQueryStatus.FAILED)
            )

        if (seeds.size > policy.maxSeeds) {
            return rejected("semantic adjacency seed budget exceeded")
        }
        if (seeds.map { it.id }.distinct().size != seeds.size) {
            return rejected("semantic adjacency seeds contain duplicate ids")
        }

        val sourceBefore = canonical.sourceCheckpoint()
        source = sourceBefore
        val manifest = when (val loaded = index.readManifest()) {
            SemanticClaimAdjacencyManifestLoadResult.Missing ->
                return fallback("semantic adjacency manifest is missing")
            is SemanticClaimAdjacencyManifestLoadResult.Loaded -> loaded.manifest
            SemanticClaimAdjacencyManifestLoadResult.Corrupt ->
                return fallback("semantic adjacency manifest is corrupt")
            is SemanticClaimAdjacencyManifestLoadResult.Incompatible ->
                return fallback(loaded.reason)
            is SemanticClaimAdjacencyManifestLoadResult.EncryptionUnavailable ->
                return fallback("semantic adjacency manifest encryption unavailable: " + loaded.category)
            is SemanticClaimAdjacencyManifestLoadResult.Failed ->
                return failed(loaded.reason, loaded.throwable)
        }
        if (manifest.state != SemanticClaimAdjacencyIndexState.COMPLETE) {
            return fallback("semantic adjacency index is incomplete")
        }
        if (
            manifest.source != sourceBefore ||
            manifest.policyVersion != policy.version ||
            manifest.pageEntries != policy.pageEntries
        ) {
            return fallback("semantic adjacency index is stale or incompatible")
        }

        val seedIds = seeds.mapTo(HashSet(seeds.size)) { it.id }
        val working = TreeMap<String, SemanticClaimAdjacencyCandidate> { left, right ->
            compareUtf8(left, right)
        }

        seeds.forEachIndexed { seedIndex, seed ->
            val seedReference = SemanticClaimRetrievalCandidateIdentity.decode(seed.id)
                ?: return rejected(
                    "semantic adjacency seed id is not a valid Semantic Claim identity"
                )

            val root = when (val loaded = index.readRoot(seedReference)) {
                SemanticClaimAdjacencyRootLoadResult.Missing -> return@forEachIndexed
                is SemanticClaimAdjacencyRootLoadResult.Loaded -> loaded.root
                SemanticClaimAdjacencyRootLoadResult.Corrupt ->
                    return fallback("semantic adjacency root is corrupt")
                is SemanticClaimAdjacencyRootLoadResult.Incompatible ->
                    return fallback(loaded.reason)
                is SemanticClaimAdjacencyRootLoadResult.EncryptionUnavailable ->
                    return fallback(
                        "semantic adjacency root encryption unavailable: " + loaded.category
                    )
                is SemanticClaimAdjacencyRootLoadResult.Failed ->
                    return failed(loaded.reason, loaded.throwable)
            }
            if (root.buildEpoch != manifest.buildEpoch) {
                return fallback("semantic adjacency root build epoch mismatch")
            }

            var relationOrdinal = 0
            pageLoop@ for (pageOrdinal in 0 until root.pageCount) {
                if (relationOrdinal >= policy.maxRelationsPerSeed) {
                    truncated = true
                    break@pageLoop
                }
                val page = when (val loaded = index.readPage(seedReference, pageOrdinal)) {
                    SemanticClaimAdjacencyPageLoadResult.Missing ->
                        return fallback("semantic adjacency page is missing")
                    is SemanticClaimAdjacencyPageLoadResult.Loaded -> loaded.page
                    SemanticClaimAdjacencyPageLoadResult.Corrupt ->
                        return fallback("semantic adjacency page is corrupt")
                    is SemanticClaimAdjacencyPageLoadResult.Incompatible ->
                        return fallback(loaded.reason)
                    is SemanticClaimAdjacencyPageLoadResult.EncryptionUnavailable ->
                        return fallback(
                            "semantic adjacency page encryption unavailable: " + loaded.category
                        )
                    is SemanticClaimAdjacencyPageLoadResult.Failed ->
                        return failed(loaded.reason, loaded.throwable)
                }
                if (page.buildEpoch != manifest.buildEpoch) {
                    return fallback("semantic adjacency page build epoch mismatch")
                }

                for (entry in page.entries) {
                    if (relationOrdinal >= policy.maxRelationsPerSeed) {
                        truncated = true
                        break@pageLoop
                    }
                    relationOrdinal += 1
                    scanned += 1

                    val candidateId = SemanticClaimRetrievalCandidateIdentity.encode(entry.neighbor)
                    if (candidateId in seedIds) {
                        duplicateSuppressed += 1
                        continue
                    }
                    val candidate = SemanticClaimAdjacencyCandidate(
                        reference = entry.neighbor,
                        candidateId = candidateId,
                        relationType = entry.relationType,
                        direction = entry.direction,
                        seedRank = seedIndex + 1,
                        relationOrdinal = relationOrdinal
                    )
                    val previous = working.putIfAbsent(candidateId.value, candidate)
                    if (previous != null) {
                        duplicateSuppressed += 1
                        continue
                    }
                    workingSetSize = working.size
                    if (workingSetSize > policy.maxWorkingSet) {
                        working.remove(candidateId.value)
                        workingSetSize = working.size
                        truncated = true
                        break@pageLoop
                    }
                }
            }
        }

        val ordered = working.values.sortedWith(
            compareBy<SemanticClaimAdjacencyCandidate>(
                { it.seedRank },
                { it.relationOrdinal }
            ).thenComparator { left, right ->
                compareUtf8(left.candidateId.value, right.candidateId.value)
            }
        )
        if (ordered.size > policy.maxReturnedCandidates) {
            truncated = true
        }
        val selected = ordered.take(policy.maxReturnedCandidates)
        returnedCount = selected.size
        workingSetSize = working.size

        for (candidate in selected) {
            val record = when (val loaded = canonical.readExact(candidate.reference)) {
                SemanticClaimReadResult.Missing ->
                    return fallback("semantic adjacency expanded canonical claim is missing")
                is SemanticClaimReadResult.Found -> loaded.record
                SemanticClaimReadResult.Corrupt ->
                    return fallback("semantic adjacency expanded canonical claim is corrupt")
                is SemanticClaimReadResult.Incompatible ->
                    return fallback(loaded.reason)
                is SemanticClaimReadResult.EncryptionUnavailable ->
                    return fallback(
                        "semantic adjacency expanded canonical encryption unavailable: " +
                            loaded.category
                    )
                is SemanticClaimReadResult.Failed ->
                    return failed(loaded.reason, loaded.throwable)
            }
            if (
                record.id != candidate.reference.claimId ||
                record.version != candidate.reference.version
            ) {
                return fallback(
                    "semantic adjacency expanded candidate does not match canonical claim id/version"
                )
            }
        }

        val sourceAfter = canonical.sourceCheckpoint()
        if (sourceAfter != sourceBefore) {
            return fallback("canonical semantic claim source changed during adjacency query")
        }

        return SemanticClaimAdjacencyQueryResult.Expanded(
            candidates = selected,
            audit = audit(SemanticClaimAdjacencyQueryStatus.EXPANDED)
        )
    }}