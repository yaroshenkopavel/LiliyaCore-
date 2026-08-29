package pro.liliya.core.learning

import java.time.Instant
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId
import kotlin.test.Test
import kotlin.test.assertEquals

class LearningConsolidationModelsContractTest {
    private fun receipt(id: String, generation: Long): LearningApplicationMutationApplicationReceipt =
        LearningApplicationMutationApplicationReceipt(
            mutation = LearningApplicationMutationReference(
                LearningApplicationMutationId(id),
                LearningApplicationMutationGeneration(generation)
            ),
            target = LearningApplicationTarget.MEMORY,
            downstream = LearningApplicationDownstreamReference.Memory(
                MemoryRecordId("memory-$id"),
                MemoryGeneration(generation)
            )
        )

    @Test
    fun source_order_is_canonical_and_exposed_lists_cannot_mutate_internal_snapshot() {
        val a = receipt("a", 1L)
        val b = receipt("b", 2L)
        val first = LearningConsolidationProposal(
            LearningConsolidationId("consolidation"),
            listOf(b, a),
            "proposal",
            Instant.parse("2026-08-29T11:10:00Z")
        )
        val second = LearningConsolidationProposal(
            LearningConsolidationId("consolidation"),
            listOf(a, b),
            "proposal",
            Instant.parse("2026-08-29T11:10:00Z")
        )

        assertEquals(listOf(a, b), first.sources)
        assertEquals(first, second)

        @Suppress("UNCHECKED_CAST")
        val exposed = first.sources as MutableList<LearningApplicationMutationApplicationReceipt>
        exposed.clear()

        assertEquals(listOf(a, b), first.sources)
        assertEquals(first, second)
    }
}
