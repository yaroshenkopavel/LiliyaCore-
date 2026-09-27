package pro.liliya.android.semanticprovider

import java.util.PriorityQueue

/**
 * Deterministically merges shard-local top-K results without exposing scores outside the provider.
 *
 * Each shard only needs to return its own top K. A candidate that is not in a shard's local top K
 * cannot belong to the global top K, so the merge remains exact while the live merge heap is
 * bounded by maxCandidates.
 */
internal object SemanticShardRankMerger {
    fun merge(
        shardCandidates: Iterable<List<SemanticRankedCandidate>>,
        maxCandidates: Int
    ): List<SemanticRankedCandidate> {
        require(maxCandidates > 0) { "maximum semantic candidates must be positive" }

        val bestFirst = Comparator<SemanticRankedCandidate> { left, right ->
            compareBestFirst(left, right)
        }
        val worstFirst = bestFirst.reversed()
        val top = PriorityQueue(maxCandidates, worstFirst)

        shardCandidates.forEach { candidates ->
            require(candidates.size <= maxCandidates) {
                "shard candidate list exceeds requested top-K bound"
            }
            candidates.forEach { candidate ->
                require(candidate.similarity.isFinite()) {
                    "shard candidate similarity must be finite"
                }
                if (top.size < maxCandidates) {
                    top.add(candidate)
                } else if (bestFirst.compare(candidate, top.peek()) < 0) {
                    top.poll()
                    top.add(candidate)
                }
            }
        }

        return top.toList().sortedWith(bestFirst)
    }

    private fun compareBestFirst(
        left: SemanticRankedCandidate,
        right: SemanticRankedCandidate
    ): Int {
        val similarity = right.similarity.compareTo(left.similarity)
        if (similarity != 0) return similarity

        val generation = left.source.generationValue.compareTo(right.source.generationValue)
        if (generation != 0) return generation

        val leftId = left.source.stableIdUtf8()
        val rightId = right.source.stableIdUtf8()
        return try {
            compareUtf8(leftId, rightId)
        } finally {
            leftId.fill(0)
            rightId.fill(0)
        }
    }

    private fun compareUtf8(
        left: ByteArray,
        right: ByteArray
    ): Int {
        val size = minOf(left.size, right.size)
        for (index in 0 until size) {
            val leftByte = left[index].toInt() and 0xff
            val rightByte = right[index].toInt() and 0xff
            if (leftByte != rightByte) return leftByte.compareTo(rightByte)
        }
        return left.size.compareTo(right.size)
    }
}
