package pro.liliya.android.semanticprovider

import org.junit.Test
import kotlin.test.assertEquals
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId

class SemanticShardRankMergerContractTest {
    @Test
    fun merges_shard_local_top_k_into_exact_global_top_k() {
        val shardA = listOf(
            candidate("a-1", 1L, 0.91),
            candidate("a-2", 2L, 0.72),
            candidate("a-3", 3L, 0.31)
        )
        val shardB = listOf(
            candidate("b-1", 4L, 0.95),
            candidate("b-2", 5L, 0.80),
            candidate("b-3", 6L, 0.20)
        )
        val shardC = listOf(
            candidate("c-1", 7L, 0.88),
            candidate("c-2", 8L, 0.40)
        )

        val merged = SemanticShardRankMerger.merge(
            shardCandidates = listOf(shardA, shardB, shardC),
            maxCandidates = 3
        )

        assertEquals(
            listOf("b-1", "a-1", "c-1"),
            merged.map { (it.source as SemanticIndexSourceReference.Memory).id.value }
        )
    }

    @Test
    fun merge_preserves_global_generation_then_utf8_tie_breaks_across_shards() {
        val merged = SemanticShardRankMerger.merge(
            shardCandidates = listOf(
                listOf(
                    candidate("zeta", 2L, 0.75),
                    candidate("beta", 4L, 0.75)
                ),
                listOf(
                    candidate("gamma", 1L, 0.75),
                    candidate("alpha", 4L, 0.75)
                )
            ),
            maxCandidates = 4
        )

        assertEquals(
            listOf("gamma", "zeta", "alpha", "beta"),
            merged.map { (it.source as SemanticIndexSourceReference.Memory).id.value }
        )
    }

    private fun candidate(
        id: String,
        generation: Long,
        similarity: Double
    ): SemanticRankedCandidate =
        SemanticRankedCandidate(
            source = SemanticIndexSourceReference.Memory(
                id = MemoryRecordId(id),
                generation = MemoryGeneration(generation)
            ),
            similarity = similarity
        )
}
