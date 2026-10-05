package pro.liliya.core.semantic

import pro.liliya.core.retrieval.HybridFusedCandidate
import pro.liliya.core.retrieval.RetrievalCandidateId
import pro.liliya.core.retrieval.compareUtf8

data class SemanticClaimGraphTraversalPolicy(
    val maxDepth: Int = 2,
    val maxSeeds: Int = 128,
    val maxFrontier: Int = 256,
    val maxVisited: Int = 2048,
    val maxReturnedCandidates: Int = 128,
    val maxRelationsPerNode: Int = 128
) {
    init {
        require(maxDepth in 1..2)
        require(maxSeeds in 1..128)
        require(maxFrontier in 1..256)
        require(maxVisited in maxReturnedCandidates..2048)
        require(maxReturnedCandidates in 1..128)
        require(maxRelationsPerNode in 1..128)
    }
}

data class SemanticClaimGraphTraversalCandidate(
    val reference: SemanticClaimVersionReference,
    val candidateId: RetrievalCandidateId,
    val depth: Int,
    val seedRank: Int,
    val parentCandidateId: RetrievalCandidateId,
    val relationType: SemanticClaimRelationType,
    val direction: SemanticClaimAdjacencyDirection
) {
    init {
        require(depth in 1..2)
        require(seedRank > 0)
    }
}

enum class SemanticClaimGraphTraversalStatus {
    TRAVERSED,
    REJECTED,
    FALLBACK_REQUIRED,
    FAILED
}

data class SemanticClaimGraphTraversalAudit(
    val source: SemanticClaimSourceCheckpoint?,
    val seedCount: Int,
    val depthReached: Int,
    val frontierPeak: Int,
    val visitedCount: Int,
    val relationEntriesScanned: Int,
    val duplicatesOrCyclesSuppressed: Int,
    val returnedCount: Int,
    val truncated: Boolean,
    val status: SemanticClaimGraphTraversalStatus,
    val advisoryOnly: Boolean = true
) {
    init {
        require(seedCount >= 0)
        require(depthReached in 0..2)
        require(frontierPeak >= 0)
        require(visitedCount >= 0)
        require(relationEntriesScanned >= 0)
        require(duplicatesOrCyclesSuppressed >= 0)
        require(returnedCount >= 0)
        require(advisoryOnly)
    }
}

sealed interface SemanticClaimGraphTraversalResult {
    data class Traversed(
        val candidates: List<SemanticClaimGraphTraversalCandidate>,
        val audit: SemanticClaimGraphTraversalAudit
    ) : SemanticClaimGraphTraversalResult

    data class Rejected(
        val reason: String,
        val audit: SemanticClaimGraphTraversalAudit
    ) : SemanticClaimGraphTraversalResult

    data class FallbackRequired(
        val reason: String,
        val audit: SemanticClaimGraphTraversalAudit
    ) : SemanticClaimGraphTraversalResult

    data class Failed(
        val reason: String,
        val throwable: Throwable? = null,
        val audit: SemanticClaimGraphTraversalAudit
    ) : SemanticClaimGraphTraversalResult
}

class SemanticClaimGraphTraversalSource internal constructor(
    private val canonical: SemanticClaimAdjacencyCanonicalReader,
    private val index: SemanticClaimAdjacencyIndexReader,
    private val adjacencyPolicy: SemanticClaimAdjacencyPolicy = SemanticClaimAdjacencyPolicy(),
    private val policy: SemanticClaimGraphTraversalPolicy = SemanticClaimGraphTraversalPolicy()
) {
    constructor(
        repository: EncryptedPersistentSemanticClaimRepository,
        indexStore: EncryptedPersistentSemanticClaimAdjacencyIndexStore,
        adjacencyPolicy: SemanticClaimAdjacencyPolicy = SemanticClaimAdjacencyPolicy(),
        policy: SemanticClaimGraphTraversalPolicy = SemanticClaimGraphTraversalPolicy()
    ) : this(
        RepositoryGraphCanonicalReader(repository),
        StoreGraphAdjacencyIndexReader(indexStore),
        adjacencyPolicy,
        policy
    )

    fun traverse(seeds: List<HybridFusedCandidate>): SemanticClaimGraphTraversalResult {
        var source: SemanticClaimSourceCheckpoint? = null
        var depthReached = 0
        var frontierPeak = 0
        var scanned = 0
        var suppressed = 0
        var truncated = false
        val visited = LinkedHashSet<RetrievalCandidateId>()
        val discovered = ArrayList<SemanticClaimGraphTraversalCandidate>()

        fun audit(status: SemanticClaimGraphTraversalStatus) =
            SemanticClaimGraphTraversalAudit(
                source = source,
                seedCount = seeds.size,
                depthReached = depthReached,
                frontierPeak = frontierPeak,
                visitedCount = visited.size,
                relationEntriesScanned = scanned,
                duplicatesOrCyclesSuppressed = suppressed,
                returnedCount = minOf(discovered.size, policy.maxReturnedCandidates),
                truncated = truncated,
                status = status
            )

        fun rejected(reason: String) =
            SemanticClaimGraphTraversalResult.Rejected(
                reason, audit(SemanticClaimGraphTraversalStatus.REJECTED)
            )
        fun fallback(reason: String) =
            SemanticClaimGraphTraversalResult.FallbackRequired(
                reason, audit(SemanticClaimGraphTraversalStatus.FALLBACK_REQUIRED)
            )
        fun failed(reason: String, throwable: Throwable? = null) =
            SemanticClaimGraphTraversalResult.Failed(
                reason, throwable, audit(SemanticClaimGraphTraversalStatus.FAILED)
            )

        if (seeds.size > policy.maxSeeds) return rejected("semantic graph seed budget exceeded")
        if (seeds.map { it.id }.distinct().size != seeds.size) {
            return rejected("semantic graph seeds contain duplicate ids")
        }

        val seedRefs = ArrayList<Pair<Int, SemanticClaimVersionReference>>(seeds.size)
        seeds.forEachIndexed { indexSeed, seed ->
            val ref = SemanticClaimRetrievalCandidateIdentity.decode(seed.id)
                ?: return rejected("semantic graph seed id is not a valid Semantic Claim identity")
            seedRefs += (indexSeed + 1) to ref
            visited += seed.id
        }

        val sourceBefore = canonical.sourceCheckpoint()
        source = sourceBefore
        val manifest = when (val loaded = index.readManifest()) {
            SemanticClaimAdjacencyManifestLoadResult.Missing ->
                return fallback("semantic graph adjacency manifest is missing")
            is SemanticClaimAdjacencyManifestLoadResult.Loaded -> loaded.manifest
            SemanticClaimAdjacencyManifestLoadResult.Corrupt ->
                return fallback("semantic graph adjacency manifest is corrupt")
            is SemanticClaimAdjacencyManifestLoadResult.Incompatible -> return fallback(loaded.reason)
            is SemanticClaimAdjacencyManifestLoadResult.EncryptionUnavailable ->
                return fallback("semantic graph adjacency manifest encryption unavailable: " + loaded.category)
            is SemanticClaimAdjacencyManifestLoadResult.Failed ->
                return failed(loaded.reason, loaded.throwable)
        }
        if (
            manifest.state != SemanticClaimAdjacencyIndexState.COMPLETE ||
            manifest.source != sourceBefore ||
            manifest.policyVersion != adjacencyPolicy.version ||
            manifest.pageEntries != adjacencyPolicy.pageEntries
        ) return fallback("semantic graph adjacency index is stale or incompatible")

        data class FrontierNode(
            val reference: SemanticClaimVersionReference,
            val candidateId: RetrievalCandidateId,
            val seedRank: Int
        )

        var frontier = seedRefs.map { (rank, ref) ->
            FrontierNode(ref, SemanticClaimRetrievalCandidateIdentity.encode(ref), rank)
        }

        for (depth in 1..policy.maxDepth) {
            if (frontier.isEmpty()) break
            depthReached = depth
            frontierPeak = maxOf(frontierPeak, frontier.size)
            if (frontier.size > policy.maxFrontier) {
                truncated = true
                frontier = frontier.take(policy.maxFrontier)
            }

            val next = ArrayList<FrontierNode>()
            for (node in frontier) {
                val root = when (val loaded = index.readRoot(node.reference)) {
                    SemanticClaimAdjacencyRootLoadResult.Missing -> continue
                    is SemanticClaimAdjacencyRootLoadResult.Loaded -> loaded.root
                    SemanticClaimAdjacencyRootLoadResult.Corrupt -> return fallback("semantic graph adjacency root is corrupt")
                    is SemanticClaimAdjacencyRootLoadResult.Incompatible -> return fallback(loaded.reason)
                    is SemanticClaimAdjacencyRootLoadResult.EncryptionUnavailable ->
                        return fallback("semantic graph adjacency root encryption unavailable: " + loaded.category)
                    is SemanticClaimAdjacencyRootLoadResult.Failed ->
                        return failed(loaded.reason, loaded.throwable)
                }
                if (root.buildEpoch != manifest.buildEpoch) return fallback("semantic graph adjacency root epoch mismatch")

                val edges = ArrayList<SemanticClaimAdjacencyEntry>()
                var nodeScanned = 0
                pageLoop@ for (ordinal in 0 until root.pageCount) {
                    val page = when (val loaded = index.readPage(node.reference, ordinal)) {
                        SemanticClaimAdjacencyPageLoadResult.Missing -> return fallback("semantic graph adjacency page is missing")
                        is SemanticClaimAdjacencyPageLoadResult.Loaded -> loaded.page
                        SemanticClaimAdjacencyPageLoadResult.Corrupt -> return fallback("semantic graph adjacency page is corrupt")
                        is SemanticClaimAdjacencyPageLoadResult.Incompatible -> return fallback(loaded.reason)
                        is SemanticClaimAdjacencyPageLoadResult.EncryptionUnavailable ->
                            return fallback("semantic graph adjacency page encryption unavailable: " + loaded.category)
                        is SemanticClaimAdjacencyPageLoadResult.Failed ->
                            return failed(loaded.reason, loaded.throwable)
                    }
                    if (page.buildEpoch != manifest.buildEpoch) return fallback("semantic graph adjacency page epoch mismatch")
                    for (entry in page.entries) {
                        if (nodeScanned >= policy.maxRelationsPerNode) {
                            truncated = true
                            break@pageLoop
                        }
                        nodeScanned += 1
                        scanned += 1
                        edges += entry
                    }
                }

                edges.sortWith { left, right ->
                    val idOrder = compareUtf8(
                        left.neighbor.claimId.value,
                        right.neighbor.claimId.value
                    )
                    if (idOrder != 0) {
                        idOrder
                    } else {
                        val versionOrder = left.neighbor.version.value.compareTo(
                            right.neighbor.version.value
                        )
                        if (versionOrder != 0) versionOrder
                        else {
                            val typeOrder = left.relationType.name.compareTo(right.relationType.name)
                            if (typeOrder != 0) typeOrder
                            else left.direction.name.compareTo(right.direction.name)
                        }
                    }
                }

                for (edge in edges) {
                    val id = SemanticClaimRetrievalCandidateIdentity.encode(edge.neighbor)
                    if (!visited.add(id)) {
                        suppressed += 1
                        continue
                    }
                    if (visited.size > policy.maxVisited) {
                        visited.remove(id)
                        truncated = true
                        break
                    }
                    val candidate = SemanticClaimGraphTraversalCandidate(
                        reference = edge.neighbor,
                        candidateId = id,
                        depth = depth,
                        seedRank = node.seedRank,
                        parentCandidateId = node.candidateId,
                        relationType = edge.relationType,
                        direction = edge.direction
                    )
                    discovered += candidate
                    if (depth < policy.maxDepth && next.size < policy.maxFrontier) {
                        next += FrontierNode(edge.neighbor, id, node.seedRank)
                    } else if (depth < policy.maxDepth) {
                        truncated = true
                    }
                }
            }
            next.sortWith(
                compareBy<FrontierNode> { it.seedRank }
                    .thenComparator { left, right -> compareUtf8(left.candidateId.value, right.candidateId.value) }
            )
            frontier = next
        }

        val selected = discovered
            .sortedWith(
                compareBy<SemanticClaimGraphTraversalCandidate>({ it.depth }, { it.seedRank })
                    .thenComparator { left, right -> compareUtf8(left.candidateId.value, right.candidateId.value) }
            )
            .take(policy.maxReturnedCandidates)
        if (discovered.size > selected.size) truncated = true

        for (candidate in selected) {
            val record = when (val loaded = canonical.readExact(candidate.reference)) {
                SemanticClaimReadResult.Missing -> return fallback("semantic graph canonical claim is missing")
                is SemanticClaimReadResult.Found -> loaded.record
                SemanticClaimReadResult.Corrupt -> return fallback("semantic graph canonical claim is corrupt")
                is SemanticClaimReadResult.Incompatible -> return fallback(loaded.reason)
                is SemanticClaimReadResult.EncryptionUnavailable ->
                    return fallback("semantic graph canonical claim encryption unavailable: " + loaded.category)
                is SemanticClaimReadResult.Failed -> return failed(loaded.reason, loaded.throwable)
            }
            if (record.id != candidate.reference.claimId || record.version != candidate.reference.version) {
                return fallback("semantic graph candidate does not match canonical claim id/version")
            }
        }

        if (canonical.sourceCheckpoint() != sourceBefore) {
            return fallback("canonical semantic claim source changed during graph traversal")
        }

        return SemanticClaimGraphTraversalResult.Traversed(
            selected,
            audit(SemanticClaimGraphTraversalStatus.TRAVERSED)
        )
    }
}

private class RepositoryGraphCanonicalReader(
    private val repository: EncryptedPersistentSemanticClaimRepository
) : SemanticClaimAdjacencyCanonicalReader {
    override fun sourceCheckpoint() = repository.sourceCheckpoint()
    override fun readExact(reference: SemanticClaimVersionReference) = repository.readExact(reference)
}

private class StoreGraphAdjacencyIndexReader(
    private val store: EncryptedPersistentSemanticClaimAdjacencyIndexStore
) : SemanticClaimAdjacencyIndexReader {
    override fun readManifest() = store.readManifest()
    override fun readRoot(reference: SemanticClaimVersionReference) = store.readRoot(reference)
    override fun readPage(reference: SemanticClaimVersionReference, ordinal: Long) =
        store.readPage(reference, ordinal)
}