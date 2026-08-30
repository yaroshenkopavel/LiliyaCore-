package pro.liliya.core.learning

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItem
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeOrigin
import pro.liliya.core.knowledge.KnowledgeSourceId
import pro.liliya.core.knowledge.KnowledgeSourceReference
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.memory.MemorySourceReference
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentSchemaVersion

class LearningApplicationMutationPersistenceCodecRestorationContractTest {
    private val createdAt = Instant.parse("2026-08-30T16:10:00Z")

    private fun memoryPlan(
        id: String = "mutation-memory",
        key: String = "idempotency-memory",
        content: String = "private memory learning payload"
    ) = LearningApplicationMutationPlan(
        id = LearningApplicationMutationId(id),
        application = LearningApplicationIntentReference(
            LearningApplicationId("application-memory"),
            LearningApplicationGeneration(4)
        ),
        principal = AuthorityPrincipal("assistant-core"),
        target = LearningApplicationTarget.MEMORY,
        idempotencyKey = LearningApplicationIdempotencyKey(key),
        payload = LearningApplicationMutationPayload.Memory(
            MemoryRecord(
                id = MemoryRecordId("memory-learned"),
                provenance = MemoryProvenance(
                    sourceId = MemorySourceId("learning"),
                    sourceReference = MemorySourceReference("decision-17")
                ),
                content = content,
                createdAt = createdAt.minusSeconds(5)
            )
        ),
        createdAt = createdAt
    )

    private fun knowledgePlan(
        id: String = "mutation-knowledge",
        key: String = "idempotency-knowledge",
        content: String = "private knowledge learning payload"
    ) = LearningApplicationMutationPlan(
        id = LearningApplicationMutationId(id),
        application = LearningApplicationIntentReference(
            LearningApplicationId("application-knowledge"),
            LearningApplicationGeneration(8)
        ),
        principal = AuthorityPrincipal("assistant-core"),
        target = LearningApplicationTarget.KNOWLEDGE,
        idempotencyKey = LearningApplicationIdempotencyKey(key),
        payload = LearningApplicationMutationPayload.Knowledge(
            KnowledgeItem(
                id = KnowledgeItemId("knowledge-learned"),
                origin = KnowledgeOrigin.Declared(
                    sourceId = KnowledgeSourceId("learning"),
                    sourceReference = KnowledgeSourceReference("candidate-23")
                ),
                content = content,
                createdAt = createdAt.minusSeconds(3)
            )
        ),
        createdAt = createdAt.plusSeconds(1)
    )

    @Test
    fun prepared_memory_payload_round_trips_exactly() {
        val plan = memoryPlan()
        val decoded = assertIs<LearningApplicationMutationPersistentDecodeResult.Prepared>(
            LearningApplicationMutationPersistentCodec.decode(
                LearningApplicationMutationPersistentCodec.encodePrepared(plan)
            )
        )
        assertEquals(plan, decoded.plan)
        assertFalse(decoded.toString().contains("private memory learning payload"))
    }

    @Test
    fun prepared_knowledge_payload_round_trips_exactly() {
        val plan = knowledgePlan()
        val decoded = assertIs<LearningApplicationMutationPersistentDecodeResult.Prepared>(
            LearningApplicationMutationPersistentCodec.decode(
                LearningApplicationMutationPersistentCodec.encodePrepared(plan)
            )
        )
        assertEquals(plan, decoded.plan)
        assertFalse(decoded.toString().contains("private knowledge learning payload"))
    }

    @Test
    fun completed_receipts_round_trip_for_memory_and_knowledge() {
        val memoryPlan = memoryPlan()
        val memoryReceipt = LearningApplicationMutationApplicationReceipt(
            mutation = LearningApplicationMutationReference(
                memoryPlan.id,
                LearningApplicationMutationGeneration(11)
            ),
            target = LearningApplicationTarget.MEMORY,
            downstream = LearningApplicationDownstreamReference.Memory(
                MemoryRecordId("memory-learned"),
                MemoryGeneration(31)
            )
        )
        val memoryDecoded = assertIs<LearningApplicationMutationPersistentDecodeResult.Completed>(
            LearningApplicationMutationPersistentCodec.decode(
                LearningApplicationMutationPersistentCodec.encodeCompleted(memoryPlan, memoryReceipt)
            )
        )
        assertEquals(memoryPlan, memoryDecoded.plan)
        assertEquals(memoryReceipt, memoryDecoded.receipt)

        val knowledgePlan = knowledgePlan()
        val knowledgeReceipt = LearningApplicationMutationApplicationReceipt(
            mutation = LearningApplicationMutationReference(
                knowledgePlan.id,
                LearningApplicationMutationGeneration(12)
            ),
            target = LearningApplicationTarget.KNOWLEDGE,
            downstream = LearningApplicationDownstreamReference.Knowledge(
                KnowledgeItemId("knowledge-learned"),
                KnowledgeGeneration(41)
            )
        )
        val knowledgeDecoded = assertIs<LearningApplicationMutationPersistentDecodeResult.Completed>(
            LearningApplicationMutationPersistentCodec.decode(
                LearningApplicationMutationPersistentCodec.encodeCompleted(knowledgePlan, knowledgeReceipt)
            )
        )
        assertEquals(knowledgePlan, knowledgeDecoded.plan)
        assertEquals(knowledgeReceipt, knowledgeDecoded.receipt)
    }

    @Test
    fun codec_rejects_trailing_mismatched_and_incompatible_records() {
        val plan = memoryPlan()
        val encoded = LearningApplicationMutationPersistentCodec.encodePrepared(plan)

        val trailing = encoded.copy(
            payload = PersistentPayload(encoded.payload.copyBytes() + byteArrayOf(1))
        )
        assertIs<LearningApplicationMutationPersistentDecodeResult.Corrupt>(
            LearningApplicationMutationPersistentCodec.decode(trailing)
        )

        val mismatchedId = encoded.copy(id = PersistentEntityId("learning-mutation:prepared:other"))
        assertIs<LearningApplicationMutationPersistentDecodeResult.Corrupt>(
            LearningApplicationMutationPersistentCodec.decode(mismatchedId)
        )

        val incompatible = encoded.copy(schemaVersion = PersistentSchemaVersion(2))
        assertIs<LearningApplicationMutationPersistentDecodeResult.Incompatible>(
            LearningApplicationMutationPersistentCodec.decode(incompatible)
        )
    }

    @Test
    fun restoration_preserves_exact_generations_high_watermark_indexes_and_ordering() {
        val earlier = memoryPlan(id = "mutation-z", key = "key-z")
        val later = knowledgePlan(id = "mutation-a", key = "key-a")
        val completedPlan = memoryPlan(id = "mutation-completed", key = "key-completed")
        val receipt = LearningApplicationMutationApplicationReceipt(
            mutation = LearningApplicationMutationReference(
                completedPlan.id,
                LearningApplicationMutationGeneration(7)
            ),
            target = LearningApplicationTarget.MEMORY,
            downstream = LearningApplicationDownstreamReference.Memory(
                MemoryRecordId("memory-completed"),
                MemoryGeneration(22)
            )
        )

        val restored = assertIs<LearningApplicationMutationRestorationResult.Restored>(
            LearningApplicationMutationRestorationBoundary.restore(
                liveEntries = listOf(
                    LearningPreparedPersistentState(later, LearningApplicationMutationGeneration(9)),
                    LearningPreparedPersistentState(earlier, LearningApplicationMutationGeneration(4))
                ),
                completedEntries = listOf(LearningCompletedPersistentState(completedPlan, receipt)),
                highWatermark = 12
            )
        ).state

        assertEquals(12, restored.highWatermark)
        assertEquals(listOf(earlier.id, later.id), restored.liveEntries.map { it.plan.id })
        assertEquals(listOf(4L, 9L), restored.liveEntries.map { it.generation.value })
        assertEquals(receipt, restored.completedByMutationId[completedPlan.id])
        assertEquals(receipt, restored.completedByIdempotencyKey[completedPlan.idempotencyKey])
    }

    @Test
    fun restoration_rejects_duplicate_or_overlapping_live_completed_state() {
        val first = memoryPlan(id = "mutation-1", key = "key-1")
        val duplicateKey = knowledgePlan(id = "mutation-2", key = "key-1")
        assertIs<LearningApplicationMutationRestorationResult.Rejected>(
            LearningApplicationMutationRestorationBoundary.restore(
                liveEntries = listOf(
                    LearningPreparedPersistentState(first, LearningApplicationMutationGeneration(1)),
                    LearningPreparedPersistentState(duplicateKey, LearningApplicationMutationGeneration(2))
                ),
                completedEntries = emptyList(),
                highWatermark = 2
            )
        )

        val receipt = LearningApplicationMutationApplicationReceipt(
            mutation = LearningApplicationMutationReference(first.id, LearningApplicationMutationGeneration(1)),
            target = LearningApplicationTarget.MEMORY,
            downstream = LearningApplicationDownstreamReference.Memory(
                MemoryRecordId("memory-1"),
                MemoryGeneration(1)
            )
        )
        assertIs<LearningApplicationMutationRestorationResult.Rejected>(
            LearningApplicationMutationRestorationBoundary.restore(
                liveEntries = listOf(
                    LearningPreparedPersistentState(first, LearningApplicationMutationGeneration(1))
                ),
                completedEntries = listOf(LearningCompletedPersistentState(first, receipt)),
                highWatermark = 1
            )
        )
    }

    @Test
    fun restoration_rejects_generation_above_high_watermark_and_invalid_completed_receipt() {
        val live = memoryPlan(id = "mutation-live", key = "key-live")
        assertIs<LearningApplicationMutationRestorationResult.Rejected>(
            LearningApplicationMutationRestorationBoundary.restore(
                liveEntries = listOf(
                    LearningPreparedPersistentState(live, LearningApplicationMutationGeneration(3))
                ),
                completedEntries = emptyList(),
                highWatermark = 2
            )
        )

        val completedPlan = knowledgePlan(id = "mutation-completed", key = "key-completed")
        val invalidReceipt = LearningApplicationMutationApplicationReceipt(
            mutation = LearningApplicationMutationReference(
                LearningApplicationMutationId("other"),
                LearningApplicationMutationGeneration(2)
            ),
            target = LearningApplicationTarget.KNOWLEDGE,
            downstream = LearningApplicationDownstreamReference.Knowledge(
                KnowledgeItemId("knowledge-learned"),
                KnowledgeGeneration(2)
            )
        )
        assertIs<LearningApplicationMutationRestorationResult.Rejected>(
            LearningApplicationMutationRestorationBoundary.restore(
                liveEntries = emptyList(),
                completedEntries = listOf(LearningCompletedPersistentState(completedPlan, invalidReceipt)),
                highWatermark = 2
            )
        )
    }

    @Test
    fun restored_state_is_detached_from_input_lists() {
        val plan = memoryPlan()
        val source = mutableListOf(
            LearningPreparedPersistentState(plan, LearningApplicationMutationGeneration(1))
        )
        val restored = assertIs<LearningApplicationMutationRestorationResult.Restored>(
            LearningApplicationMutationRestorationBoundary.restore(source, emptyList(), 1)
        ).state
        source.clear()
        assertEquals(1, restored.liveEntries.size)
        assertTrue(restored.completedEntries.isEmpty())
    }
}
