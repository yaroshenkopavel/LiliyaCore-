package pro.liliya.core.learning

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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

class LearningApplicationMutationRecoveryEvidenceClassifierContractTest {

    @Test
    fun exact_memory_and_knowledge_preserve_mutation_and_downstream_generations() {
        val memory = memorySnapshot("memory", "memory-1", "memory payload", generation = 3)
        val knowledge = knowledgeSnapshot("knowledge", "knowledge-1", "knowledge payload", generation = 5)
        val memoryRecord = (memory.plan.payload as LearningApplicationMutationPayload.Memory).record
        val knowledgeItem = (knowledge.plan.payload as LearningApplicationMutationPayload.Knowledge).item

        val result = assertIs<LearningApplicationMutationRecoveryEvidenceResult.Classified>(
            LearningApplicationMutationRecoveryEvidenceClassifier.classify(
                preparedMutations = listOf(memory, knowledge),
                inspectMemory = { MemoryRecordSnapshot(memoryRecord, MemoryGeneration(7)) },
                inspectKnowledge = { KnowledgeItemSnapshot(knowledgeItem, KnowledgeGeneration(11)) }
            )
        )

        assertEquals(2, result.summary.exactDownstream.size)
        assertEquals(0, result.summary.noDownstream)
        assertEquals(0, result.summary.mismatchedDownstreamPresent)
        assertEquals(2, result.summary.totalPrepared)

        val memoryEvidence = result.summary.exactDownstream[0]
        assertEquals(LearningApplicationMutationId("mutation-memory"), memoryEvidence.mutation.mutationId)
        assertEquals(LearningApplicationMutationGeneration(3), memoryEvidence.mutation.generation)
        assertEquals(LearningApplicationTarget.MEMORY, memoryEvidence.target)
        assertEquals(
            LearningApplicationDownstreamReference.Memory(
                recordId = MemoryRecordId("memory-1"),
                generation = MemoryGeneration(7)
            ),
            memoryEvidence.downstream
        )

        val knowledgeEvidence = result.summary.exactDownstream[1]
        assertEquals(LearningApplicationMutationId("mutation-knowledge"), knowledgeEvidence.mutation.mutationId)
        assertEquals(LearningApplicationMutationGeneration(5), knowledgeEvidence.mutation.generation)
        assertEquals(LearningApplicationTarget.KNOWLEDGE, knowledgeEvidence.target)
        assertEquals(
            LearningApplicationDownstreamReference.Knowledge(
                itemId = KnowledgeItemId("knowledge-1"),
                generation = KnowledgeGeneration(11)
            ),
            knowledgeEvidence.downstream
        )
    }

    @Test
    fun absent_and_mismatched_downstream_never_emit_exact_evidence() {
        val absent = memorySnapshot("absent", "memory-absent", "a")
        val mismatch = knowledgeSnapshot("mismatch", "knowledge-mismatch", "b")
        val mismatchItem = (mismatch.plan.payload as LearningApplicationMutationPayload.Knowledge).item

        val result = assertIs<LearningApplicationMutationRecoveryEvidenceResult.Classified>(
            LearningApplicationMutationRecoveryEvidenceClassifier.classify(
                preparedMutations = listOf(absent, mismatch),
                inspectMemory = { null },
                inspectKnowledge = {
                    KnowledgeItemSnapshot(
                        mismatchItem.copy(content = "different durable payload"),
                        KnowledgeGeneration(9)
                    )
                }
            )
        )

        assertEquals(emptyList(), result.summary.exactDownstream)
        assertEquals(1, result.summary.noDownstream)
        assertEquals(1, result.summary.mismatchedDownstreamPresent)
        assertEquals(2, result.summary.totalPrepared)
    }

    @Test
    fun inspection_failure_returns_failed_without_partial_exact_evidence() {
        val exact = memorySnapshot("exact", "memory-exact", "payload")
        val later = knowledgeSnapshot("later", "knowledge-later", "payload")
        val exactRecord = (exact.plan.payload as LearningApplicationMutationPayload.Memory).record

        assertEquals(
            LearningApplicationMutationRecoveryEvidenceResult.Failed,
            LearningApplicationMutationRecoveryEvidenceClassifier.classify(
                preparedMutations = listOf(exact, later),
                inspectMemory = { MemoryRecordSnapshot(exactRecord, MemoryGeneration(4)) },
                inspectKnowledge = { throw IllegalStateException("private backend detail") }
            )
        )
    }

    @Test
    fun exact_evidence_rejects_target_downstream_type_mismatch() {
        assertFailsWith<IllegalArgumentException> {
            LearningApplicationMutationExactRecoveryEvidence(
                mutation = LearningApplicationMutationReference(
                    mutationId = LearningApplicationMutationId("mutation-invalid"),
                    generation = LearningApplicationMutationGeneration(1)
                ),
                target = LearningApplicationTarget.MEMORY,
                downstream = LearningApplicationDownstreamReference.Knowledge(
                    itemId = KnowledgeItemId("knowledge-invalid"),
                    generation = KnowledgeGeneration(1)
                )
            )
        }
    }

    private fun memorySnapshot(
        suffix: String,
        recordId: String,
        content: String,
        generation: Long = 1
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
            createdAt = createdAt,
            generation = generation
        )
    }

    private fun knowledgeSnapshot(
        suffix: String,
        itemId: String,
        content: String,
        generation: Long = 1
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
            createdAt = createdAt,
            generation = generation
        )
    }

    private fun snapshot(
        suffix: String,
        target: LearningApplicationTarget,
        payload: LearningApplicationMutationPayload,
        createdAt: Instant,
        generation: Long
    ): LearningApplicationMutationSnapshot = LearningApplicationMutationSnapshot(
        plan = LearningApplicationMutationPlan(
            id = LearningApplicationMutationId("mutation-$suffix"),
            application = LearningApplicationIntentReference(
                applicationId = LearningApplicationId("application-$suffix"),
                generation = LearningApplicationGeneration(1)
            ),
            principal = AuthorityPrincipal("learning-recovery-evidence-test"),
            target = target,
            idempotencyKey = LearningApplicationIdempotencyKey("idempotency-$suffix"),
            payload = payload,
            createdAt = createdAt
        ),
        generation = LearningApplicationMutationGeneration(generation)
    )
}
