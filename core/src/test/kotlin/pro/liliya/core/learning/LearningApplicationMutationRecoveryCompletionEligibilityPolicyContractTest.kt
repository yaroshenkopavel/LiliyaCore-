package pro.liliya.core.learning

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
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

class LearningApplicationMutationRecoveryCompletionEligibilityPolicyContractTest {

    @Test
    fun no_prepared_entries_require_no_recovery() {
        assertDecision(
            expected = LearningApplicationMutationRecoveryCompletionEligibilityDecision
                .NO_RECOVERY_REQUIRED,
            prepared = emptyList(),
            summary = summary()
        )
    }

    @Test
    fun all_current_prepared_entries_with_unique_exact_evidence_are_ready() {
        val memory = memorySnapshot("one", "memory-one")
        val knowledge = knowledgeSnapshot("two", "knowledge-two")
        assertDecision(
            expected = LearningApplicationMutationRecoveryCompletionEligibilityDecision
                .EXACT_COMPLETION_EVIDENCE_READY,
            prepared = listOf(memory, knowledge),
            summary = summary(
                exact = listOf(
                    memoryEvidence(memory, 5),
                    knowledgeEvidence(knowledge, 7)
                )
            )
        )
    }

    @Test
    fun no_downstream_only_requires_fresh_authority_replay() {
        val prepared = listOf(
            memorySnapshot("one", "memory-one"),
            memorySnapshot("two", "memory-two"),
            knowledgeSnapshot("three", "knowledge-three")
        )
        assertDecision(
            expected = LearningApplicationMutationRecoveryCompletionEligibilityDecision
                .REPLAY_REQUIRES_FRESH_AUTHORITY,
            prepared = prepared,
            summary = summary(noDownstream = prepared.size)
        )
    }

    @Test
    fun mismatch_dominates_exact_absent_and_inconsistent_evidence() {
        val prepared = memorySnapshot("duplicate", "memory-duplicate")
        val duplicate = memoryEvidence(prepared, 4)
        assertDecision(
            expected = LearningApplicationMutationRecoveryCompletionEligibilityDecision
                .MISMATCH_BLOCKED,
            prepared = listOf(prepared),
            summary = summary(
                exact = listOf(duplicate, duplicate),
                noDownstream = 1,
                mismatched = 1
            )
        )
    }

    @Test
    fun mixed_exact_and_no_downstream_blocks_partial_automatic_recovery() {
        val exact = memorySnapshot("exact", "memory-exact")
        val absent = knowledgeSnapshot("absent", "knowledge-absent")
        assertDecision(
            expected = LearningApplicationMutationRecoveryCompletionEligibilityDecision
                .MIXED_RECOVERY_BLOCKED,
            prepared = listOf(exact, absent),
            summary = summary(
                exact = listOf(memoryEvidence(exact, 8)),
                noDownstream = 1
            )
        )
    }

    @Test
    fun duplicate_mutation_reference_in_exact_evidence_fails_closed() {
        val prepared = memorySnapshot("same", "memory-one")
        val first = memoryEvidence(prepared, 2)
        val second = first.copy(
            downstream = LearningApplicationDownstreamReference.Memory(
                recordId = MemoryRecordId("memory-two"),
                generation = MemoryGeneration(3)
            )
        )
        assertDecision(
            expected = LearningApplicationMutationRecoveryCompletionEligibilityDecision
                .EVIDENCE_INCONSISTENT,
            prepared = listOf(prepared, memorySnapshot("other", "memory-two")),
            summary = summary(exact = listOf(first, second))
        )
    }

    @Test
    fun duplicate_downstream_reference_in_exact_evidence_fails_closed() {
        val one = memorySnapshot("one", "shared-memory")
        val two = memorySnapshot("two", "shared-memory")
        assertDecision(
            expected = LearningApplicationMutationRecoveryCompletionEligibilityDecision
                .EVIDENCE_INCONSISTENT,
            prepared = listOf(one, two),
            summary = summary(
                exact = listOf(memoryEvidence(one, 9), memoryEvidence(two, 9))
            )
        )
    }

    @Test
    fun evidence_count_must_cover_the_complete_current_prepared_set() {
        val one = memorySnapshot("one", "memory-one")
        val two = knowledgeSnapshot("two", "knowledge-two")
        assertDecision(
            expected = LearningApplicationMutationRecoveryCompletionEligibilityDecision
                .EVIDENCE_INCONSISTENT,
            prepared = listOf(one, two),
            summary = summary(exact = listOf(memoryEvidence(one, 4)))
        )
    }

    @Test
    fun exact_evidence_must_reference_the_current_prepared_identity_and_payload_target() {
        val current = memorySnapshot("current", "memory-current", generation = 4)
        val staleGeneration = memoryEvidence(current, 7).copy(
            mutation = LearningApplicationMutationReference(
                current.plan.id,
                LearningApplicationMutationGeneration(3)
            )
        )
        assertDecision(
            expected = LearningApplicationMutationRecoveryCompletionEligibilityDecision
                .EVIDENCE_INCONSISTENT,
            prepared = listOf(current),
            summary = summary(exact = listOf(staleGeneration))
        )

        val wrongId = memoryEvidence(current, 7).copy(
            downstream = LearningApplicationDownstreamReference.Memory(
                recordId = MemoryRecordId("memory-other"),
                generation = MemoryGeneration(7)
            )
        )
        assertDecision(
            expected = LearningApplicationMutationRecoveryCompletionEligibilityDecision
                .EVIDENCE_INCONSISTENT,
            prepared = listOf(current),
            summary = summary(exact = listOf(wrongId))
        )
    }

    @Test
    fun evidence_classification_failure_fails_closed() {
        assertEquals(
            LearningApplicationMutationRecoveryCompletionEligibilityDecision.EVIDENCE_FAILED,
            LearningApplicationMutationRecoveryCompletionEligibilityPolicy.decide(
                preparedMutations = listOf(memorySnapshot("failed", "memory-failed")),
                evidence = LearningApplicationMutationRecoveryEvidenceResult.Failed
            )
        )
    }

    private fun assertDecision(
        expected: LearningApplicationMutationRecoveryCompletionEligibilityDecision,
        prepared: List<LearningApplicationMutationSnapshot>,
        summary: LearningApplicationMutationRecoveryEvidenceSummary
    ) {
        assertEquals(
            expected,
            LearningApplicationMutationRecoveryCompletionEligibilityPolicy.decide(
                preparedMutations = prepared,
                evidence = LearningApplicationMutationRecoveryEvidenceResult.Classified(summary)
            )
        )
    }

    private fun summary(
        exact: List<LearningApplicationMutationExactRecoveryEvidence> = emptyList(),
        noDownstream: Int = 0,
        mismatched: Int = 0
    ): LearningApplicationMutationRecoveryEvidenceSummary =
        LearningApplicationMutationRecoveryEvidenceSummary(
            exactDownstream = exact,
            noDownstream = noDownstream,
            mismatchedDownstreamPresent = mismatched
        )

    private fun memoryEvidence(
        prepared: LearningApplicationMutationSnapshot,
        downstreamGeneration: Long
    ): LearningApplicationMutationExactRecoveryEvidence {
        val record = (prepared.plan.payload as LearningApplicationMutationPayload.Memory).record
        return LearningApplicationMutationExactRecoveryEvidence(
            mutation = referenceOf(prepared),
            target = LearningApplicationTarget.MEMORY,
            downstream = LearningApplicationDownstreamReference.Memory(
                recordId = record.id,
                generation = MemoryGeneration(downstreamGeneration)
            )
        )
    }

    private fun knowledgeEvidence(
        prepared: LearningApplicationMutationSnapshot,
        downstreamGeneration: Long
    ): LearningApplicationMutationExactRecoveryEvidence {
        val item = (prepared.plan.payload as LearningApplicationMutationPayload.Knowledge).item
        return LearningApplicationMutationExactRecoveryEvidence(
            mutation = referenceOf(prepared),
            target = LearningApplicationTarget.KNOWLEDGE,
            downstream = LearningApplicationDownstreamReference.Knowledge(
                itemId = item.id,
                generation = KnowledgeGeneration(downstreamGeneration)
            )
        )
    }

    private fun referenceOf(
        snapshot: LearningApplicationMutationSnapshot
    ): LearningApplicationMutationReference =
        LearningApplicationMutationReference(snapshot.plan.id, snapshot.generation)

    private fun memorySnapshot(
        suffix: String,
        recordId: String,
        generation: Long = 1
    ): LearningApplicationMutationSnapshot {
        val createdAt = Instant.parse("2026-09-13T18:00:00Z")
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
                    content = "payload-$suffix",
                    createdAt = createdAt
                )
            ),
            generation = generation,
            createdAt = createdAt
        )
    }

    private fun knowledgeSnapshot(
        suffix: String,
        itemId: String,
        generation: Long = 1
    ): LearningApplicationMutationSnapshot {
        val createdAt = Instant.parse("2026-09-13T18:00:00Z")
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
                    content = "payload-$suffix",
                    createdAt = createdAt
                )
            ),
            generation = generation,
            createdAt = createdAt
        )
    }

    private fun snapshot(
        suffix: String,
        target: LearningApplicationTarget,
        payload: LearningApplicationMutationPayload,
        generation: Long,
        createdAt: Instant
    ): LearningApplicationMutationSnapshot = LearningApplicationMutationSnapshot(
        plan = LearningApplicationMutationPlan(
            id = LearningApplicationMutationId("mutation-$suffix"),
            application = LearningApplicationIntentReference(
                applicationId = LearningApplicationId("application-$suffix"),
                generation = LearningApplicationGeneration(1)
            ),
            principal = AuthorityPrincipal("learning-recovery-completion-policy-test"),
            target = target,
            idempotencyKey = LearningApplicationIdempotencyKey("idempotency-$suffix"),
            payload = payload,
            createdAt = createdAt
        ),
        generation = LearningApplicationMutationGeneration(generation)
    )
}
