package pro.liliya.android.semanticprovider

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId

class SemanticIndexPublicationTest {

    @Test
    fun complete_rebuild_replaces_previous_publication_atomically() {
        val publication = SemanticIndexPublication(SemanticProfileGeneration(1))
        val old = memory("old", 1)
        val fresh = memory("fresh", 2)
        publication.addExact(old, axisVector(0))

        val result = publication.rebuild(
            listOf(SemanticIndexSeed(fresh, axisVector(1)))
        )

        assertEquals(1, assertIs<SemanticIndexRebuildResult.Published>(result).entryCount)
        assertEquals(1, publication.size())
        assertEquals(
            fresh,
            publication.rank(SemanticIndexDomain.MEMORY, axisVector(1), 4).single().source
        )
    }

    @Test
    fun failed_rebuild_keeps_previous_publication_unchanged() {
        val publication = SemanticIndexPublication(SemanticProfileGeneration(1))
        val existing = memory("existing", 4)
        publication.addExact(existing, axisVector(0))
        val duplicate = memory("duplicate", 7)

        val result = publication.rebuild(
            listOf(
                SemanticIndexSeed(duplicate, axisVector(1)),
                SemanticIndexSeed(duplicate, axisVector(2))
            )
        )

        assertIs<SemanticIndexRebuildResult.DuplicateOrConflictingIdentity>(result)
        assertEquals(1, publication.size())
        assertEquals(
            existing,
            publication.rank(SemanticIndexDomain.MEMORY, axisVector(0), 4).single().source
        )
    }

    @Test
    fun capacity_failure_does_not_publish_partial_rebuild() {
        val publication = SemanticIndexPublication(
            profileGeneration = SemanticProfileGeneration(1),
            limits = SemanticFlatIndexLimits(
                maxMemoryEntries = 1,
                maxKnowledgeEntries = 1,
                maxTotalEntries = 1
            )
        )
        val existing = memory("existing", 1)
        publication.addExact(existing, axisVector(0))

        val result = publication.rebuild(
            listOf(
                SemanticIndexSeed(memory("first", 1), axisVector(1)),
                SemanticIndexSeed(memory("second", 1), axisVector(2))
            )
        )

        assertIs<SemanticIndexRebuildResult.CapacityRejected>(result)
        assertEquals(1, publication.size())
        assertEquals(
            existing,
            publication.rank(SemanticIndexDomain.MEMORY, axisVector(0), 4).single().source
        )
    }

    @Test
    fun incremental_replace_and_remove_remain_generation_exact() {
        val publication = SemanticIndexPublication(SemanticProfileGeneration(1))
        val first = memory("entity", 2)
        val second = memory("entity", 3)
        publication.addExact(first, axisVector(0))

        assertIs<SemanticIndexReplaceResult.Replaced>(
            publication.replaceExact(first, second, axisVector(1))
        )
        assertIs<SemanticIndexRemoveResult.StaleOrMissing>(publication.removeExact(first))
        assertIs<SemanticIndexRemoveResult.Removed>(publication.removeExact(second))
        assertEquals(0, publication.size())
    }

    private fun memory(id: String, generation: Long): SemanticIndexSourceReference.Memory =
        SemanticIndexSourceReference.Memory(
            id = MemoryRecordId(id),
            generation = MemoryGeneration(generation)
        )

    private fun axisVector(index: Int): SemanticEmbeddingVector {
        val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
        values[index] = 1f
        return SemanticEmbeddingVector(values)
    }
}
