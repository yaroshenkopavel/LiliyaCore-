package pro.liliya.android.semanticprovider

import kotlin.test.assertEquals
import org.junit.Test
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId

class SemanticIndexReleaseContractTest {

    @Test
    fun release_clears_published_entries_and_embedding_values() {
        val publication = SemanticIndexPublication(SemanticProfileGeneration(1))
        val vector = unitVector()

        assertEquals(
            SemanticIndexAddResult.Indexed,
            publication.addExact(
                SemanticIndexSourceReference.Memory(
                    MemoryRecordId("release-memory"),
                    MemoryGeneration(1)
                ),
                vector
            )
        )
        assertEquals(1, publication.size())

        publication.release()

        assertEquals(0, publication.size())
        assertEquals(
            true,
            vector.copyValues().all { it == 0f }
        )
    }

    @Test
    fun exact_remove_clears_retired_embedding_values() {
        val index = SemanticFlatIndex(SemanticProfileGeneration(1))
        val vector = unitVector()
        val source = SemanticIndexSourceReference.Memory(
            MemoryRecordId("remove-memory"),
            MemoryGeneration(1)
        )

        assertEquals(SemanticIndexAddResult.Indexed, index.addExact(source, vector))
        assertEquals(SemanticIndexRemoveResult.Removed, index.removeExact(source))
        assertEquals(0, index.size())
        assertEquals(true, vector.copyValues().all { it == 0f })
    }

    @Test
    fun exact_replace_clears_previous_embedding_values_only() {
        val index = SemanticFlatIndex(SemanticProfileGeneration(1))
        val previous = unitVector(0)
        val replacement = unitVector(1)
        val expected = SemanticIndexSourceReference.Memory(
            MemoryRecordId("replace-memory"),
            MemoryGeneration(1)
        )
        val next = SemanticIndexSourceReference.Memory(
            MemoryRecordId("replace-memory"),
            MemoryGeneration(2)
        )

        assertEquals(SemanticIndexAddResult.Indexed, index.addExact(expected, previous))
        assertEquals(
            SemanticIndexReplaceResult.Replaced,
            index.replaceExact(expected, next, replacement)
        )

        assertEquals(true, previous.copyValues().all { it == 0f })
        assertEquals(1f, replacement.copyValues()[1])
        assertEquals(1, index.size())
    }

    @Test
    fun rejected_transactional_rebuild_clears_partial_replacement_vectors() {
        val publication = SemanticIndexPublication(
            profileGeneration = SemanticProfileGeneration(1),
            limits = SemanticFlatIndexLimits(
                maxMemoryEntries = 1,
                maxKnowledgeEntries = 1,
                maxTotalEntries = 1
            )
        )
        val first = unitVector(0)
        val second = unitVector(1)

        assertEquals(
            SemanticIndexRebuildResult.CapacityRejected,
            publication.rebuild(
                listOf(
                    SemanticIndexSeed(
                        SemanticIndexSourceReference.Memory(
                            MemoryRecordId("capacity-1"),
                            MemoryGeneration(1)
                        ),
                        first
                    ),
                    SemanticIndexSeed(
                        SemanticIndexSourceReference.Memory(
                            MemoryRecordId("capacity-2"),
                            MemoryGeneration(2)
                        ),
                        second
                    )
                )
            )
        )

        assertEquals(0, publication.size())
        assertEquals(true, first.copyValues().all { it == 0f })
    }

    private fun unitVector(index: Int = 0): SemanticEmbeddingVector {
        val values = FloatArray(SemanticEmbeddingVector.DIMENSION)
        values[index] = 1f
        return SemanticEmbeddingVector(values)
    }
}
