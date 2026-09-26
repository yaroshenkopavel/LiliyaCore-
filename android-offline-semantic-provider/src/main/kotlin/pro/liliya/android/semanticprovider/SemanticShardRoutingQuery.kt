package pro.liliya.android.semanticprovider

import java.util.PriorityQueue

internal sealed interface SemanticShardRoutingQueryResult {
    data class Routed(
        val candidates: List<SemanticRankedCandidate>,
        val routingNodeReads: Int,
        val shardReads: Int
    ) : SemanticShardRoutingQueryResult

    data object Missing : SemanticShardRoutingQueryResult
    data object Stale : SemanticShardRoutingQueryResult
    data object Corrupt : SemanticShardRoutingQueryResult
    data class Incompatible(val reason: String) : SemanticShardRoutingQueryResult
    data class Failed(val reason: String, val throwable: Throwable? = null) :
        SemanticShardRoutingQueryResult
}

internal class SemanticShardRoutingQuery(
    private val shardStore: SemanticShardStore,
    private val routingStore: SemanticShardRoutingStore
) {
    private sealed interface FrontierItem {
        val upperBound: Double
        val tieKey: String

        data class Node(
            val sha256: String,
            val expectedLevel: Int,
            override val upperBound: Double
        ) : FrontierItem {
            override val tieKey: String = "N:" + sha256
        }

        data class Shard(
            val descriptor: SemanticShardDescriptor,
            override val upperBound: Double
        ) : FrontierItem {
            override val tieKey: String =
                "S:" + descriptor.shardId.domain.ordinal + ":" +
                    descriptor.shardId.ordinal + ":" + descriptor.blobSha256
        }
    }

    fun rank(
        manifest: SemanticShardManifestRootV3,
        domain: SemanticIndexDomain,
        query: SemanticEmbeddingVector,
        maxCandidates: Int
    ): SemanticShardRoutingQueryResult {
        require(maxCandidates > 0)

        val routing = when (val loaded = routingStore.loadRoot()) {
            SemanticShardRoutingRootLoadResult.Missing ->
                return SemanticShardRoutingQueryResult.Missing
            SemanticShardRoutingRootLoadResult.Corrupt ->
                return SemanticShardRoutingQueryResult.Corrupt
            is SemanticShardRoutingRootLoadResult.Incompatible ->
                return SemanticShardRoutingQueryResult.Incompatible(loaded.reason)
            is SemanticShardRoutingRootLoadResult.Failed ->
                return SemanticShardRoutingQueryResult.Failed(loaded.reason, loaded.throwable)
            is SemanticShardRoutingRootLoadResult.Loaded -> loaded.root
        }

        if (!routing.matches(manifest)) return SemanticShardRoutingQueryResult.Stale

        val domainRoot = routing.domain(domain)
        if (domainRoot.shardCount == 0L) {
            return SemanticShardRoutingQueryResult.Routed(
                candidates = emptyList(),
                routingNodeReads = 0,
                shardReads = 0
            )
        }
        val rootSha = domainRoot.rootNodeSha256
            ?: return SemanticShardRoutingQueryResult.Corrupt

        val frontier = PriorityQueue<FrontierItem>(
            compareByDescending<FrontierItem> { it.upperBound }
                .thenBy { it.tieKey }
        )
        frontier += FrontierItem.Node(
            sha256 = rootSha,
            expectedLevel = domainRoot.depth - 1,
            upperBound = Double.POSITIVE_INFINITY
        )

        var topK: List<SemanticRankedCandidate> = emptyList()
        var routingNodeReads = 0
        var shardReads = 0

        while (frontier.isNotEmpty()) {
            val next = frontier.peek() ?: break
            val threshold = if (topK.size == maxCandidates) {
                topK.last().similarity
            } else {
                Double.NEGATIVE_INFINITY
            }

            // Strict inequality only. On equality, generation/UTF-8 tie-break may still change K.
            if (topK.size == maxCandidates && next.upperBound < threshold) break

            when (val item = frontier.poll()) {
                is FrontierItem.Node -> {
                    routingNodeReads += 1
                    val node = when (val loaded = routingStore.readNode(item.sha256)) {
                        SemanticShardRoutingNodeLoadResult.Missing ->
                            return SemanticShardRoutingQueryResult.Corrupt
                        SemanticShardRoutingNodeLoadResult.Corrupt ->
                            return SemanticShardRoutingQueryResult.Corrupt
                        is SemanticShardRoutingNodeLoadResult.Incompatible ->
                            return SemanticShardRoutingQueryResult.Incompatible(loaded.reason)
                        is SemanticShardRoutingNodeLoadResult.Failed ->
                            return SemanticShardRoutingQueryResult.Failed(
                                loaded.reason,
                                loaded.throwable
                            )
                        is SemanticShardRoutingNodeLoadResult.Loaded -> loaded.node
                    }
                    if (node.level != item.expectedLevel) {
                        return SemanticShardRoutingQueryResult.Corrupt
                    }

                    when (node) {
                        is SemanticShardRoutingNode.Leaf -> {
                            if (node.level != 0) return SemanticShardRoutingQueryResult.Corrupt
                            for (entry in node.entries) {
                                if (entry.descriptor.shardId.domain != domain) {
                                    return SemanticShardRoutingQueryResult.Corrupt
                                }
                                val bound = entry.envelope.upperBound(query)
                                if (!bound.isFinite()) {
                                    return SemanticShardRoutingQueryResult.Corrupt
                                }
                                frontier += FrontierItem.Shard(entry.descriptor, bound)
                            }
                        }
                        is SemanticShardRoutingNode.Internal -> {
                            if (node.level <= 0) return SemanticShardRoutingQueryResult.Corrupt
                            for (child in node.children) {
                                val bound = child.envelope.upperBound(query)
                                if (!bound.isFinite()) {
                                    return SemanticShardRoutingQueryResult.Corrupt
                                }
                                frontier += FrontierItem.Node(
                                    sha256 = child.nodeSha256,
                                    expectedLevel = node.level - 1,
                                    upperBound = bound
                                )
                            }
                        }
                    }
                }

                is FrontierItem.Shard -> {
                    shardReads += 1
                    when (
                        val ranked = shardStore.rankDescriptors(
                            descriptors = listOf(item.descriptor),
                            domain = domain,
                            query = query,
                            maxCandidates = maxCandidates
                        )
                    ) {
                        is SemanticShardRankResult.Ranked -> {
                            topK = SemanticShardRankMerger.merge(
                                shardCandidates = listOf(topK, ranked.candidates),
                                maxCandidates = maxCandidates
                            )
                        }
                        SemanticShardRankResult.Corrupt ->
                            return SemanticShardRoutingQueryResult.Corrupt
                        is SemanticShardRankResult.Incompatible ->
                            return SemanticShardRoutingQueryResult.Incompatible(ranked.reason)
                        is SemanticShardRankResult.Failed ->
                            return SemanticShardRoutingQueryResult.Failed(
                                ranked.reason,
                                ranked.throwable
                            )
                    }
                }
            }
        }

        return SemanticShardRoutingQueryResult.Routed(
            candidates = topK,
            routingNodeReads = routingNodeReads,
            shardReads = shardReads
        )
    }
}
