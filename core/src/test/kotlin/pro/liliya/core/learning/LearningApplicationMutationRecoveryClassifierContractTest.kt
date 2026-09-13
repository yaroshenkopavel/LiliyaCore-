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
import pro.liliya.core.knowledge.KnowledgeItemSnapshot
import pro.liliya.core.knowledge.KnowledgeOrigin
import pro.liliya.core.knowledge.KnowledgeSourceId
import pro.liliya.core.knowledge.KnowledgeSourceReference
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemoryRecordSnapshot
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.memory.MemorySourceReference

class LearningApplicationMutationRecoveryClassifierContractTest {

    @Test
    fun no_prepared_mutations_is_clean_without_inspecting_downstream() {
        var inspections = 0

        val result = assertIs<LearningApplicationMutationRecoveryClassificationResult.Classified>(
            LearningApplicationMutationRecoveryClassifier.classify(
                preparedMutations = emptyList(),
                inspectMemory = {
                    inspections += 1
                    error("memory must not be inspected")
                },
                inspectKnowledge = {
                    inspections += 1
                    error("knowledge must not be inspected")
                }
            )
        )

        assertEquals(LearningApplicationMutationRecoverySummary.Clean, result.summary)
        assertFalse(result.summary.requiresRecovery)
        assertEquals(0, inspections)
    }

    @Test
    fun memory_prepared_mutations_classify_absent_exact_and_mismatched_state() {
        val absent = memorySnapshot("absent", "memory-absent", "content-a")
        val exact = memorySnapshot("exact", "memory-exact", "content-b")
        val mismatch = memorySnapshot("mismatch", "memory-mismatch", "content-c")
        val exactRecord = (exact.plan.payload as LearningApplicationMutationPayload.Memory).record
        val mismatchRecord = (mismatch.plan.payload as LearningApplicationMutationPayload.Memory).record

        val result = assertIs<LearningApplicationMutationRecoveryClassificationResult.Classified>(
            LearningApplicationMutationRecoveryClassifier.classify(
                preparedMutations = listOf(absent, exact, mismatch),
                inspectMemory = { id ->
                    when (id.value) {
                        "memory-absent" -> null
                        "memory-exact" -> MemoryRecordSnapshot(exactRecord, MemoryGeneration(7))
                        "memory-mismatch" -> MemoryRecordSnapshot(
                            mismatchRecord.copy(content = "different durable content"),
                            MemoryGeneration(8)
                        )
                        else -> error("unexpected memory id")
                    }
                },
                inspectKnowledge = { error("knowledge must not be inspected") }
            )
        )

        assertEquals(1, result.summary.noDownstream)
        assertEquals(1, result.summary.exactDownstreamPresent)
        assertEquals(1, result.summary.mismatchedDownstreamPresent)
        assertEquals(3, result.summary.totalPrepared)
        assertTrue(result.summary.requiresRecovery)
    }

    @Test
    fun knowledge_prepared_mutations_classify_absent_exact_and_mismatched_state() {
        val absent = knowledgeSnapshot("absent", "knowledge-absent", "content-a")
        val exact = knowledgeSnapshot("exact", "knowledge-exact", "content-b")
        val mismatch = knowledgeSnapshot("mismatch", "knowledge-mismatch", "content-c")
        val exactItem = (exact.plan.payload as LearningApplicationMutationPayload.Knowledge).item
        val mismatchItem = (mismatch.plan.payload as LearningApplicationMutationPayload.Knowledge).item

        val result = assertIs<LearningApplicationMutationRecoveryClassificationResult.Classified>(
            LearningApplicationMutationRecoveryClassifier.classify(
                preparedMutations = listOf(absent, exact, mismatch),
                inspectMemory = { error("memory must not be inspected") },
                inspectKnowledge = { id ->
                    when (id.value) {
                        "knowledge-absent" -> null
                        "knowledge-exact" -> KnowledgeItemSnapshot(
                            exactItem,
                            KnowledgeGeneration(11)
                        )
                        "knowledge-mismatch" -> KnowledgeItemSnapshot(
                            mismatchItem.copy(content = "different durable content"),
                            KnowledgeGeneration(12)
                        )
                        else -> error("unexpected knowledge id")
                    }
                }
            )
        )

        assertEquals(1, result.summary.noDownstream)
        assertEquals(1, result.summary.exactDownstreamPresent)
        assertEquals(1, result.summary.mismatchedDownstreamPresent)
        assertEquals(3, result.summary.totalPrepared)
        assertTrue(result.summary.requiresRecovery)
    }

    @Test
    fun mixed_targets_produce_bounded_deterministic_counts_only() {
        val memory = memorySnapshot("mixed-memory", "memory-mixed", "memory")
        val knowledge = knowledgeSnapshot("mixed-knowledge", "knowledge-mixed", "knowledge")
        val memoryRecord = (memory.plan.payload as LearningApplicationMutationPayload.Memory).record

        val result = assertIs<LearningApplicationMutationRecoveryClassificationResult.Classified>(
            LearningApplicationMutationRecoveryClassifier.classify(
                preparedMutations = listOf(memory, knowledge),
                inspectMemory = { MemoryRecordSnapshot(memoryRecord, MemoryGeneration(4)) },
                inspectKnowledge = { null }
            )
        )

        assertEquals(
            LearningApplicationMutationRecoverySummary(
                noDownstream = 1,
                exactDownstreamPresent = 1,
                mismatchedDownstreamPresent = 0
            ),
            result.summary
        )
    }

    @Test
    fun downstream_inspection_exception_fails_closed_without_partial_summary() {
        val prepared = memorySnapshot("failed", "memory-failed", "payload")

        assertEquals(
            LearningApplicationMutationRecoveryClassificationResult.Failed,
            LearningApplicationMutationRecoveryClassifier.classify(
                preparedMutations = listOf(prepared),
                inspectMemory = { throw IllegalStateException("private backend detail") },
                inspectKnowledge = { null }
            )
        )
    }

    private fun memorySnapshot(
        suffix: String,
        recordId: String,
        content: String
    ): LearningApplicationMutationSnapshot {
        val createdAt = Instant.parse("2026-09-13T10:00:00Z")
        return snapshot(
            suffix = suffix,
            target = LearningApplicationTarget.MEMORY,
            payload = LearningApplicationMutationPayload.Memory(
                MemoryRecord(
                    id = MemoryRecordId(recordId),
                    provenance = MemoryProvenance(
                        sourceId = MemorySourceId("cognitive-runtime-learning"),
                        sourceReference = MemorySourceReference("learning-$suffix")
                    ),
                    content = content,
                    createdAt = createdAt
                )
            ),
            createdAt = createdAt
        )
    }

    private fun knowledgeSnapshot(
        suffix: String,
        itemId: String,
        content: String
    ): LearningApplicationMutationSnapshot {
        val createdAt = Instant.parse("2026-09-13T10:00:00Z")
        return snapshot(
            suffix = suffix,
            target = LearningApplicationTarget.KNOWLEDGE,
            payload = LearningApplicationMutationPayload.Knowledge(
                KnowledgeItem(
                    id = KnowledgeItemId(itemId),
                    origin = KnowledgeOrigin.Declared(
                        sourceId = KnowledgeSourceId("cognitive-runtime-learning"),
                        sourceReference = KnowledgeSourceReference("learning-$suffix")
                    ),
                    content = content,
                    createdAt = createdAt
                )
            ),
            createdAt = createdAt
        )
    }

    private fun snapshot(
        suffix: String,
        target: LearningApplicationTarget,
        payload: LearningApplicationMutationPayload,
        createdAt: Instant
    ): LearningApplicationMutationSnapshot = LearningApplicationMutationSnapshot(
        plan = LearningApplicationMutationPlan(
            id = LearningApplicationMutationId("mutation-$suffix"),
            application = LearningApplicationIntentReference(
                applicationId = LearningApplicationId("application-$suffix"),
                generation = LearningApplicationGeneration(1)
            ),
            principal = AuthorityPrincipal("learning-recovery-test"),
            target = target,
            idempotencyKey = LearningApplicationIdempotencyKey("idempotency-$suffix"),
            payload = payload,
            createdAt = createdAt
        ),
        generation = LearningApplicationMutationGeneration(1)
    )
}
