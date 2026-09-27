package pro.liliya.android.semanticprovider

import org.junit.Test
import kotlin.test.assertEquals
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId

class SemanticShardLayoutContractTest {
    @Test
    fun generation_ranges_are_bounded_and_unbounded_in_shard_count() {
        assertShard(SemanticIndexDomain.MEMORY, 1L, 0L)
        assertShard(SemanticIndexDomain.MEMORY, 2_048L, 0L)
        assertShard(SemanticIndexDomain.MEMORY, 2_049L, 1L)
        assertShard(SemanticIndexDomain.MEMORY, 4_096L, 1L)
        assertShard(SemanticIndexDomain.MEMORY, 4_097L, 2L)
        assertShard(SemanticIndexDomain.MEMORY, Long.MAX_VALUE, (Long.MAX_VALUE - 1L) / 2_048L)
    }

    @Test
    fun memory_and_knowledge_with_same_generation_map_to_distinct_domain_shards() {
        val memory = SemanticShardLayout.shardFor(
            SemanticIndexSourceReference.Memory(
                MemoryRecordId("memory"),
                MemoryGeneration(7L)
            )
        )
        val knowledge = SemanticShardLayout.shardFor(
            SemanticIndexSourceReference.Knowledge(
                KnowledgeItemId("knowledge"),
                KnowledgeGeneration(7L)
            )
        )

        assertEquals(SemanticShardId(SemanticIndexDomain.MEMORY, 0L), memory)
        assertEquals(SemanticShardId(SemanticIndexDomain.KNOWLEDGE, 0L), knowledge)
    }

    @Test
    fun shard_generation_boundaries_are_exact() {
        val shard = SemanticShardId(SemanticIndexDomain.KNOWLEDGE, 12L)
        assertEquals(24_577L, SemanticShardLayout.firstGeneration(shard))
        assertEquals(26_624L, SemanticShardLayout.lastGeneration(shard))
    }

    private fun assertShard(
        domain: SemanticIndexDomain,
        generation: Long,
        expectedOrdinal: Long
    ) {
        assertEquals(
            SemanticShardId(domain, expectedOrdinal),
            SemanticShardLayout.shardFor(domain, generation)
        )
    }
}
