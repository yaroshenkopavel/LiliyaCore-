package pro.liliya.android.semanticprovider

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId

class SemanticFlatIndexContractTest {
    @Test
    fun embedding_vector_requires_exact_dimension_finite_values_and_normalization() {
        assertFailsWith<IllegalArgumentException> {
            SemanticEmbeddingVector(FloatArray(383) { 0f })
        }

        val nan = basis(0).copyOf().also { it[0] = Float.NaN }
        assertFailsWith<IllegalArgumentException> { SemanticEmbeddingVector(nan) }

        val notNormalized = FloatArray(384).also { it[0] = 2f }
        assertFailsWith<IllegalArgumentException> { SemanticEmbeddingVector(notNormalized) }

        val vector = SemanticEmbeddingVector(basis(3))
        assertContentEquals(basis(3), vector.copyValues())
        assertTrue(vector.toString().contains("<redacted:384>"))
    }

    @Test
    fun exact_flat_ranking_preserves_similarity_then_generation_then_utf8_id_order() {
        val index = SemanticFlatIndex(SemanticProfileGeneration(1))
        val equal = SemanticEmbeddingVector(basis(0))
        val less = SemanticEmbeddingVector(unit(0.8f, 0.6f))

        val newerB = memory("b", 2)
        val olderZ = memory("z", 1)
        val olderA = memory("a", 1)
        val lowerSimilarity = memory("topically-lower", 1)

        assertIs<SemanticIndexAddResult.Indexed>(index.addExact(newerB, equal))
        assertIs<SemanticIndexAddResult.Indexed>(index.addExact(olderZ, equal))
        assertIs<SemanticIndexAddResult.Indexed>(index.addExact(olderA, equal))
        assertIs<SemanticIndexAddResult.Indexed>(index.addExact(lowerSimilarity, less))

        val ranked = index.rank(SemanticIndexDomain.MEMORY, equal, maxCandidates = 4)

        assertEquals(
            listOf(olderA, olderZ, newerB, lowerSimilarity),
            ranked.map { it.source }
        )
    }

    @Test
    fun deterministic_tie_break_uses_unsigned_utf8_byte_order_for_non_ascii_ids() {
        val index = SemanticFlatIndex(SemanticProfileGeneration(1))
        val equal = SemanticEmbeddingVector(basis(0))

        val emoji = memory("🙂", 1)
        val cyrillic = memory("я", 1)
        val accented = memory("é", 1)
        val ascii = memory("a", 1)

        assertIs<SemanticIndexAddResult.Indexed>(index.addExact(emoji, equal))
        assertIs<SemanticIndexAddResult.Indexed>(index.addExact(cyrillic, equal))
        assertIs<SemanticIndexAddResult.Indexed>(index.addExact(accented, equal))
        assertIs<SemanticIndexAddResult.Indexed>(index.addExact(ascii, equal))

        assertEquals(
            listOf(ascii, accented, cyrillic, emoji),
            index.rank(SemanticIndexDomain.MEMORY, equal, maxCandidates = 4).map { it.source }
        )
    }

    @Test
    fun bounded_top_k_replaces_worse_early_entries_with_better_late_entries() {
        val index = SemanticFlatIndex(SemanticProfileGeneration(1))
        val query = SemanticEmbeddingVector(basis(0))
        val unrelated = SemanticEmbeddingVector(basis(1))
        val relevant = SemanticEmbeddingVector(basis(0))

        val earlyLowA = memory("early-low-a", 1)
        val earlyLowB = memory("early-low-b", 2)
        val lateBestOlder = memory("late-best-older", 3)
        val lateBestNewer = memory("late-best-newer", 4)

        assertIs<SemanticIndexAddResult.Indexed>(index.addExact(earlyLowA, unrelated))
        assertIs<SemanticIndexAddResult.Indexed>(index.addExact(earlyLowB, unrelated))
        assertIs<SemanticIndexAddResult.Indexed>(index.addExact(lateBestNewer, relevant))
        assertIs<SemanticIndexAddResult.Indexed>(index.addExact(lateBestOlder, relevant))

        assertEquals(
            listOf(lateBestOlder, lateBestNewer),
            index.rank(SemanticIndexDomain.MEMORY, query, maxCandidates = 2).map { it.source }
        )
    }

    @Test
    fun duplicate_and_same_entity_new_generation_do_not_silently_replace() {
        val index = SemanticFlatIndex(SemanticProfileGeneration(1))
        val v = SemanticEmbeddingVector(basis(0))
        val first = memory("same", 1)
        val next = memory("same", 2)

        assertIs<SemanticIndexAddResult.Indexed>(index.addExact(first, v))
        assertIs<SemanticIndexAddResult.DuplicateExact>(index.addExact(first, v))
        assertIs<SemanticIndexAddResult.EntityAlreadyIndexed>(index.addExact(next, v))
        assertEquals(listOf(first), index.rank(SemanticIndexDomain.MEMORY, v, 4).map { it.source })
    }

    @Test
    fun exact_replace_requires_same_entity_exact_expected_and_forward_generation() {
        val index = SemanticFlatIndex(SemanticProfileGeneration(1))
        val v1 = SemanticEmbeddingVector(basis(0))
        val v2 = SemanticEmbeddingVector(basis(1))
        val old = memory("replace", 1)
        val current = memory("replace", 2)
        val future = memory("replace", 3)

        assertIs<SemanticIndexAddResult.Indexed>(index.addExact(current, v1))
        assertIs<SemanticIndexReplaceResult.StaleExpected>(index.replaceExact(old, future, v2))
        assertIs<SemanticIndexReplaceResult.IdentityMismatch>(
            index.replaceExact(current, memory("other", 3), v2)
        )
        assertIs<SemanticIndexReplaceResult.NonForwardGeneration>(
            index.replaceExact(current, old, v2)
        )
        assertIs<SemanticIndexReplaceResult.NonForwardGeneration>(
            index.replaceExact(current, current, v2)
        )
        assertIs<SemanticIndexReplaceResult.Replaced>(index.replaceExact(current, future, v2))
        assertEquals(listOf(future), index.rank(SemanticIndexDomain.MEMORY, v2, 1).map { it.source })
    }

    @Test
    fun stale_remove_cannot_remove_newer_generation() {
        val index = SemanticFlatIndex(SemanticProfileGeneration(1))
        val v = SemanticEmbeddingVector(basis(0))
        val stale = knowledge("item", 1)
        val live = knowledge("item", 2)

        assertIs<SemanticIndexAddResult.Indexed>(index.addExact(live, v))
        assertIs<SemanticIndexRemoveResult.StaleOrMissing>(index.removeExact(stale))
        assertEquals(1, index.size(SemanticIndexDomain.KNOWLEDGE))
        assertIs<SemanticIndexRemoveResult.Removed>(index.removeExact(live))
        assertEquals(0, index.size(SemanticIndexDomain.KNOWLEDGE))
    }

    @Test
    fun domain_and_total_capacity_fail_closed_without_eviction() {
        val limits = SemanticFlatIndexLimits(
            maxMemoryEntries = 2,
            maxKnowledgeEntries = 2,
            maxTotalEntries = 3
        )
        val index = SemanticFlatIndex(SemanticProfileGeneration(1), limits)
        val v = SemanticEmbeddingVector(basis(0))

        assertIs<SemanticIndexAddResult.Indexed>(index.addExact(memory("m1", 1), v))
        assertIs<SemanticIndexAddResult.Indexed>(index.addExact(memory("m2", 2), v))
        assertIs<SemanticIndexAddResult.CapacityRejected>(index.addExact(memory("m3", 3), v))
        assertIs<SemanticIndexAddResult.Indexed>(index.addExact(knowledge("k1", 4), v))
        assertIs<SemanticIndexAddResult.CapacityRejected>(index.addExact(knowledge("k2", 5), v))

        assertEquals(3, index.size())
        assertEquals(2, index.size(SemanticIndexDomain.MEMORY))
        assertEquals(1, index.size(SemanticIndexDomain.KNOWLEDGE))
    }

    @Test
    fun requested_candidate_bound_is_honored_without_cross_domain_results() {
        val index = SemanticFlatIndex(SemanticProfileGeneration(7))
        val query = SemanticEmbeddingVector(basis(0))
        repeat(5) { n ->
            assertIs<SemanticIndexAddResult.Indexed>(
                index.addExact(memory("m$n", (n + 1).toLong()), query)
            )
        }
        assertIs<SemanticIndexAddResult.Indexed>(index.addExact(knowledge("k", 9), query))

        val result = index.rank(SemanticIndexDomain.MEMORY, query, maxCandidates = 2)
        assertEquals(2, result.size)
        assertTrue(result.all { it.source.domain == SemanticIndexDomain.MEMORY })
    }

    @Test
    fun ranking_request_above_domain_capacity_returns_only_live_entries() {
        val limits = SemanticFlatIndexLimits(
            maxMemoryEntries = 2,
            maxKnowledgeEntries = 3,
            maxTotalEntries = 5
        )
        val index = SemanticFlatIndex(SemanticProfileGeneration(1), limits)
        val query = SemanticEmbeddingVector(basis(0))
        val first = memory("first", 1)
        val second = memory("second", 2)

        assertIs<SemanticIndexAddResult.Indexed>(index.addExact(first, query))
        assertIs<SemanticIndexAddResult.Indexed>(index.addExact(second, query))

        assertEquals(
            listOf(first, second),
            index.rank(
                SemanticIndexDomain.MEMORY,
                query,
                maxCandidates = Int.MAX_VALUE
            ).map { it.source }
        )
    }

    @Test
    fun structural_rendering_redacts_ids_and_vectors() {
        val source = memory("private-memory-id", 4)
        val vector = SemanticEmbeddingVector(basis(0))

        assertTrue(!source.toString().contains("private-memory-id"))
        assertTrue(!vector.toString().contains("1.0"))
        assertTrue(SemanticRankedCandidate(source).toString().contains("id=<redacted>"))
    }

    private fun memory(id: String, generation: Long) =
        SemanticIndexSourceReference.Memory(MemoryRecordId(id), MemoryGeneration(generation))

    private fun knowledge(id: String, generation: Long) =
        SemanticIndexSourceReference.Knowledge(KnowledgeItemId(id), KnowledgeGeneration(generation))

    private fun basis(index: Int): FloatArray = FloatArray(384).also { it[index] = 1f }

    private fun unit(first: Float, second: Float): FloatArray =
        FloatArray(384).also {
            it[0] = first
            it[1] = second
        }
}
